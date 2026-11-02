package com.vobiz.masking.appointment.service;

import com.vobiz.masking.appointment.model.Appointment;
import com.vobiz.masking.appointment.model.AppointmentEvent;
import com.vobiz.masking.appointment.model.Doctor;
import com.vobiz.masking.appointment.model.Patient;
import com.vobiz.masking.appointment.repo.AppointmentEventRepository;
import com.vobiz.masking.appointment.repo.AppointmentRepository;
import com.vobiz.masking.appointment.repo.DoctorRepository;
import com.vobiz.masking.appointment.repo.PatientRepository;
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

    /**
     * The only legal status transitions. This is the single source of truth for the lifecycle:
     * cancelled/completed/no_show are terminal, so a cancelled appointment can never be
     * "confirmed" back to life with a NULL slot_key (which used to break the UNIQUE slot_key
     * invariant that makes double booking impossible).
     */
    private static final Map<String, Set<String>> ALLOWED_TRANSITIONS = Map.of(
            Appointment.STATUS_REQUESTED, Set.of(
                    Appointment.STATUS_CONFIRMED, Appointment.STATUS_RESCHEDULED,
                    Appointment.STATUS_CANCELLED, Appointment.STATUS_NO_SHOW),
            Appointment.STATUS_CONFIRMED, Set.of(
                    Appointment.STATUS_RESCHEDULED, Appointment.STATUS_COMPLETED,
                    Appointment.STATUS_CANCELLED, Appointment.STATUS_NO_SHOW),
            Appointment.STATUS_RESCHEDULED, Set.of(
                    Appointment.STATUS_CONFIRMED, Appointment.STATUS_COMPLETED,
                    Appointment.STATUS_CANCELLED, Appointment.STATUS_NO_SHOW),
            Appointment.STATUS_CANCELLED, Set.of(),
            Appointment.STATUS_COMPLETED, Set.of(),
            Appointment.STATUS_NO_SHOW, Set.of());

    private final AppointmentRepository appointmentRepo;
    private final AppointmentEventRepository eventRepo;
    private final PatientRepository patientRepo;
    private final DoctorRepository doctorRepo;
    private final AvailabilityService availability;
    private final ReminderService reminders;
    private final SettingsService settings;

    public AppointmentService(AppointmentRepository appointmentRepo, AppointmentEventRepository eventRepo,
                              PatientRepository patientRepo, DoctorRepository doctorRepo,
                              AvailabilityService availability,
                              ReminderService reminders, SettingsService settings) {
        this.appointmentRepo = appointmentRepo;
        this.eventRepo = eventRepo;
        this.patientRepo = patientRepo;
        this.doctorRepo = doctorRepo;
        this.availability = availability;
        this.reminders = reminders;
        this.settings = settings;
    }

    /** Pure lifecycle rule — unit-tested without a database. */
    public static boolean canTransition(String from, String to) {
        if (from == null || to == null) return false;
        if (from.equals(to)) return true;                    // no-op
        return ALLOWED_TRANSITIONS.getOrDefault(from, Set.of()).contains(to);
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
        if (!d.isActive())
            throw new ApiException(409, "Doctor " + d.getName() + " is not accepting appointments");
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
        if (!canTransition(a.getStatus(), Appointment.STATUS_CANCELLED))
            throw new ApiException(409, "Appointment " + id + " is " + a.getStatus() + " and cannot be cancelled");
        a.setCancellationReason(reason);
        // transition() owns the cancelled bookkeeping: cancelled_at, slot_key=NULL (frees the
        // slot), reminder suppression and the staff change-notification.
        return transition(a, Appointment.STATUS_CANCELLED, "cancelled", actor, false,
                Map.of("reason", reason == null ? "" : reason));
    }

    @Transactional
    public Appointment confirm(long id, String actor) {
        return transition(get(id), Appointment.STATUS_CONFIRMED, "confirmed", actor, true, Map.of());
    }

    @Transactional
    public Appointment complete(long id) {
        return transition(get(id), Appointment.STATUS_COMPLETED, "completed", "system", false, Map.of());
    }

    @Transactional
    public Appointment markNoShow(long id) {
        return transition(get(id), Appointment.STATUS_NO_SHOW, "no_show", "system", false, Map.of());
    }

    /**
     * The one and only status writer. Every path (confirm/complete/no-show/cancel/PATCH status)
     * goes through here so side effects can never be skipped: a cancelled slot is always
     * released, its reminders always suppressed, and terminal statuses are never left open.
     */
    private Appointment transition(Appointment a, String status, String eventType, String actor,
                                   boolean setConfirmedAt, Map<String, Object> payload) {
        String from = a.getStatus();
        if (status.equals(from)) return a;
        if (!canTransition(from, status))
            throw new ApiException(409, "Cannot change status from " + from + " to " + status);

        Instant now = Instant.now();
        a.setStatus(status);
        if (Appointment.STATUS_COMPLETED.equals(status)) {
            a.setCompletedAt(now);
            a.setNeedsRebooking(false);
        } else if (Appointment.STATUS_CANCELLED.equals(status)) {
            a.setCancelledAt(now);
            a.setSlotKey(null);              // the slot becomes bookable again
            a.setNeedsRebooking(false);
        } else if (Appointment.STATUS_NO_SHOW.equals(status)) {
            a.setNeedsRebooking(false);
        } else if (setConfirmedAt && OPEN_STATUSES.contains(status)) {
            a.setConfirmedAt(now);
        }
        a = appointmentRepo.save(a);
        event(a.getId(), eventType, from, status, actor, null, payload == null ? Map.of() : payload);

        if (Appointment.STATUS_CANCELLED.equals(status)) {
            reminders.suppressPending(a.getId());
            if (actor != null && !actor.equals("patient_ivr") && settings.changeNotificationEnabled()) {
                String reason = a.getCancellationReason();
                reminders.enqueueChange(a, "Cancelled" + (reason == null || reason.isBlank() ? "" : ": " + reason));
            }
        }
        return a;
    }

    /** Partial update from the ops console. */
    @Transactional
    public Appointment update(long id, Map<String, Object> body) {
        Appointment a = get(id);
        String target = null;
        if (body.containsKey("status")) {
            target = String.valueOf(body.get("status"));
            if (!Set.of(Appointment.STATUS_REQUESTED, Appointment.STATUS_CONFIRMED, Appointment.STATUS_RESCHEDULED,
                    Appointment.STATUS_CANCELLED, Appointment.STATUS_COMPLETED, Appointment.STATUS_NO_SHOW).contains(target))
                throw new ApiException(400, "Unknown status: " + target);
            if (!canTransition(a.getStatus(), target))
                throw new ApiException(409, "Cannot change status from " + a.getStatus() + " to " + target);
        }
        if (body.containsKey("notes")) a.setNotes(str(body.get("notes"), 2000));
        if (body.containsKey("reason")) a.setReason(str(body.get("reason"), 2000));
        a = appointmentRepo.save(a);          // notes/reason are kept even when a status change follows
        if (target != null) a = transition(a, target, "updated", "agent", false, Map.of());
        return a;
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
