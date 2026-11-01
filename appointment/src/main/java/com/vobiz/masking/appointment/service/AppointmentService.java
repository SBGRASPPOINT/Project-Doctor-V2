package com.vobiz.masking.appointment.service;

import com.vobiz.masking.appointment.model.Appointment;
import com.vobiz.masking.appointment.model.AppointmentEvent;
import com.vobiz.masking.appointment.model.Doctor;
import com.vobiz.masking.appointment.model.Patient;
import com.vobiz.masking.appointment.model.WaitlistEntry;
import com.vobiz.masking.appointment.repo.AppointmentEventRepository;
import com.vobiz.masking.appointment.repo.AppointmentRepository;
import com.vobiz.masking.appointment.repo.DoctorRepository;
import com.vobiz.masking.appointment.repo.PatientRepository;
import com.vobiz.masking.appointment.repo.WaitlistEntryRepository;
import com.vobiz.masking.service.SettingsService;
import com.vobiz.masking.util.ApiException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Appointment lifecycle: create / reschedule / cancel / confirm / complete / no-show.
 *
 * The double-booking guard is the UNIQUE {@code slot_key} ("{doctorId}|{startsAt}"): the
 * availability check narrows it down, but the constraint is the real lock — a unique
 * violation is caught and returned as a 409 with an IVR-safe message ("that slot has just gone").
 * On cancel the slot key is set to NULL, which frees the slot while the row survives for history.
 */
public class AppointmentService {

    private static final SecureRandom RANDOM = new SecureRandom();
    /** Unambiguous alphabet — no 0/O/1/I — so a spoken reference code is never misheard. */
    private static final String REF_ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789";

    private static final Set<String> OPEN_STATUSES = Set.of(
            Appointment.STATUS_REQUESTED, Appointment.STATUS_CONFIRMED, Appointment.STATUS_RESCHEDULED);
    private static final Set<String> CLOSED_STATUSES = Set.of(
            Appointment.STATUS_CANCELLED, Appointment.STATUS_COMPLETED, Appointment.STATUS_NO_SHOW);

    private final AppointmentRepository appointmentRepo;
    private final AppointmentEventRepository eventRepo;
    private final PatientRepository patientRepo;
    private final DoctorRepository doctorRepo;
    private final WaitlistEntryRepository waitlistRepo;
    private final AvailabilityService availability;
    private final ReminderService reminders;
    private final SettingsService settings;

    public AppointmentService(AppointmentRepository appointmentRepo, AppointmentEventRepository eventRepo,
                              PatientRepository patientRepo, DoctorRepository doctorRepo,
                              WaitlistEntryRepository waitlistRepo, AvailabilityService availability,
                              ReminderService reminders, SettingsService settings) {
        this.appointmentRepo = appointmentRepo;
        this.eventRepo = eventRepo;
        this.patientRepo = patientRepo;
        this.doctorRepo = doctorRepo;
        this.waitlistRepo = waitlistRepo;
        this.availability = availability;
        this.reminders = reminders;
        this.settings = settings;
    }

    public Appointment get(long id) {
        return appointmentRepo.findById(id).orElseThrow(() -> new ApiException(404, "Appointment not found: " + id));
    }

    public Appointment getByReference(String ref) {
        return appointmentRepo.findByReferenceCode(ref)
                .orElseThrow(() -> new ApiException(404, "Appointment not found: " + ref));
    }

    /** Upcoming (open) appointments of a patient — the IVR reschedule/cancel menus. */
    public List<Appointment> upcomingForPatient(long patientId, int limit) {
        return appointmentRepo
                .findByPatientIdAndStartsAtAfterAndStatusInOrderByStartsAtAsc(patientId, Instant.now(), List.copyOf(OPEN_STATUSES))
                .stream().limit(limit).toList();
    }

    @Transactional
    public Appointment create(Long patientId, Long doctorId, Instant startsAt, Integer durationMin,
                              String reason, String source, Long callId) {
        Patient p = patientRepo.findById(patientId)
                .orElseThrow(() -> new ApiException(404, "Patient not found: " + patientId));
        Doctor d = doctorRepo.findById(doctorId)
                .orElseThrow(() -> new ApiException(404, "Doctor not found: " + doctorId));
        int dur = durationMin != null && durationMin > 0 ? durationMin
                : (d.getSlotMinutes() > 0 ? d.getSlotMinutes() : settings.slotGranularityMin());
        String key = slotKey(doctorId, startsAt);
        if (appointmentRepo.findBySlotKey(key).isPresent() || !availability.isFree(doctorId, startsAt, dur)) {
            throw new ApiException(409, "That slot has just gone — please pick another");
        }
        Appointment a = new Appointment();
        a.setPatientId(p.getId());
        a.setPatientName(p.getName());
        a.setPatientPhoneE164(p.getPhoneE164());
        a.setDoctorId(d.getId());
        a.setDepartmentId(d.getDepartmentId());
        a.setStartsAt(startsAt);
        a.setEndsAt(startsAt.plusSeconds(dur * 60L));
        a.setDurationMin(dur);
        a.setStatus(Appointment.STATUS_CONFIRMED);
        a.setConfirmedAt(Instant.now());
        a.setSource(source == null ? "ivr_call" : source);
        a.setReason(reason);
        a.setCreatedViaCallId(callId);
        a.setSlotKey(key);
        a = saveWithReferenceRetry(a, key);
        event(a.getId(), "created", null, a.getStatus(), "system", null,
                Map.of("source", a.getSource(), "starts_at", startsAt.toString(), "doctor_id", doctorId));
        reminders.scheduleFor(a);
        return a;
    }

    @Transactional
    public Appointment reschedule(long id, Instant newStartsAt, String actor) {
        Appointment a = get(id);
        if (CLOSED_STATUSES.contains(a.getStatus()))
            throw new ApiException(409, "Appointment " + id + " is " + a.getStatus() + " and cannot be rescheduled");
        int dur = a.getDurationMin() != null ? a.getDurationMin() : settings.slotGranularityMin();
        String newKey = slotKey(a.getDoctorId(), newStartsAt);
        if (appointmentRepo.findBySlotKey(newKey).isPresent() || !availability.isFree(a.getDoctorId(), newStartsAt, dur)) {
            throw new ApiException(409, "That slot has just gone — please pick another");
        }
        String fromStatus = a.getStatus();
        a.setSlotKey(null);                       // release the old slot first (unique index)
        appointmentRepo.saveAndFlush(a);
        a.setStartsAt(newStartsAt);
        a.setEndsAt(newStartsAt.plusSeconds(dur * 60L));
        a.setStatus(Appointment.STATUS_RESCHEDULED);
        a.setConfirmedAt(Instant.now());
        a.setSlotKey(newKey);
        a = saveWithReferenceRetry(a, newKey);
        event(a.getId(), "rescheduled", fromStatus, a.getStatus(), actor, null,
                Map.of("new_starts_at", newStartsAt.toString()));
        reminders.replaceFor(a);
        if (actor != null && !actor.equals("patient_ivr") && settings.changeNotificationEnabled()) {
            reminders.enqueueChange(a, "Rescheduled by staff to " + newStartsAt);
        }
        return a;
    }

    @Transactional
    public Appointment cancel(long id, String reason, String actor) {
        Appointment a = get(id);
        if (Appointment.STATUS_CANCELLED.equals(a.getStatus())) return a;
        String from = a.getStatus();
        a.setStatus(Appointment.STATUS_CANCELLED);
        a.setCancellationReason(reason);
        a.setCancelledAt(Instant.now());
        a.setSlotKey(null);                       // the slot becomes bookable again
        a = appointmentRepo.save(a);
        event(a.getId(), "cancelled", from, Appointment.STATUS_CANCELLED, actor, null,
                Map.of("reason", reason == null ? "" : reason));
        reminders.suppressPending(a.getId());
        if (actor != null && !actor.equals("patient_ivr") && settings.changeNotificationEnabled()) {
            reminders.enqueueChange(a, "Cancelled" + (reason == null || reason.isBlank() ? "" : ": " + reason));
        }
        // offer the freed slot to waiting patients of this doctor
        for (WaitlistEntry w : waitlistRepo.findByStatusAndDoctorIdOrderByCreatedAtAsc("waiting", a.getDoctorId())) {
            w.setStatus("offered");
            waitlistRepo.save(w);
        }
        return a;
    }

    @Transactional
    public Appointment confirm(long id, String actor) {
        return transition(id, Appointment.STATUS_CONFIRMED, "confirmed", actor, true);
    }

    @Transactional
    public Appointment complete(long id) {
        return transition(id, Appointment.STATUS_COMPLETED, "completed", "system", false);
    }

    @Transactional
    public Appointment markNoShow(long id) {
        return transition(id, Appointment.STATUS_NO_SHOW, "no_show", "system", false);
    }

    private Appointment transition(long id, String status, String eventType, String actor, boolean setConfirmedAt) {
        Appointment a = get(id);
        String from = a.getStatus();
        a.setStatus(status);
        if (setConfirmedAt) a.setConfirmedAt(Instant.now());
        if (Appointment.STATUS_COMPLETED.equals(status)) a.setCompletedAt(Instant.now());
        if (Appointment.STATUS_CANCELLED.equals(status)) a.setCancelledAt(Instant.now());
        a = appointmentRepo.save(a);
        event(a.getId(), eventType, from, status, actor, null, Map.of());
        return a;
    }

    /** Partial update from the ops console. */
    @Transactional
    public Appointment update(long id, Map<String, Object> body) {
        Appointment a = get(id);
        if (body.containsKey("notes")) a.setNotes(str(body.get("notes"), 2000));
        if (body.containsKey("reason")) a.setReason(str(body.get("reason"), 2000));
        if (body.containsKey("status")) {
            String s = String.valueOf(body.get("status"));
            if (!Set.of(Appointment.STATUS_REQUESTED, Appointment.STATUS_CONFIRMED, Appointment.STATUS_RESCHEDULED,
                    Appointment.STATUS_CANCELLED, Appointment.STATUS_COMPLETED, Appointment.STATUS_NO_SHOW).contains(s))
                throw new ApiException(400, "Unknown status: " + s);
            transition(a.getId(), s, "updated", "agent", false);
            return get(id);
        }
        return appointmentRepo.save(a);
    }

    // ---------- helpers ----------

    private Appointment saveWithReferenceRetry(Appointment a, String key) {
        for (int attempt = 0; attempt < 5; attempt++) {
            a.setReferenceCode(newReferenceCode());
            try {
                return appointmentRepo.save(a);
            } catch (DataIntegrityViolationException e) {
                if (appointmentRepo.findBySlotKey(key).isPresent())
                    throw new ApiException(409, "That slot has just gone — please pick another");
                if (attempt == 4) throw new ApiException(500, "Could not allocate a reference code");
            }
        }
        throw new ApiException(500, "Could not save appointment");
    }

    private void event(long appointmentId, String type, String from, String to, String actor,
                       String callUuid, Map<String, Object> payload) {
        AppointmentEvent ev = new AppointmentEvent();
        ev.setAppointmentId(appointmentId);
        ev.setEventType(type);
        ev.setFromStatus(from);
        ev.setToStatus(to);
        ev.setActor(actor);
        ev.setCallUuid(callUuid);
        ev.setPayload(payload == null ? new LinkedHashMap<>() : payload);
        eventRepo.save(ev);
    }

    private static String str(Object v, int max) {
        if (v == null) return null;
        String s = String.valueOf(v);
        if (s.length() > max) throw new ApiException(400, "too long (max " + max + ")");
        return s;
    }

    // ---------- pure helpers (unit-tested) ----------

    public static String newReferenceCode() {
        StringBuilder sb = new StringBuilder("APT-");
        for (int i = 0; i < 4; i++) sb.append(REF_ALPHABET.charAt(RANDOM.nextInt(REF_ALPHABET.length())));
        return sb.toString();
    }

    /** Unambiguous alphabet check — no 0/O/1/I. */
    public static boolean isUnambiguousAlphabet(String alphabet) {
        return alphabet.chars().noneMatch(c -> "0O1I".indexOf(c) >= 0);
    }

    public static String slotKey(long doctorId, Instant startsAt) {
        return doctorId + "|" + startsAt;
    }
}
