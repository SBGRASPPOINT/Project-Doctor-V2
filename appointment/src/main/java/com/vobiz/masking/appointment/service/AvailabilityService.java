package com.vobiz.masking.appointment.service;

import com.vobiz.masking.appointment.model.Appointment;
import com.vobiz.masking.appointment.model.Doctor;
import com.vobiz.masking.appointment.model.DoctorSchedule;
import com.vobiz.masking.appointment.model.DoctorTimeOff;
import com.vobiz.masking.appointment.repo.AppointmentRepository;
import com.vobiz.masking.appointment.repo.DoctorRepository;
import com.vobiz.masking.appointment.repo.DoctorScheduleRepository;
import com.vobiz.masking.appointment.repo.DoctorTimeOffRepository;
import com.vobiz.masking.service.SettingsService;
import com.vobiz.masking.util.ApiException;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Slot availability. Slots are DERIVED on the fly from the doctor's weekly schedule template
 * + time-off blocks + existing appointments — nothing is pre-materialised.
 *
 * All times are UTC {@link Instant}s internally; rendering uses the clinic timezone setting.
 * The slot arithmetic itself is a pure static function ({@link #generateDaySlots}) so it can
 * be unit-tested without a database, mirroring {@code RoutingService.selectEligible}.
 */
public class AvailabilityService {

    private static final Set<String> BOOKED_STATUSES = Set.of(
            Appointment.STATUS_REQUESTED, Appointment.STATUS_CONFIRMED, Appointment.STATUS_RESCHEDULED);

    private static final DateTimeFormatter SLOT_LABEL =
            DateTimeFormatter.ofPattern("EEE d MMM, h:mm a", Locale.ENGLISH);

    /** One bookable slot (UTC instants). */
    public record Slot(Instant start, Instant end) {
        public String label(ZoneId zone) {
            return SLOT_LABEL.withZone(zone).format(start);
        }
    }

    /** One recurring window for a day, with its slot length. */
    public record Template(LocalTime start, LocalTime end, int slotMinutes) {
    }

    private final DoctorRepository doctorRepo;
    private final DoctorScheduleRepository scheduleRepo;
    private final DoctorTimeOffRepository timeOffRepo;
    private final AppointmentRepository appointmentRepo;
    private final SettingsService settings;

    public AvailabilityService(DoctorRepository doctorRepo, DoctorScheduleRepository scheduleRepo,
                               DoctorTimeOffRepository timeOffRepo, AppointmentRepository appointmentRepo,
                               SettingsService settings) {
        this.doctorRepo = doctorRepo;
        this.scheduleRepo = scheduleRepo;
        this.timeOffRepo = timeOffRepo;
        this.appointmentRepo = appointmentRepo;
        this.settings = settings;
    }

    /** Free slots for a doctor across [fromDate, toDate], up to limit. */
    public List<Slot> freeSlots(long doctorId, LocalDate fromDate, LocalDate toDate, int limit) {
        Doctor doc = doctorRepo.findById(doctorId)
                .orElseThrow(() -> new ApiException(404, "Doctor not found: " + doctorId));
        ZoneId zone = settings.clinicZone();
        Instant now = Instant.now();
        List<Slot> out = new ArrayList<>();
        for (LocalDate d = fromDate; !d.isAfter(toDate) && out.size() < limit; d = d.plusDays(1)) {
            for (Slot s : generateDaySlots(d, zone, dayTemplates(doc, d),
                    busyRanges(doctorId, d, zone), timeOffRanges(doctorId, d, zone),
                    now, settings.minBookingLeadMinutes(), settings.bookingHorizonDays())) {
                out.add(s);
                if (out.size() >= limit) break;
            }
        }
        return out;
    }

    /**
     * Transactional re-check used on confirm: the slot must be inside a schedule template,
     * on the slot grid, not in the past/lead window, and not overlapping any existing
     * non-cancelled appointment or time-off block.
     */
    public boolean isFree(long doctorId, Instant startsAt, int durationMin) {
        Doctor doc = doctorRepo.findById(doctorId)
                .orElseThrow(() -> new ApiException(404, "Doctor not found: " + doctorId));
        ZoneId zone = settings.clinicZone();
        Instant now = Instant.now();
        if (!doc.isActive()) return false;
        if (startsAt.isBefore(now.plusSeconds(settings.minBookingLeadMinutes() * 60L))) return false;
        LocalDate date = startsAt.atZone(zone).toLocalDate();
        // Same horizon the slot menu enforces — otherwise the REST API could book outside it.
        if (date.isAfter(LocalDate.now(zone).plusDays(settings.bookingHorizonDays()))) return false;
        Instant end = startsAt.plusSeconds(durationMin * 60L);
        for (Instant[] b : busyRanges(doctorId, date, zone)) {
            if (startsAt.isBefore(b[1]) && end.isAfter(b[0])) return false;
        }
        for (Instant[] t : timeOffRanges(doctorId, date, zone)) {
            if (startsAt.isBefore(t[1]) && end.isAfter(t[0])) return false;
        }
        for (Template t : dayTemplates(doc, date)) {
            ZonedDateTime winStart = date.atTime(t.start()).atZone(zone);
            ZonedDateTime winEnd = date.atTime(t.end()).atZone(zone);
            long deltaSec = Duration.between(winStart.toInstant(), startsAt).toSeconds();
            long gridSec = t.slotMinutes() * 60L;
            if (deltaSec >= 0 && deltaSec % gridSec == 0
                    && !startsAt.isBefore(winStart.toInstant()) && !end.isAfter(winEnd.toInstant())) {
                return true;
            }
        }
        return false;
    }

    // ---------- DB-backed helpers ----------

    private int effectiveSlotMinutes(Doctor doc) {
        return doc.getSlotMinutes() > 0 ? doc.getSlotMinutes() : settings.slotGranularityMin();
    }

    private List<Template> dayTemplates(Doctor doc, LocalDate date) {
        int dow = date.getDayOfWeek().getValue(); // ISO: 1 = Monday … 7 = Sunday (matches the entity)
        List<Template> out = new ArrayList<>();
        for (DoctorSchedule s : scheduleRepo.findByDoctorIdAndDayOfWeekAndActiveTrue(doc.getId(), dow)) {
            out.add(new Template(s.getStartTime(), s.getEndTime(),
                    s.getSlotMinutes() > 0 ? s.getSlotMinutes() : effectiveSlotMinutes(doc)));
        }
        return out;
    }

    private List<Instant[]> busyRanges(long doctorId, LocalDate date, ZoneId zone) {
        Instant dayStart = date.atStartOfDay(zone).toInstant();
        Instant dayEnd = date.plusDays(1).atStartOfDay(zone).toInstant();
        List<Instant[]> out = new ArrayList<>();
        for (Appointment a : appointmentRepo.findByDoctorIdAndStartsAtBetweenOrderByStartsAtAsc(
                doctorId, dayStart.minusSeconds(86400), dayEnd.plusSeconds(86400))) {
            if (BOOKED_STATUSES.contains(a.getStatus()) && a.getEndsAt() != null) {
                out.add(new Instant[]{a.getStartsAt(), a.getEndsAt()});
            }
        }
        return out;
    }

    private List<Instant[]> timeOffRanges(long doctorId, LocalDate date, ZoneId zone) {
        Instant dayStart = date.atStartOfDay(zone).toInstant();
        Instant dayEnd = date.plusDays(1).atStartOfDay(zone).toInstant();
        List<Instant[]> out = new ArrayList<>();
        for (DoctorTimeOff t : timeOffRepo.findOverlapping(doctorId, dayStart.minusSeconds(86400), dayEnd.plusSeconds(86400))) {
            out.add(new Instant[]{t.getFromTs(), t.getToTs()});
        }
        return out;
    }

    // ---------- pure slot arithmetic (unit-tested) ----------

    /**
     * Generates the free slots of one clinic day. Pure and deterministic — no I/O.
     *
     * @param date           the clinic-local date
     * @param zone           clinic timezone
     * @param templates      the day's recurring windows (+ slot length each)
     * @param busyRanges     existing appointments as [start, end) instant pairs
     * @param timeOffRanges  time-off blocks as [from, to) instant pairs
     * @param nowUtc         current time
     * @param minLeadMinutes minimum booking lead time
     * @param horizonDays    how far ahead booking is allowed
     */
    public static List<Slot> generateDaySlots(LocalDate date, ZoneId zone, List<Template> templates,
                                              List<Instant[]> busyRanges, List<Instant[]> timeOffRanges,
                                              Instant nowUtc, int minLeadMinutes, int horizonDays) {
        List<Slot> out = new ArrayList<>();
        LocalDate today = nowUtc.atZone(zone).toLocalDate();
        if (date.isBefore(today) || date.isAfter(today.plusDays(horizonDays))) return out;
        Instant earliest = nowUtc.plusSeconds(minLeadMinutes * 60L);
        for (Template t : templates) {
            if (t.slotMinutes() <= 0 || !t.start().isBefore(t.end())) continue;
            ZonedDateTime cur = date.atTime(t.start()).atZone(zone);
            ZonedDateTime dayEnd = date.atTime(t.end()).atZone(zone);
            while (!cur.plusMinutes(t.slotMinutes()).isAfter(dayEnd)) {
                Instant s = cur.toInstant();
                Instant e = cur.plusMinutes(t.slotMinutes()).toInstant();
                if (!s.isBefore(earliest) && !overlaps(s, e, busyRanges) && !overlaps(s, e, timeOffRanges)) {
                    out.add(new Slot(s, e));
                }
                cur = cur.plusMinutes(t.slotMinutes());
            }
        }
        out.sort(Comparator.comparing(Slot::start));
        List<Slot> dedup = new ArrayList<>();
        for (Slot s : out) {
            if (dedup.isEmpty() || !dedup.get(dedup.size() - 1).start().equals(s.start())) dedup.add(s);
        }
        return dedup;
    }

    static boolean overlaps(Instant s, Instant e, List<Instant[]> ranges) {
        for (Instant[] r : ranges) {
            if (s.isBefore(r[1]) && e.isAfter(r[0])) return true;
        }
        return false;
    }
}
