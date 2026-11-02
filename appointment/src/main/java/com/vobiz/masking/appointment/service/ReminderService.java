package com.vobiz.masking.appointment.service;

import com.vobiz.masking.appointment.model.Appointment;
import com.vobiz.masking.appointment.model.AppointmentEvent;
import com.vobiz.masking.appointment.model.Patient;
import com.vobiz.masking.appointment.model.Reminder;
import com.vobiz.masking.appointment.repo.AppointmentEventRepository;
import com.vobiz.masking.appointment.repo.AppointmentRepository;
import com.vobiz.masking.appointment.repo.PatientRepository;
import com.vobiz.masking.appointment.repo.ReminderRepository;
import com.vobiz.masking.config.AppProperties;
import com.vobiz.masking.repo.CallDao;
import com.vobiz.masking.security.WebhookSigner;
import com.vobiz.masking.service.SettingsService;
import com.vobiz.masking.util.ApiException;
import com.vobiz.masking.vobiz.VobizClient;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.data.domain.PageRequest;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Reminder engine over outbound voice calls:
 *  - scheduleFor: T-24h + T-2h reminders on booking (unique per (appointment, kind), so a
 *    reschedule REPLACES the pending set instead of duplicating it);
 *  - enqueueChange: immediate 'change' notification when staff reschedule/cancel or a
 *    time-off block overlaps;
 *  - runDue: the scheduler scan — places the outbound call via Vobiz (callerId = M1, so the
 *    patient only ever sees the clinic number), logs a calls row, marks the reminder 'placed';
 *  - onOutboundHangup: retry policy for unanswered reminder calls;
 *  - quiet hours + patient DND are respected everywhere.
 */
public class ReminderService {

    private static final Logger log = LogManager.getLogger(ReminderService.class);

    private static final Set<String> CLOSED_STATUSES = Set.of(
            Appointment.STATUS_CANCELLED, Appointment.STATUS_COMPLETED, Appointment.STATUS_NO_SHOW);

    private final ReminderRepository reminderRepo;
    private final AppointmentRepository appointmentRepo;
    private final AppointmentEventRepository eventRepo;
    private final PatientRepository patientRepo;
    private final CallDao callDao;
    private final VobizClient vobiz;
    private final SettingsService settings;
    private final AppProperties app;
    private final WebhookSigner signer;
    private final SmsService sms;

    public ReminderService(ReminderRepository reminderRepo, AppointmentRepository appointmentRepo,
                           AppointmentEventRepository eventRepo, PatientRepository patientRepo,
                           CallDao callDao, VobizClient vobiz, SettingsService settings, AppProperties app,
                           WebhookSigner signer, SmsService sms) {
        this.reminderRepo = reminderRepo;
        this.appointmentRepo = appointmentRepo;
        this.eventRepo = eventRepo;
        this.patientRepo = patientRepo;
        this.callDao = callDao;
        this.vobiz = vobiz;
        this.settings = settings;
        this.app = app;
        this.signer = signer;
        this.sms = sms;
    }

    // ---------- scheduling ----------

    @Transactional
    public void scheduleFor(Appointment a) {
        if (settings.reminderT24hEnabled())
            upsert(a, Reminder.KIND_T24H, a.getStartsAt().minusSeconds(settings.reminderT24hLeadMinutes() * 60L));
        if (settings.reminderT2hEnabled())
            upsert(a, Reminder.KIND_T2H, a.getStartsAt().minusSeconds(settings.reminderT2hLeadMinutes() * 60L));
    }

    /** On reschedule: suppress the pending set, then schedule fresh ones. */
    @Transactional
    public void replaceFor(Appointment a) {
        suppressPending(a.getId());
        scheduleFor(a);
    }

    @Transactional
    public void suppressPending(long appointmentId) {
        for (Reminder r : reminderRepo.findByAppointmentIdAndStatusIn(appointmentId, List.of("pending", "placed"))) {
            r.setStatus("suppressed");
            reminderRepo.save(r);
        }
    }

    /** Immediate change notification (staff reschedule/cancel, doctor time-off). */
    @Transactional
    public Reminder enqueueChange(Appointment a, String reason) {
        Reminder r = reminderRepo.findByAppointmentIdAndKind(a.getId(), Reminder.KIND_CHANGE).orElseGet(Reminder::new);
        r.setAppointmentId(a.getId());
        r.setKind(Reminder.KIND_CHANGE);
        r.setChannel("voice");
        r.setScheduledAt(rollIntoAllowedWindow(Instant.now(), settings.clinicZone(),
                settings.reminderQuietStart(), settings.reminderQuietEnd()));
        r.setStatus("pending");
        r.setErrorText(reason);
        r = reminderRepo.save(r);
        event(a, "reminder_queued", null, Map.of("kind", Reminder.KIND_CHANGE, "reason", reason == null ? "" : reason));
        return r;
    }

    private void upsert(Appointment a, String kind, Instant at) {
        Instant rolled = rollIntoAllowedWindow(at, settings.clinicZone(),
                settings.reminderQuietStart(), settings.reminderQuietEnd());
        Reminder r = reminderRepo.findByAppointmentIdAndKind(a.getId(), kind).orElseGet(Reminder::new);
        r.setAppointmentId(a.getId());
        r.setKind(kind);
        r.setChannel("voice");
        r.setScheduledAt(rolled);
        if (!"pending".equals(r.getStatus())) r.setStatus("pending");
        reminderRepo.save(r);
    }

    // ---------- the scan ----------

    /** Places outbound calls for due pending reminders. Called by the scheduler every scan interval. */
    @Transactional
    public void runDue() {
        List<Reminder> due = reminderRepo.findByStatusAndScheduledAtLessThanEqualOrderByScheduledAtAsc(
                "pending", Instant.now(), PageRequest.of(0, 50));
        for (Reminder r : due) {
            try {
                place(r);
            } catch (Exception e) {
                log.error("Reminder {} placement failed", r.getId(), e);
                r.setStatus("failed");
                r.setErrorText(String.valueOf(e.getMessage()));
                reminderRepo.save(r);
            }
        }
    }

    private void place(Reminder r) {
        Appointment a = appointmentRepo.findById(r.getAppointmentId()).orElse(null);
        if (a == null || CLOSED_STATUSES.contains(a.getStatus())) {
            r.setStatus("suppressed");
            reminderRepo.save(r);
            return;
        }
        Patient p = patientRepo.findById(a.getPatientId()).orElse(null);
        if (p == null || p.isDnd() || p.getPhoneE164() == null || p.getPhoneE164().isBlank()) {
            r.setStatus("suppressed");   // DND or no number: never call
            reminderRepo.save(r);
            return;
        }
        Instant now = Instant.now();
        if (outsideAllowedWindow(now, settings.clinicZone(), settings.reminderQuietStart(), settings.reminderQuietEnd())) {
            // quiet hours: roll to the next allowed window, keep pending
            r.setScheduledAt(nextWindowStart(now, settings.clinicZone(), settings.reminderQuietStart(), settings.reminderQuietEnd()));
            reminderRepo.save(r);
            return;
        }
        String m1 = settings.m1(app.getDefaultM1Number());
        String base = trimSlash(app.getPublicBaseUrl());
        String answerUrl = signer.sign(base + "/voice/reminder-answer?reminder_id=" + r.getId()
                + "&appointment_id=" + a.getId());
        String uuid = vobiz.placeCall(m1, p.getPhoneE164(), answerUrl, signer.sign(base + "/voice/hangup"));
        callDao.insertOutboundReminder(uuid, p.getPhoneE164(), r.getId());
        r.setStatus("placed");
        r.setAttempts(r.getAttempts() + 1);
        r.setLastAttemptAt(now);
        r.setOutboundCallUuid(uuid);
        reminderRepo.save(r);
        event(a, "reminder_sent", null, Map.of("kind", r.getKind(), "call_uuid", uuid));
        log.info("Reminder #{} ({}) call placed to patient {} as {}", r.getId(), r.getKind(),
                mask(p.getPhoneE164()), uuid);
    }

    /** A leg that lasted at least this long was listened to — never auto-redial it. */
    public static final int ANSWERED_MIN_SEC = 5;

    /** Called from /voice/hangup for outbound legs that carry a reminder. */
    @Transactional
    public void onOutboundHangup(String callUuid) {
        onOutboundHangup(callUuid, null);
    }

    /**
     * Retry policy. Previously every hangup of an unattended reminder re-queued it, because a
     * delivered reminder stayed in status 'placed' unless the patient pressed a key — so a
     * patient who listened and hung up was dialled again and again.
     *
     * Now: a leg long enough to have been heard (Vobiz sends CallDuration on the hangup
     * webhook) is finished as 'answered'; only short/unanswered legs are retried, and only
     * while {@code attempt < reminder_retry_count} (so the setting is the number of *extra*
     * attempts, not the total).
     */
    @Transactional
    public void onOutboundHangup(String callUuid, Integer durationSec) {
        if (callUuid == null) return;
        reminderRepo.findByOutboundCallUuid(callUuid).ifPresent(r -> {
            if (!"placed".equals(r.getStatus())) return;
            Appointment a = appointmentRepo.findById(r.getAppointmentId()).orElse(null);
            if (durationSec != null && durationSec >= ANSWERED_MIN_SEC) {
                r.setStatus("answered");
                r.setErrorText(durationSec + "s call, no keypad response");
                reminderRepo.save(r);
                if (a != null) event(a, "reminder_answered", null,
                        Map.of("kind", r.getKind(), "duration_sec", durationSec));
                return;
            }
            boolean stillWorthRetrying = a != null
                    && !CLOSED_STATUSES.contains(a.getStatus())
                    && a.getStartsAt().isAfter(Instant.now())
                    && r.getAttempts() < settings.reminderRetryCount();
            if (stillWorthRetrying) {
                r.setStatus("pending");
                r.setScheduledAt(rollIntoAllowedWindow(
                        Instant.now().plusSeconds(settings.reminderRetryGapMinutes() * 60L),
                        settings.clinicZone(), settings.reminderQuietStart(), settings.reminderQuietEnd()));
            } else {
                r.setStatus("no_answer");
            }
            reminderRepo.save(r);
            if ("no_answer".equals(r.getStatus())) sendSmsFallback(a, r);
        });
    }

    /**
     * SMS fallback: {@code sms_fallback_enabled} used to be a setting nobody read and an
     * injectable {@link SmsService} nobody called. When the voice reminder is not answered
     * (retries exhausted) the patient now gets one plain-text fallback, logged and attributed
     * on the appointment timeline. The default bean still only logs — wire a provider to send.
     */
    private void sendSmsFallback(Appointment a, Reminder r) {
        if (a == null || !settings.smsFallbackEnabled()) return;
        Patient p = patientRepo.findById(a.getPatientId()).orElse(null);
        if (p == null || p.getPhoneE164() == null || p.getPhoneE164().isBlank()) return;
        if ("sms".equals(r.getChannel())) return;                 // never double-send
        String when = java.time.format.DateTimeFormatter.ofPattern("EEE d MMM, h:mm a")
                .withZone(settings.clinicZone()).format(a.getStartsAt());
        String text = "Reminder from " + settings.clinicName() + ": appointment on " + when
                + (a.getReferenceCode() == null ? "" : " (ref " + a.getReferenceCode() + ")") + ".";
        try {
            sms.send(p.getPhoneE164(), text);
            r.setChannel("sms");                                   // recorded so a retry scan skips it
            reminderRepo.save(r);
            event(a, "sms_fallback_sent", null, Map.of("kind", r.getKind()));
        } catch (Exception e) {
            log.warn("SMS fallback for reminder {} failed: {}", r.getId(), e.getMessage());
        }
    }

    /** Keypad response on a reminder call (1 confirm / 2 reschedule / 3 cancel). */
    @Transactional
    public Reminder markAnswered(long reminderId, String digit) {
        Reminder r = reminderRepo.findById(reminderId)
                .orElseThrow(() -> new ApiException(404, "Reminder not found: " + reminderId));
        r.setResponseDigit(digit);
        String type;
        switch (digit) {
            case "1" -> { r.setStatus("answered_confirmed"); type = "patient_confirmed"; }
            // "2" only connects the caller to reception — nothing is rescheduled yet, so the
            // status must not claim otherwise (it used to say answered_rescheduled).
            case "2" -> { r.setStatus("answered_reschedule_requested"); type = "patient_reschedule_requested"; }
            case "3" -> { r.setStatus("answered_cancelled"); type = "patient_cancelled"; }
            default -> { r.setStatus("answered"); type = "reminder_answered"; }
        }
        Reminder saved = reminderRepo.save(r);
        Appointment a = appointmentRepo.findById(r.getAppointmentId()).orElse(null);
        if (a != null) event(a, type, null, Map.of("digit", digit, "reminder_id", reminderId));
        return saved;
    }

    public Reminder get(long id) {
        return reminderRepo.findById(id).orElseThrow(() -> new ApiException(404, "Reminder not found: " + id));
    }

    /** Force-place one reminder now (ops console "Call now"). */
    @Transactional
    public void placeNow(long reminderId) {
        Reminder r = reminderRepo.findById(reminderId)
                .orElseThrow(() -> new ApiException(404, "Reminder not found: " + reminderId));
        r.setStatus("pending");
        r.setScheduledAt(Instant.now());
        reminderRepo.save(r);
        place(r);
    }

    @Transactional
    public Reminder suppress(long reminderId) {
        Reminder r = reminderRepo.findById(reminderId)
                .orElseThrow(() -> new ApiException(404, "Reminder not found: " + reminderId));
        r.setStatus("suppressed");
        return reminderRepo.save(r);
    }

    // ---------- pure quiet-hours helpers (unit-tested) ----------

    public static boolean outsideAllowedWindow(Instant t, ZoneId zone, LocalTime start, LocalTime end) {
        LocalTime lt = t.atZone(zone).toLocalTime();
        return lt.isBefore(start) || !lt.isBefore(end);
    }

    public static Instant nextWindowStart(Instant t, ZoneId zone, LocalTime start, LocalTime end) {
        ZonedDateTime z = t.atZone(zone);
        LocalDate day = z.toLocalDate();
        if (z.toLocalTime().isBefore(start)) return day.atTime(start).atZone(zone).toInstant();
        return day.plusDays(1).atTime(start).atZone(zone).toInstant();
    }

    public static Instant rollIntoAllowedWindow(Instant t, ZoneId zone, LocalTime start, LocalTime end) {
        if (outsideAllowedWindow(t, zone, start, end)) return nextWindowStart(t, zone, start, end);
        return t;
    }

    /** Which reminder kinds+times an appointment gets, given the policy. Pure, unit-testable. */
    public static Map<String, Instant> scheduleFor(Instant startsAt, Instant now,
                                                   boolean t24hEnabled, int t24hLeadMin,
                                                   boolean t2hEnabled, int t2hLeadMin,
                                                   ZoneId zone, LocalTime quietStart, LocalTime quietEnd) {
        Map<String, Instant> out = new LinkedHashMap<>();
        if (t24hEnabled) {
            Instant at = startsAt.minusSeconds(t24hLeadMin * 60L);
            if (at.isAfter(now)) out.put(Reminder.KIND_T24H, rollIntoAllowedWindow(at, zone, quietStart, quietEnd));
        }
        if (t2hEnabled) {
            Instant at = startsAt.minusSeconds(t2hLeadMin * 60L);
            if (at.isAfter(now)) out.put(Reminder.KIND_T2H, rollIntoAllowedWindow(at, zone, quietStart, quietEnd));
        }
        return out;
    }

    // ---------- misc ----------

    private void event(Appointment a, String type, String from, Map<String, Object> payload) {
        AppointmentEvent ev = new AppointmentEvent();
        ev.setAppointmentId(a.getId());
        ev.setEventType(type);
        ev.setFromStatus(from);
        ev.setToStatus(a.getStatus());
        ev.setActor("scheduler");
        ev.setPayload(payload == null ? new LinkedHashMap<>() : payload);
        eventRepo.save(ev);
    }

    private static String trimSlash(String url) {
        if (url == null) return "";
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    private static String mask(String phone) {
        if (phone == null || phone.length() < 5) return "***";
        return phone.substring(0, 4) + "****" + phone.substring(phone.length() - 2);
    }
}
