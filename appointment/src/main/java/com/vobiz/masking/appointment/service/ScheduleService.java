package com.vobiz.masking.appointment.service;

import com.vobiz.masking.appointment.model.Appointment;
import com.vobiz.masking.appointment.model.DoctorSchedule;
import com.vobiz.masking.appointment.model.DoctorTimeOff;
import com.vobiz.masking.appointment.repo.AppointmentRepository;
import com.vobiz.masking.appointment.repo.DoctorRepository;
import com.vobiz.masking.appointment.repo.DoctorScheduleRepository;
import com.vobiz.masking.appointment.repo.DoctorTimeOffRepository;
import com.vobiz.masking.service.SettingsService;
import com.vobiz.masking.util.ApiException;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Weekly schedule templates + time-off blocks. Adding a time-off block that overlaps booked
 * appointments marks those appointments {@code needs_rebooking} and enqueues an immediate
 * change-notification reminder for each affected patient.
 */
public class ScheduleService {

    private static final Set<String> CLOSED_STATUSES = Set.of(
            Appointment.STATUS_CANCELLED, Appointment.STATUS_COMPLETED, Appointment.STATUS_NO_SHOW);

    private final DoctorScheduleRepository scheduleRepo;
    private final DoctorTimeOffRepository timeOffRepo;
    private final DoctorRepository doctorRepo;
    private final AppointmentRepository appointmentRepo;
    private final ReminderService reminderService;
    private final SettingsService settings;

    public ScheduleService(DoctorScheduleRepository scheduleRepo, DoctorTimeOffRepository timeOffRepo,
                           DoctorRepository doctorRepo, AppointmentRepository appointmentRepo,
                           ReminderService reminderService, SettingsService settings) {
        this.scheduleRepo = scheduleRepo;
        this.timeOffRepo = timeOffRepo;
        this.doctorRepo = doctorRepo;
        this.appointmentRepo = appointmentRepo;
        this.reminderService = reminderService;
        this.settings = settings;
    }

    // ---------- weekly templates ----------

    public List<DoctorSchedule> listForDoctor(long doctorId) {
        return scheduleRepo.findByDoctorIdAndActiveTrueOrderByDayOfWeekAscStartTimeAsc(doctorId);
    }

    @Transactional
    public DoctorSchedule addSchedule(long doctorId, int dayOfWeek, LocalTime start, LocalTime end, int slotMinutes) {
        doctorRepo.findById(doctorId).orElseThrow(() -> new ApiException(404, "Doctor not found: " + doctorId));
        if (dayOfWeek < 1 || dayOfWeek > 7) throw new ApiException(400, "day_of_week must be 1..7 (ISO, 1=Monday)");
        if (start == null || end == null || !start.isBefore(end))
            throw new ApiException(400, "start_time must be before end_time");
        if (slotMinutes < 0 || slotMinutes > 120) throw new ApiException(400, "slot_minutes must be 0..120 (0 = inherit)");
        DoctorSchedule s = new DoctorSchedule();
        s.setDoctorId(doctorId);
        s.setDayOfWeek(dayOfWeek);
        s.setStartTime(start);
        s.setEndTime(end);
        s.setSlotMinutes(slotMinutes);
        s.setActive(true);
        try {
            return scheduleRepo.save(s);
        } catch (DataIntegrityViolationException e) {
            throw new ApiException(409, "An active schedule row already exists for that doctor/day/start time");
        }
    }

    @Transactional
    public void deleteSchedule(long id) {
        DoctorSchedule s = scheduleRepo.findById(id).orElseThrow(() -> new ApiException(404, "Schedule not found: " + id));
        s.setActive(false);
        scheduleRepo.save(s);
    }

    // ---------- time off ----------

    public List<DoctorTimeOff> listTimeOff(long doctorId) {
        Instant now = Instant.now();
        return timeOffRepo.findOverlapping(doctorId, now.minusSeconds(366L * 86400), now.plusSeconds(366L * 86400));
    }

    /**
     * Adds a time-off block and, in the same transaction, flags every overlapping booked
     * appointment as {@code needs_rebooking} and enqueues a 'change' reminder for its patient.
     */
    @Transactional
    public DoctorTimeOff addTimeOff(long doctorId, Instant from, Instant to, boolean fullDay, String reason) {
        doctorRepo.findById(doctorId).orElseThrow(() -> new ApiException(404, "Doctor not found: " + doctorId));
        if (from == null || to == null || !from.isBefore(to))
            throw new ApiException(400, "from_ts must be before to_ts");
        DoctorTimeOff t = new DoctorTimeOff();
        t.setDoctorId(doctorId);
        t.setFromTs(from);
        t.setToTs(to);
        t.setFullDay(fullDay);
        t.setReason(reason);
        t = timeOffRepo.save(t);
        if (settings.changeNotificationEnabled()) {
            for (Appointment a : appointmentRepo.findByDoctorIdAndStartsAtBetweenOrderByStartsAtAsc(
                    doctorId, from.minusSeconds(86400), to.plusSeconds(86400))) {
                if (CLOSED_STATUSES.contains(a.getStatus())) continue;
                if (a.getStartsAt().isBefore(to) && a.getEndsAt() != null && a.getEndsAt().isAfter(from)) {
                    a.setNeedsRebooking(true);
                    appointmentRepo.save(a);
                    reminderService.enqueueChange(a, "Doctor unavailable (" + (reason == null ? "time off" : reason) + ")");
                }
            }
        }
        return t;
    }

    @Transactional
    public void deleteTimeOff(long id) {
        timeOffRepo.deleteById(id);
    }

    /** Convenience for the REST layer: accepts a JSON body. */
    @Transactional
    public DoctorTimeOff addTimeOff(Map<String, Object> body) {
        long doctorId = reqLong(body, "doctor_id");
        Instant from = Instant.parse(reqString(body, "from_ts", 1, 64));
        Instant to = Instant.parse(reqString(body, "to_ts", 1, 64));
        boolean fullDay = body.get("full_day") instanceof Boolean b && b;
        String reason = body.get("reason") == null ? null : String.valueOf(body.get("reason"));
        return addTimeOff(doctorId, from, to, fullDay, reason);
    }

    private static long reqLong(Map<String, Object> body, String key) {
        Object v = body.get(key);
        if (v instanceof Number n) return n.longValue();
        throw new ApiException(400, "Missing field: " + key);
    }

    private static String reqString(Map<String, Object> body, String key, int min, int max) {
        Object v = body.get(key);
        if (v == null) throw new ApiException(400, "Missing field: " + key);
        String s = String.valueOf(v).trim();
        if (s.length() < min || s.length() > max) throw new ApiException(400, key + " must be " + min + ".." + max + " chars");
        return s;
    }
}
