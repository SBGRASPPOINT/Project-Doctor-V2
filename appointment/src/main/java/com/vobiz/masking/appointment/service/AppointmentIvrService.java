package com.vobiz.masking.appointment.service;

import com.vobiz.masking.appointment.model.Appointment;
import com.vobiz.masking.appointment.model.Department;
import com.vobiz.masking.appointment.model.Doctor;
import com.vobiz.masking.appointment.model.IvrSession;
import com.vobiz.masking.appointment.model.Patient;
import com.vobiz.masking.appointment.model.Reminder;
import com.vobiz.masking.appointment.repo.DepartmentRepository;
import com.vobiz.masking.appointment.repo.DoctorRepository;
import com.vobiz.masking.appointment.repo.IvrSessionRepository;
import com.vobiz.masking.model.CallRecord;
import com.vobiz.masking.repo.CallDao;
import com.vobiz.masking.security.WebhookSigner;
import com.vobiz.masking.service.CallFlowService;
import com.vobiz.masking.service.SettingsService;
import com.vobiz.masking.util.ApiException;
import com.vobiz.masking.util.Forms;
import com.vobiz.masking.web.HttpResult;
import com.vobiz.masking.xml.VobizXml;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;

/**
 * The call-first booking IVR. Vobiz keeps NO state between webhooks, so every handler reads
 * the {@link IvrSession} row by CallUUID, mutates it and writes it back — never trust a query
 * param for state. One method per webhook, each taking the form params and returning an
 * {@link HttpResult}, exactly like {@code CallFlowService}.
 *
 * Flow: answer → greet (known caller by name) → main menu → department → doctor → slots →
 * confirm → booked (reference code spoken). Every menu has an escape to the human path
 * (press 0), which reuses {@link CallFlowService#bridgeToAgentsXml} — the existing masking
 * flow with voicemail fallback. Reminder calls land in /voice/reminder-answer.
 */
public class AppointmentIvrService {

    private static final Logger log = LogManager.getLogger(AppointmentIvrService.class);
    private static final int GATHER_TIMEOUT_SEC = 8;
    private static final DateTimeFormatter DAY_LABEL =
            DateTimeFormatter.ofPattern("EEEE d MMMM", Locale.ENGLISH);
    private static final DateTimeFormatter TIME_LABEL =
            DateTimeFormatter.ofPattern("h:mm a", Locale.ENGLISH);

    private final IvrSessionRepository sessions;
    private final PatientDirectoryService patients;
    private final DepartmentRepository departments;
    private final DoctorRepository doctors;
    private final AvailabilityService availability;
    private final AppointmentService appointments;
    private final ReminderService reminders;
    private final SettingsService settings;
    private final PromptService prompts;
    private final CallFlowService callFlow;
    private final CallDao callDao;
    private final String publicBaseUrl;
    private final WebhookSigner signer;

    public AppointmentIvrService(IvrSessionRepository sessions, PatientDirectoryService patients,
                                 DepartmentRepository departments, DoctorRepository doctors,
                                 AvailabilityService availability, AppointmentService appointments,
                                 ReminderService reminders, SettingsService settings, PromptService prompts,
                                 CallFlowService callFlow, CallDao callDao, String publicBaseUrl,
                                 WebhookSigner signer) {
        this.sessions = sessions;
        this.patients = patients;
        this.departments = departments;
        this.doctors = doctors;
        this.availability = availability;
        this.appointments = appointments;
        this.reminders = reminders;
        this.settings = settings;
        this.prompts = prompts;
        this.callFlow = callFlow;
        this.callDao = callDao;
        this.signer = signer;
        this.publicBaseUrl = publicBaseUrl.endsWith("/")
                ? publicBaseUrl.substring(0, publicBaseUrl.length() - 1) : publicBaseUrl;
    }

    // ---------------- entry: POST /voice/answer (when ivr_enabled) ----------------

    public HttpResult handleAnswer(Map<String, String> p) {
        String callUuid = reqUuid(p);
        if (callUuid == null) return HttpResult.xml(400, VobizXml.emptyResponse());
        String from = p.getOrDefault("From", "");
        callDao.insertInbound(callUuid, from, p.getOrDefault("To", ""));
        log.info("Inbound call {} from {} — starting appointment IVR", callUuid, mask(from));
        Patient patient = patients.resolveByPhone(from).orElse(null);
        IvrSession s = new IvrSession();
        s.setCallUuid(callUuid);
        s.setCallerNumber(from);
        if (patient != null) s.setPatientId(patient.getId());
        s.setState("main_menu");
        s.setHops(1);
        sessions.save(s);
        return HttpResult.xml(200, mainMenuXml(callUuid, patient));
    }

    // ---------------- main menu ----------------

    public HttpResult handleMenu(Map<String, String> p) {
        String callUuid = reqUuid(p);
        IvrSession s = session(callUuid);
        if (s == null) return HttpResult.xml(200, VobizXml.emptyResponse());
        String lang = langOf(s);
        String digit = p.getOrDefault("Digits", "");
        hop(s);
        if (overCap(s)) return human(callUuid, s);
        switch (digit) {
            case "1" -> {
                if (!settings.allowSelfBooking()) {
                    return prompt(s, lang, "ivr_booking_disabled", Map.of(), action(callUuid, "/voice/ivr/menu"));
                }
                s.setIntent("book");
                return departmentMenu(callUuid, s, lang);
            }
            case "2" -> {
                List<Appointment> ups = upcoming(s);
                if (ups.isEmpty()) {
                    return prompt(s, lang, "ivr_no_appointments", Map.of(), action(callUuid, "/voice/ivr/menu"));
                }
                s.setIntent("reschedule");
                s.setState("reschedule_pick");
                s.collected().put("appointment_ids", ids(ups));
                return prompt(s, lang, "ivr_pick_appointment",
                        Map.of("list", numbered(ups, a -> a.getReferenceCode() + ", " + doctorName(a) + ", " + label(a))),
                        action(callUuid, "/voice/ivr/reschedule"));
            }
            case "3" -> {
                List<Appointment> ups = upcoming(s);
                if (ups.isEmpty()) {
                    return prompt(s, lang, "ivr_no_appointments", Map.of(), action(callUuid, "/voice/ivr/menu"));
                }
                s.setIntent("cancel");
                s.setState("cancel_pick");
                s.collected().put("appointment_ids", ids(ups));
                return prompt(s, lang, "ivr_pick_appointment",
                        Map.of("list", numbered(ups, a -> a.getReferenceCode() + ", " + doctorName(a) + ", " + label(a))),
                        action(callUuid, "/voice/ivr/cancel"));
            }
            case "4", "0" -> { return human(callUuid, s); }
            default -> { sessions.save(s); return HttpResult.xml(200, mainMenuXml(callUuid, patientOf(s))); }
        }
    }

    // ---------------- book: department → doctor → slot → confirm ----------------

    public HttpResult handleDepartment(Map<String, String> p) {
        String callUuid = reqUuid(p);
        IvrSession s = session(callUuid);
        if (s == null) return HttpResult.xml(200, VobizXml.emptyResponse());
        String lang = langOf(s);
        String digit = p.getOrDefault("Digits", "");
        hop(s);
        if (overCap(s)) return human(callUuid, s);
        if (digit.equals("0")) return human(callUuid, s);
        List<Department> deps = departments.findByActiveTrueOrderByPriorityAscNameAsc();
        int idx = parseDigit(digit);
        if (idx < 1 || idx > deps.size()) return departmentMenu(callUuid, s, lang);
        Department dep = deps.get(idx - 1);
        s.collected().put("department_id", dep.getId().toString());
        return doctorMenu(callUuid, s, lang, dep.getId());
    }

    public HttpResult handleDoctor(Map<String, String> p) {
        String callUuid = reqUuid(p);
        IvrSession s = session(callUuid);
        if (s == null) return HttpResult.xml(200, VobizXml.emptyResponse());
        String lang = langOf(s);
        String digit = p.getOrDefault("Digits", "");
        hop(s);
        if (overCap(s)) return human(callUuid, s);
        if (digit.equals("0")) return human(callUuid, s);
        Long deptId = parseLong((String) s.collected().get("department_id"));
        List<Doctor> docs = deptId == null ? doctors.findByActiveTrueOrderByNameAsc()
                : doctors.findByActiveTrueAndDepartmentIdOrderByNameAsc(deptId);
        int idx = parseDigit(digit);
        if (idx < 1 || idx > docs.size()) return doctorMenu(callUuid, s, lang, deptId);
        Doctor doc = docs.get(idx - 1);
        s.setState("choose_slot");
        s.collected().put("doctor_id", doc.getId().toString());
        return offerSlots(callUuid, s, lang, doc);
    }

    public HttpResult handleSlot(Map<String, String> p) {
        String callUuid = reqUuid(p);
        IvrSession s = session(callUuid);
        if (s == null) return HttpResult.xml(200, VobizXml.emptyResponse());
        String lang = langOf(s);
        String digit = p.getOrDefault("Digits", "");
        hop(s);
        if (overCap(s)) return human(callUuid, s);
        if (digit.equals("0")) return human(callUuid, s);
        List<String> slots = stringList(s.collected().get("slots"));
        int idx = parseDigit(digit);
        if (idx < 1 || idx > slots.size()) {
            Long doctorId = parseLong((String) s.collected().get("doctor_id"));
            Doctor doc = doctorId == null ? null : doctors.findById(doctorId).orElse(null);
            if (doc == null) return human(callUuid, s);
            return offerSlots(callUuid, s, lang, doc);
        }
        Instant startsAt = Instant.parse(slots.get(idx - 1));
        s.setState("confirm");
        s.collected().put("starts_at", startsAt.toString());
        sessions.save(s);
        Long doctorId = parseLong((String) s.collected().get("doctor_id"));
        Doctor doc = doctors.findById(doctorId).orElseThrow(() -> new ApiException(404, "Doctor not found"));
        return prompt(s, lang, "ivr_confirm_booking",
                Map.of("doctor", doc.getName(), "day", dayLabel(startsAt), "time", timeLabel(startsAt)),
                action(callUuid, "/voice/ivr/confirm"));
    }

    /** Final confirm: digit 1 books (or reschedules), 2 re-offers slots, 0 → reception. */
    public HttpResult handleConfirm(Map<String, String> p) {
        String callUuid = reqUuid(p);
        IvrSession s = session(callUuid);
        if (s == null) return HttpResult.xml(200, VobizXml.emptyResponse());
        String lang = langOf(s);
        String digit = p.getOrDefault("Digits", "");
        hop(s);
        if (overCap(s)) return human(callUuid, s);
        if (digit.equals("0")) return human(callUuid, s);
        if (digit.equals("2")) {
            Long doctorId = parseLong((String) s.collected().get("doctor_id"));
            Doctor doc = doctors.findById(doctorId).orElseThrow(() -> new ApiException(404, "Doctor not found"));
            s.setState("choose_slot");
            return offerSlots(callUuid, s, lang, doc);
        }
        if (!digit.equals("1")) {
            return prompt(s, lang, "ivr_no_input", Map.of(), action(callUuid, "/voice/ivr/confirm"));
        }
        Instant startsAt = Instant.parse((String) s.collected().get("starts_at"));
        Long doctorId = parseLong((String) s.collected().get("doctor_id"));
        try {
            if ("reschedule".equals(s.getIntent())) {
                Long apptId = parseLong((String) s.collected().get("appointment_id"));
                if (apptId == null) return human(callUuid, s);   // lost session state → reception, not an NPE
                Appointment a = appointments.reschedule(apptId, startsAt, "patient_ivr");
                s.setAppointmentId(a.getId());
                sessions.save(s);
                log.info("Call {}: appointment {} rescheduled via IVR to {}", callUuid, a.getReferenceCode(), startsAt);
                return speakAndHangup(s, lang, "ivr_rescheduled_done",
                        Map.of("ref", a.getReferenceCode(), "day", dayLabel(startsAt), "time", timeLabel(startsAt)));
            }
            Long patientId = s.getPatientId();
            if (patientId == null) {
                Patient np = patients.registerUnknownCaller(s.getCallerNumber(), null);
                patientId = np.getId();
                s.setPatientId(patientId);
            }
            Long callId = callDao.findByUuid(callUuid).map(CallRecord::getId).orElse(null);
            Appointment a = appointments.create(patientId, doctorId, startsAt, null, null, "ivr_call", callId);
            s.setAppointmentId(a.getId());
            s.setState("done");
            sessions.save(s);
            log.info("Call {}: booked {} for patient {} at {}", callUuid, a.getReferenceCode(), patientId, startsAt);
            return speakAndHangup(s, lang, "ivr_booked_done", Map.of("ref", a.getReferenceCode()));
        } catch (ApiException e) {
            log.info("Call {}: booking failed: {}", callUuid, e.getMessage());
            if (e.getStatus() == 409) {
                Doctor doc = doctors.findById(doctorId).orElse(null);
                if (doc != null) {
                    s.setState("choose_slot");
                    return offerSlots(callUuid, s, lang, doc);   // slot gone → offer others
                }
            }
            return speakAndHangup(s, lang, "ivr_slot_gone", Map.of());
        }
    }

    // ---------------- reschedule pick ----------------

    public HttpResult handleReschedule(Map<String, String> p) {
        String callUuid = reqUuid(p);
        IvrSession s = session(callUuid);
        if (s == null) return HttpResult.xml(200, VobizXml.emptyResponse());
        String lang = langOf(s);
        String digit = p.getOrDefault("Digits", "");
        hop(s);
        if (overCap(s)) return human(callUuid, s);
        if (digit.equals("0")) return human(callUuid, s);
        List<String> ids = stringList(s.collected().get("appointment_ids"));
        int idx = parseDigit(digit);
        if (idx < 1 || idx > ids.size()) {
            return prompt(s, lang, "ivr_pick_appointment", Map.of("list", renumber(ids)),
                    action(callUuid, "/voice/ivr/reschedule"));
        }
        Long apptId = Long.parseLong(ids.get(idx - 1));
        Appointment a = appointments.get(apptId);
        s.setState("choose_slot");
        s.setIntent("reschedule");
        s.collected().put("appointment_id", apptId.toString());
        Doctor doc = doctors.findById(a.getDoctorId()).orElseThrow(() -> new ApiException(404, "Doctor not found"));
        return offerSlots(callUuid, s, lang, doc);
    }

    // ---------------- cancel (pick → confirm, same route) ----------------

    public HttpResult handleCancel(Map<String, String> p) {
        String callUuid = reqUuid(p);
        IvrSession s = session(callUuid);
        if (s == null) return HttpResult.xml(200, VobizXml.emptyResponse());
        String lang = langOf(s);
        String digit = p.getOrDefault("Digits", "");
        hop(s);
        if (overCap(s)) return human(callUuid, s);
        if (digit.equals("0")) return human(callUuid, s);
        if ("cancel_confirm".equals(s.getState())) {
            if (digit.equals("1")) {
                Long apptId = parseLong((String) s.collected().get("appointment_id"));
                Appointment a = appointments.cancel(apptId, "Cancelled by patient via IVR", "patient_ivr");
                s.setState("done");
                sessions.save(s);
                log.info("Call {}: appointment {} cancelled via IVR", callUuid, a.getReferenceCode());
                return speakAndHangup(s, lang, "ivr_cancel_done", Map.of("ref", a.getReferenceCode()));
            }
            s.setState("cancel_pick");
            sessions.save(s);
            return HttpResult.xml(200, mainMenuXml(callUuid, patientOf(s)));
        }
        List<String> ids = stringList(s.collected().get("appointment_ids"));
        int idx = parseDigit(digit);
        if (idx < 1 || idx > ids.size()) {
            return prompt(s, lang, "ivr_pick_appointment", Map.of("list", renumber(ids)),
                    action(callUuid, "/voice/ivr/cancel"));
        }
        Long apptId = Long.parseLong(ids.get(idx - 1));
        Appointment a = appointments.get(apptId);
        // self-cancel cut-off: too close to the appointment → hand to reception
        long hoursLeft = java.time.Duration.between(Instant.now(), a.getStartsAt()).toHours();
        if (hoursLeft < settings.allowSelfCancelHours()) {
            return prompt(s, lang, "ivr_cancel_too_late", Map.of(), humanUrl(callUuid));
        }
        s.setState("cancel_confirm");
        s.collected().put("appointment_id", apptId.toString());
        return prompt(s, lang, "ivr_confirm_cancel",
                Map.of("ref", a.getReferenceCode(), "doctor", doctorName(a), "day", dayLabel(a.getStartsAt()),
                        "time", timeLabel(a.getStartsAt())),
                action(callUuid, "/voice/ivr/cancel"));
    }

    // ---------------- no-input / human escape ----------------

    public HttpResult handleNoInput(Map<String, String> p) {
        String callUuid = reqUuid(p);
        IvrSession s = session(callUuid);
        if (s == null) return HttpResult.xml(200, VobizXml.emptyResponse());
        int n = (int) s.collected().getOrDefault("no_input", 0) + 1;
        s.collected().put("no_input", n);
        hop(s);
        if (shouldEscapeToHuman(n, s.getHops(), settings.ivrMaxHops())) return human(callUuid, s);
        sessions.save(s);
        return HttpResult.xml(200, mainMenuXml(callUuid, patientOf(s)));
    }

    public HttpResult handleHuman(Map<String, String> p) {
        String callUuid = reqUuid(p);
        IvrSession s = session(callUuid);
        if (s == null) return HttpResult.xml(200, VobizXml.emptyResponse());
        return human(callUuid, s);
    }

    // ---------------- reminder call webhooks ----------------

    /** Patient answered an outbound reminder call: play the reminder + gather 1/2/3. */
    public HttpResult handleReminderAnswer(Map<String, String> p) {
        String callUuid = reqUuid(p);
        Long reminderId = parseLong(p.get("reminder_id"));
        Long appointmentId = parseLong(p.get("appointment_id"));
        if (callUuid == null || reminderId == null || appointmentId == null)
            return HttpResult.xml(200, VobizXml.emptyResponse());
        Appointment a = appointments.get(appointmentId);
        Patient patient = patients.get(a.getPatientId());
        Reminder r = reminders.get(reminderId);
        IvrSession s = sessions.findById(callUuid).orElseGet(() -> {
            IvrSession n = new IvrSession();
            n.setCallUuid(callUuid);
            return n;
        });
        s.setPatientId(patient.getId());
        s.setCallerNumber(patient.getPhoneE164());
        s.setReminderId(reminderId);
        s.setAppointmentId(appointmentId);
        s.setState("reminder_menu");
        s.setHops(1);
        sessions.save(s);
        String key = switch (r.getKind()) {
            case Reminder.KIND_CHANGE -> "reminder_change";
            case Reminder.KIND_T2H -> "reminder_t2h";
            default -> "reminder_t24h";
        };
        Map<String, String> vars = Map.of(
                "clinic", settings.clinicName(),
                "doctor", doctorName(a),
                "day", dayLabel(a.getStartsAt()),
                "time", timeLabel(a.getStartsAt()),
                "ref", a.getReferenceCode() == null ? "" : a.getReferenceCode(),
                "reason", r.getErrorText() == null ? "" : r.getErrorText());
        String action = signer.sign(publicBaseUrl + "/voice/reminder-response?reminder_id=" + reminderId
                + "&call_uuid=" + enc(callUuid));
        return HttpResult.xml(200, VobizXml.response(
                VobizXml.gather(VobizXml.speak(prompts.render(key, patient.getPreferredLanguage(), vars)), action, 1, 10)
                        + VobizXml.redirect(noInputUrl(callUuid))));
    }

    /** 1 confirm / 2 reschedule (→ reception) / 3 cancel. */
    public HttpResult handleReminderResponse(Map<String, String> p) {
        String callUuid = reqUuid(p);
        Long reminderId = parseLong(p.get("reminder_id"));
        if (callUuid == null || reminderId == null) return HttpResult.xml(200, VobizXml.emptyResponse());
        IvrSession s = session(callUuid);
        String lang = langOf(s);
        String digit = p.getOrDefault("Digits", "");
        // The self-cancel cut-off is checked BEFORE the response is recorded, so a refusal is not
        // logged as "patient cancelled" (and cannot happen here when the main IVR would refuse too).
        if ("3".equals(digit)) {
            Appointment a = appointments.get(reminders.get(reminderId).getAppointmentId());
            long hoursLeft = java.time.Duration.between(Instant.now(), a.getStartsAt()).toHours();
            if (hoursLeft < settings.allowSelfCancelHours()) {
                reminders.markAnswered(reminderId, "0");
                return HttpResult.xml(200, VobizXml.response(
                        VobizXml.speak(prompts.render("ivr_cancel_too_late", lang, Map.of()))
                                + VobizXml.redirect(humanUrl(callUuid))));
            }
            reminders.markAnswered(reminderId, digit);
            appointments.cancel(a.getId(), "Cancelled by patient on reminder call", "patient_ivr");
            return HttpResult.xml(200, VobizXml.response(
                    VobizXml.speak(prompts.render("ivr_cancel_done", lang, Map.of("ref", a.getReferenceCode() == null ? "" : a.getReferenceCode())))
                            + VobizXml.hangup()));
        }
        reminders.markAnswered(reminderId, digit);
        log.info("Reminder #{} answered with digit {}", reminderId, digit);
        switch (digit) {
            case "1" -> {
                return HttpResult.xml(200, VobizXml.response(
                        VobizXml.speak(prompts.render("reminder_thanks", lang, Map.of())) + VobizXml.hangup()));
            }
            case "2" -> {
                return HttpResult.xml(200, VobizXml.response(
                        VobizXml.speak(prompts.render("ivr_human", lang, Map.of()))
                                + VobizXml.redirect(humanUrl(callUuid))));
            }
            default -> {
                return HttpResult.xml(200, VobizXml.response(
                        VobizXml.speak(prompts.render("reminder_goodbye", lang, Map.of())) + VobizXml.hangup()));
            }
        }
    }

    // ---------------- shared builders ----------------

    private String mainMenuXml(String callUuid, Patient patient) {
        String lang = patient != null ? patient.getPreferredLanguage() : "en";
        String welcome = patient != null
                ? prompts.render("ivr_welcome_known", lang, Map.of("name", patient.getName()))
                : prompts.render("ivr_welcome_unknown", lang, Map.of("clinic", settings.clinicName()));
        String menu = prompts.render("ivr_main_menu", lang, Map.of("clinic", settings.clinicName()));
        return VobizXml.response(
                VobizXml.gather(VobizXml.speak(welcome + " " + menu), action(callUuid, "/voice/ivr/menu"), 1, GATHER_TIMEOUT_SEC)
                        + VobizXml.redirect(noInputUrl(callUuid)));
    }

    private HttpResult departmentMenu(String callUuid, IvrSession s, String lang) {
        List<Department> deps = departments.findByActiveTrueOrderByPriorityAscNameAsc();
        if (deps.isEmpty()) return doctorMenu(callUuid, s, lang, null);
        s.setState("choose_department");
        return prompt(s, lang, "ivr_choose_department", Map.of("list", numbered(deps, Department::getName)),
                action(callUuid, "/voice/ivr/department"));
    }

    private HttpResult doctorMenu(String callUuid, IvrSession s, String lang, Long departmentId) {
        List<Doctor> docs = departmentId == null ? doctors.findByActiveTrueOrderByNameAsc()
                : doctors.findByActiveTrueAndDepartmentIdOrderByNameAsc(departmentId);
        if (docs.isEmpty()) {
            return prompt(s, lang, "ivr_no_slots", Map.of(), action(callUuid, "/voice/ivr/menu"));
        }
        s.setState("choose_doctor");
        return prompt(s, lang, "ivr_choose_doctor", Map.of("list", numbered(docs, Doctor::getName)),
                action(callUuid, "/voice/ivr/doctor"));
    }

    private HttpResult offerSlots(String callUuid, IvrSession s, String lang, Doctor doc) {
        ZoneId zone = settings.clinicZone();
        LocalDate today = LocalDate.now(zone);
        List<AvailabilityService.Slot> slots = availability.freeSlots(
                doc.getId(), today, today.plusDays(settings.bookingHorizonDays()), settings.maxSlotsOffered());
        if (slots.isEmpty()) {
            return prompt(s, lang, "ivr_no_slots", Map.of("doctor", doc.getName()), action(callUuid, "/voice/ivr/doctor"));
        }
        s.setState("choose_slot");
        List<String> iso = new ArrayList<>();
        StringBuilder list = new StringBuilder();
        for (int i = 0; i < slots.size(); i++) {
            iso.add(slots.get(i).start().toString());
            list.append(i + 1).append(". ").append(slots.get(i).label(zone)).append(". ");
        }
        s.collected().put("slots", iso);
        sessions.save(s);
        return prompt(s, lang, "ivr_choose_slot", Map.of("list", list.toString()), action(callUuid, "/voice/ivr/slot"));
    }

    /** Speak a template, then gather one digit at actionUrl; on timeout → no-input handler. */
    private HttpResult prompt(IvrSession s, String lang, String templateKey, Map<String, String> vars, String actionUrl) {
        sessions.save(s);
        String text = prompts.render(templateKey, lang, vars);
        return HttpResult.xml(200, VobizXml.response(
                VobizXml.gather(VobizXml.speak(text), actionUrl, 1, GATHER_TIMEOUT_SEC)
                        + VobizXml.redirect(noInputUrl(s.getCallUuid()))));
    }

    private HttpResult speakAndHangup(IvrSession s, String lang, String templateKey, Map<String, String> vars) {
        sessions.save(s);
        return HttpResult.xml(200, VobizXml.response(
                VobizXml.speak(prompts.render(templateKey, lang, vars)) + VobizXml.hangup()));
    }

    /** Escape to the existing masking flow (agents + voicemail fallback), still callerId=M1. */
    private HttpResult human(String callUuid, IvrSession s) {
        s.setState("bridged");
        sessions.save(s);
        log.info("Call {}: IVR → human (reception)", callUuid);
        return HttpResult.xml(200, callFlow.bridgeToAgentsXml(callUuid));
    }

    // ---------------- small helpers ----------------

    private IvrSession session(String callUuid) {
        return sessions.findById(callUuid).orElse(null);
    }

    private static String reqUuid(Map<String, String> p) {
        return Forms.firstNonBlank(p, "CallUUID", "call_uuid");
    }

    private void hop(IvrSession s) {
        s.setHops(s.getHops() + 1);
    }

    private boolean overCap(IvrSession s) {
        return shouldEscapeToHuman(0, s.getHops(), settings.ivrMaxHops());
    }

    /** Pure: after two no-inputs, or past the hop cap, escape to the human path. */
    public static boolean shouldEscapeToHuman(int noInputCount, int hops, int maxHops) {
        return noInputCount >= 2 || hops > maxHops;
    }

    private List<Appointment> upcoming(IvrSession s) {
        if (s.getPatientId() == null) return List.of();
        return appointments.upcomingForPatient(s.getPatientId(), 9);
    }

    private String langOf(IvrSession s) {
        Patient p = patientOf(s);
        return p != null ? p.getPreferredLanguage() : "en";
    }

    private Patient patientOf(IvrSession s) {
        if (s == null || s.getPatientId() == null) return null;
        try {
            return patients.get(s.getPatientId());
        } catch (Exception e) {
            return null;
        }
    }

    private String doctorName(Appointment a) {
        return doctors.findById(a.getDoctorId()).map(Doctor::getName).orElse("the doctor");
    }

    private String label(Appointment a) {
        ZoneId zone = settings.clinicZone();
        return DAY_LABEL.withZone(zone).format(a.getStartsAt()) + " at " + TIME_LABEL.withZone(zone).format(a.getStartsAt());
    }

    private String dayLabel(Instant at) {
        return DAY_LABEL.withZone(settings.clinicZone()).format(at);
    }

    private String timeLabel(Instant at) {
        return TIME_LABEL.withZone(settings.clinicZone()).format(at);
    }

    private String action(String callUuid, String path) {
        return signer.sign(publicBaseUrl + path + "?call_uuid=" + enc(callUuid));
    }

    private String noInputUrl(String callUuid) {
        return action(callUuid, "/voice/ivr/no-input");
    }

    private String humanUrl(String callUuid) {
        return action(callUuid, "/voice/ivr/human");
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    private static int parseDigit(String d) {
        try {
            return Integer.parseInt(d.trim());
        } catch (Exception e) {
            return -1;
        }
    }

    private static Long parseLong(String s) {
        try {
            return s == null ? null : Long.parseLong(s.trim());
        } catch (Exception e) {
            return null;
        }
    }

    /** Re-speaks the appointment list that was offered (ids were stored in the session). */
    private String renumber(List<String> appointmentIds) {
        StringBuilder sb = new StringBuilder();
        int i = 1;
        for (String raw : appointmentIds) {
            Long id = parseLong(raw);
            if (id == null) continue;
            try {
                Appointment a = appointments.get(id);
                sb.append(i++).append(". ").append(a.getReferenceCode()).append(", ")
                        .append(doctorName(a)).append(", ").append(label(a)).append(". ");
            } catch (ApiException e) {
                // Stale id (appointment deleted): skip it rather than end the call with an error.
                log.warn("Skipping missing appointment {} while re-speaking the menu", id);
            }
        }
        return sb.toString();
    }

    private static List<String> ids(List<Appointment> appts) {
        List<String> out = new ArrayList<>();
        for (Appointment a : appts) out.add(a.getId().toString());
        return out;
    }

    @SuppressWarnings("unchecked")
    private static List<String> stringList(Object v) {
        if (v instanceof List<?> l) {
            List<String> out = new ArrayList<>();
            for (Object o : l) out.add(String.valueOf(o));
            return out;
        }
        return List.of();
    }

    private static <T> String numbered(List<T> items, Function<T, String> label) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < items.size(); i++) {
            sb.append(i + 1).append(". ").append(label.apply(items.get(i))).append(". ");
        }
        return sb.toString();
    }

    /** Mask a phone number in logs (DPDP). */
    private static String mask(String phone) {
        if (phone == null || phone.length() < 5) return "***";
        return phone.substring(0, 4) + "****" + phone.substring(phone.length() - 2);
    }
}
