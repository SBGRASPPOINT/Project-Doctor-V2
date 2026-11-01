package com.vobiz.masking.web;

import com.vobiz.masking.appointment.model.Appointment;
import com.vobiz.masking.appointment.model.MessageTemplate;
import com.vobiz.masking.appointment.repo.AppointmentDao;
import com.vobiz.masking.appointment.repo.AppointmentEventRepository;
import com.vobiz.masking.appointment.repo.MessageTemplateRepository;
import com.vobiz.masking.appointment.repo.ReminderDao;
import com.vobiz.masking.appointment.service.AppointmentIvrService;
import com.vobiz.masking.appointment.service.AppointmentService;
import com.vobiz.masking.appointment.service.AvailabilityService;
import com.vobiz.masking.appointment.service.DepartmentService;
import com.vobiz.masking.appointment.service.DoctorService;
import com.vobiz.masking.appointment.service.PatientDirectoryService;
import com.vobiz.masking.appointment.service.ReminderService;
import com.vobiz.masking.appointment.service.ScheduleService;
import com.vobiz.masking.model.Agent;
import com.vobiz.masking.model.CallRecord;
import com.vobiz.masking.model.CallbackRequest;
import com.vobiz.masking.repo.CallDao;
import com.vobiz.masking.repo.CallbackDao;
import com.vobiz.masking.service.AgentService;
import com.vobiz.masking.service.CallFlowService;
import com.vobiz.masking.service.CallbackService;
import com.vobiz.masking.service.SettingsService;
import com.vobiz.masking.util.ApiException;
import com.vobiz.masking.util.Forms;
import com.vobiz.masking.util.Json;
import com.vobiz.masking.xml.VobizXml;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Framework-free request handlers. The Spring controllers in this package are thin
 * adapters over {@link #handleVoice} / {@link #handleApi}, which keeps 100% of the
 * routing logic unit-testable without Spring MVC on the classpath.
 *
 * Voice: the existing masking webhooks plus the appointment IVR (/voice/ivr/*) and the
 * reminder-call webhooks. With ivr_enabled=false, /voice/answer behaves exactly as before.
 */
public class Handlers {

    private static final Logger log = LogManager.getLogger(Handlers.class);

    private final AgentService agentService;
    private final CallFlowService callFlow;
    private final CallbackService callbackService;
    private final SettingsService settings;
    private final CallDao callDao;
    private final CallbackDao callbackDao;
    private final AppointmentIvrService ivr;
    private final ReminderService reminders;
    private final AppointmentDao appointmentDao;
    private final ReminderDao reminderDao;
    private final AppointmentService appointmentService;
    private final PatientDirectoryService patientDirectoryService;
    private final DoctorService doctorService;
    private final DepartmentService departmentService;
    private final ScheduleService scheduleService;
    private final AvailabilityService availabilityService;
    private final AppointmentEventRepository eventRepo;
    private final MessageTemplateRepository templateRepo;

    public Handlers(AgentService agentService, CallFlowService callFlow, CallbackService callbackService,
                    SettingsService settings, CallDao callDao, CallbackDao callbackDao,
                    AppointmentIvrService ivr, ReminderService reminders,
                    AppointmentDao appointmentDao, ReminderDao reminderDao,
                    AppointmentService appointmentService, PatientDirectoryService patientDirectoryService,
                    DoctorService doctorService, DepartmentService departmentService,
                    ScheduleService scheduleService, AvailabilityService availabilityService,
                    AppointmentEventRepository eventRepo, MessageTemplateRepository templateRepo) {
        this.agentService = agentService;
        this.callFlow = callFlow;
        this.callbackService = callbackService;
        this.settings = settings;
        this.callDao = callDao;
        this.callbackDao = callbackDao;
        this.ivr = ivr;
        this.reminders = reminders;
        this.appointmentDao = appointmentDao;
        this.reminderDao = reminderDao;
        this.appointmentService = appointmentService;
        this.patientDirectoryService = patientDirectoryService;
        this.doctorService = doctorService;
        this.departmentService = departmentService;
        this.scheduleService = scheduleService;
        this.availabilityService = availabilityService;
        this.eventRepo = eventRepo;
        this.templateRepo = templateRepo;
    }

    // ---------------- Vobiz voice webhooks (XML in/out) ----------------

    public HttpResult handleVoice(String method, String path, Map<String, String> params) {
        try {
            return switch (path) {
                case "/voice/answer" -> settings.ivrEnabled() ? ivr.handleAnswer(params) : callFlow.voiceAnswer(params);
                case "/voice/dial-result" -> callFlow.voiceDialResult(params);
                case "/voice/dial-step" -> callFlow.voiceDialStep(params);
                case "/voice/recording-done" -> callFlow.voiceRecordingDone(params);
                case "/voice/hangup" -> {
                    HttpResult r = callFlow.voiceHangup(params);
                    reminders.onOutboundHangup(Forms.firstNonBlank(params, "CallUUID", "call_uuid"));
                    yield r;
                }
                case "/voice/outbound-answer" -> callFlow.voiceOutboundAnswer(params);
                // appointment IVR
                case "/voice/ivr/menu" -> ivr.handleMenu(params);
                case "/voice/ivr/department" -> ivr.handleDepartment(params);
                case "/voice/ivr/doctor" -> ivr.handleDoctor(params);
                case "/voice/ivr/slot" -> ivr.handleSlot(params);
                case "/voice/ivr/confirm" -> ivr.handleConfirm(params);
                case "/voice/ivr/reschedule" -> ivr.handleReschedule(params);
                case "/voice/ivr/cancel" -> ivr.handleCancel(params);
                case "/voice/ivr/no-input" -> ivr.handleNoInput(params);
                case "/voice/ivr/human" -> ivr.handleHuman(params);
                // reminder calls
                case "/voice/reminder-answer" -> ivr.handleReminderAnswer(params);
                case "/voice/reminder-response" -> ivr.handleReminderResponse(params);
                default -> HttpResult.xml(404, VobizXml.emptyResponse());
            };
        } catch (Exception e) {
            // Never fail a live call with a 5xx: an empty Response just ends the call cleanly.
            log.error("voice webhook {} failed; returning empty Response", path, e);
            return HttpResult.xml(200, VobizXml.emptyResponse());
        }
    }

    // ---------------- internal REST API (JSON) ----------------

    public HttpResult handleApi(String method, String path, Map<String, String> query, String body) {
        try {
            if (path.equals("/api/health") && method.equals("GET")) return health();
            if (path.equals("/api/stats") && method.equals("GET")) return stats();
            if (path.equals("/api/stats/appointments") && method.equals("GET")) return appointmentStats();

            if (path.equals("/api/agents")) {
                if (method.equals("GET")) return listAgents();
                if (method.equals("POST")) return createAgent(body);
            }
            if (path.startsWith("/api/agents/")) {
                long id = parseId(path.substring("/api/agents/".length()));
                if (method.equals("GET")) return ok(agentService.get(id));
                if (method.equals("PATCH") || method.equals("PUT")) return ok(agentService.update(id, Json.parseObject(body)));
                if (method.equals("DELETE")) {
                    agentService.deactivate(id);
                    return ok(Map.of("deleted", true, "id", id));
                }
            }

            if (path.equals("/api/calls") && method.equals("GET")) return listCalls(query);
            if (path.startsWith("/api/calls/")) {
                long id = parseId(path.substring("/api/calls/".length()));
                if (method.equals("GET")) {
                    CallRecord c = callDao.findById(id)
                            .orElseThrow(() -> new ApiException(404, "Call not found: " + id));
                    Map<String, Object> m = new LinkedHashMap<>(c.toMap());
                    m.put("callbacks", callbackDao.listByCallId(id));
                    return ok(m);
                }
            }

            if (path.equals("/api/callbacks") && method.equals("GET")) return listCallbacks(query);
            if (path.startsWith("/api/callbacks/")) {
                String rest = path.substring("/api/callbacks/".length());
                if (rest.endsWith("/call") && method.equals("POST")) {
                    long id = parseId(rest.substring(0, rest.length() - "/call".length()));
                    Map<String, Object> req = (body == null || body.isBlank()) ? Map.of() : Json.parseObject(body);
                    Long agentId = req.get("agent_id") instanceof Number n ? n.longValue() : null;
                    return ok(callbackService.initiate(id, agentId));
                }
                long id = parseId(rest);
                if (method.equals("GET")) {
                    CallbackRequest cb = callbackDao.findById(id)
                            .orElseThrow(() -> new ApiException(404, "Callback not found: " + id));
                    Map<String, Object> m = new LinkedHashMap<>(cb.toMap());
                    if (cb.getCallId() != null)
                        callDao.findById(cb.getCallId()).ifPresent(c -> m.put("call", c));
                    return ok(m);
                }
                if (method.equals("PATCH") || method.equals("PUT"))
                    return ok(callbackService.update(id, Json.parseObject(body)));
            }

            if (path.equals("/api/settings")) {
                if (method.equals("GET")) return ok(Map.of("settings", settings.getAll()));
                if (method.equals("PUT") || method.equals("PATCH"))
                    return ok(Map.of("settings", settings.update(Json.parseObject(body))));
            }

            // ---------- appointments ----------
            if (path.equals("/api/appointments")) {
                if (method.equals("GET")) return listAppointments(query);
                if (method.equals("POST")) return createAppointment(body);
            }
            if (path.startsWith("/api/appointments/")) {
                String rest = path.substring("/api/appointments/".length());
                if (rest.endsWith("/reschedule") && method.equals("POST"))
                    return ok(appointmentService.reschedule(parseId(strip(rest, "/reschedule")),
                            Instant.parse(reqStr(Json.parseObject(body), "starts_at")), "agent"));
                if (rest.endsWith("/cancel") && method.equals("POST")) {
                    Map<String, Object> b = Json.parseObject(body);
                    return ok(appointmentService.cancel(parseId(strip(rest, "/cancel")),
                            b.get("reason") == null ? null : String.valueOf(b.get("reason")), "agent"));
                }
                if (rest.endsWith("/complete") && method.equals("POST"))
                    return ok(appointmentService.complete(parseId(strip(rest, "/complete"))));
                if (rest.endsWith("/no-show") && method.equals("POST"))
                    return ok(appointmentService.markNoShow(parseId(strip(rest, "/no-show"))));
                long id = parseId(rest);
                if (method.equals("GET")) {
                    Appointment a = appointmentService.get(id);
                    Map<String, Object> m = new LinkedHashMap<>(a.toMap());
                    m.put("events", eventRepo.findByAppointmentIdOrderByCreatedAtAsc(id));
                    m.put("reminders", reminderDao.repo().findByAppointmentIdOrderByScheduledAtAsc(id));
                    return ok(m);
                }
                if (method.equals("PATCH") || method.equals("PUT"))
                    return ok(appointmentService.update(id, Json.parseObject(body)));
            }

            // ---------- availability ----------
            if (path.equals("/api/availability") && method.equals("GET")) {
                long doctorId = parseId(query.get("doctor_id"));
                LocalDate date = query.get("date") == null || query.get("date").isBlank()
                        ? LocalDate.now(settings.clinicZone()) : LocalDate.parse(query.get("date").trim());
                return ok(Map.of("items", availabilitySlots(doctorId, date, date), "doctor_id", doctorId, "date", date.toString()));
            }
            if (path.equals("/api/availability/week") && method.equals("GET")) {
                long doctorId = parseId(query.get("doctor_id"));
                LocalDate from = query.get("from") == null || query.get("from").isBlank()
                        ? LocalDate.now(settings.clinicZone()) : LocalDate.parse(query.get("from").trim());
                return ok(Map.of("items", availabilitySlots(doctorId, from, from.plusDays(6)), "doctor_id", doctorId));
            }

            // ---------- directory ----------
            if (path.equals("/api/doctors")) {
                if (method.equals("GET")) return ok(Map.of("items", doctorService.listAll()));
                if (method.equals("POST")) return created(doctorService.create(Json.parseObject(body)));
            }
            if (path.startsWith("/api/doctors/")) {
                long id = parseId(path.substring("/api/doctors/".length()));
                if (method.equals("GET")) return ok(doctorService.get(id));
                if (method.equals("PATCH") || method.equals("PUT")) return ok(doctorService.update(id, Json.parseObject(body)));
                if (method.equals("DELETE")) {
                    com.vobiz.masking.appointment.model.Doctor d = doctorService.get(id);
                    d.setActive(false);
                    return ok(doctorService.update(id, Map.of("active", false)));
                }
            }
            if (path.equals("/api/departments")) {
                if (method.equals("GET")) return ok(Map.of("items", departmentService.listAll()));
                if (method.equals("POST")) return created(departmentService.create(Json.parseObject(body)));
            }
            if (path.startsWith("/api/departments/")) {
                long id = parseId(path.substring("/api/departments/".length()));
                if (method.equals("GET")) return ok(departmentService.get(id));
                if (method.equals("PATCH") || method.equals("PUT")) return ok(departmentService.update(id, Json.parseObject(body)));
            }
            if (path.equals("/api/patients")) {
                if (method.equals("GET")) return ok(Map.of("items", patientDirectoryService.list(query.get("q"), parseLimit(query.get("limit")))));
                if (method.equals("POST")) return created(patientDirectoryService.create(Json.parseObject(body)));
            }
            if (path.startsWith("/api/patients/")) {
                long id = parseId(path.substring("/api/patients/".length()));
                if (method.equals("GET")) return ok(patientDirectoryService.get(id));
                if (method.equals("PATCH") || method.equals("PUT")) return ok(patientDirectoryService.update(id, Json.parseObject(body)));
            }
            if (path.equals("/api/schedules") && method.equals("GET")) {
                long doctorId = parseId(query.get("doctor_id"));
                return ok(Map.of("items", scheduleService.listForDoctor(doctorId)));
            }
            if (path.equals("/api/schedules") && method.equals("POST")) {
                Map<String, Object> b = Json.parseObject(body);
                return created(scheduleService.addSchedule(
                        ((Number) b.get("doctor_id")).longValue(),
                        ((Number) b.get("day_of_week")).intValue(),
                        LocalTime.parse(String.valueOf(b.get("start_time"))),
                        LocalTime.parse(String.valueOf(b.get("end_time"))),
                        b.get("slot_minutes") instanceof Number n ? n.intValue() : 0));
            }
            if (path.startsWith("/api/schedules/") && method.equals("DELETE")) {
                scheduleService.deleteSchedule(parseId(path.substring("/api/schedules/".length())));
                return ok(Map.of("deleted", true));
            }
            if (path.equals("/api/time-off") && method.equals("GET")) {
                long doctorId = parseId(query.get("doctor_id"));
                return ok(Map.of("items", scheduleService.listTimeOff(doctorId)));
            }
            if (path.equals("/api/time-off") && method.equals("POST")) return created(scheduleService.addTimeOff(Json.parseObject(body)));
            if (path.startsWith("/api/time-off/") && method.equals("DELETE")) {
                scheduleService.deleteTimeOff(parseId(path.substring("/api/time-off/".length())));
                return ok(Map.of("deleted", true));
            }

            // ---------- reminders ----------
            if (path.equals("/api/reminders") && method.equals("GET")) {
                Long appointmentId = query.get("appointment_id") == null || query.get("appointment_id").isBlank()
                        ? null : Long.parseLong(query.get("appointment_id").trim());
                List<com.vobiz.masking.appointment.model.Reminder> items = reminderDao.repo().findAll();
                List<com.vobiz.masking.appointment.model.Reminder> filtered = new ArrayList<>();
                for (com.vobiz.masking.appointment.model.Reminder r : items) {
                    if (query.get("status") != null && !query.get("status").isBlank() && !query.get("status").equals(r.getStatus())) continue;
                    if (query.get("kind") != null && !query.get("kind").isBlank() && !query.get("kind").equals(r.getKind())) continue;
                    if (appointmentId != null && !appointmentId.equals(r.getAppointmentId())) continue;
                    filtered.add(r);
                }
                return ok(Map.of("items", filtered, "count", filtered.size()));
            }
            if (path.equals("/api/reminders/run") && method.equals("POST")) {
                reminders.runDue();
                return ok(Map.of("ran", true));
            }
            if (path.startsWith("/api/reminders/")) {
                String rest = path.substring("/api/reminders/".length());
                if (rest.endsWith("/call") && method.equals("POST")) {
                    reminders.placeNow(parseId(strip(rest, "/call")));
                    return ok(Map.of("called", true));
                }
                if (rest.endsWith("/suppress") && method.equals("POST")) return ok(reminders.suppress(parseId(strip(rest, "/suppress"))));
                if (method.equals("GET")) return ok(reminders.get(parseId(rest)));
            }

            // ---------- message templates ----------
            if (path.equals("/api/templates") && method.equals("GET")) {
                String key = query.get("key");
                List<MessageTemplate> all = templateRepo.findByActiveTrueOrderByKeyAscLanguageAsc();
                if (key != null && !key.isBlank()) {
                    List<MessageTemplate> f = new ArrayList<>();
                    for (MessageTemplate t : all) if (t.getKey().equals(key)) f.add(t);
                    return ok(Map.of("items", f));
                }
                return ok(Map.of("items", all));
            }
            if (path.startsWith("/api/templates/") && (method.equals("PUT") || method.equals("PATCH"))) {
                long id = parseId(path.substring("/api/templates/".length()));
                MessageTemplate t = templateRepo.findById(id).orElseThrow(() -> new ApiException(404, "Template not found: " + id));
                Map<String, Object> b = Json.parseObject(body);
                if (b.containsKey("text")) t.setText(String.valueOf(b.get("text")));
                if (b.containsKey("active")) t.setActive(Boolean.parseBoolean(String.valueOf(b.get("active"))));
                return ok(templateRepo.save(t));
            }

            return error(404, "Not found: " + method + " " + path);
        } catch (ApiException e) {
            return error(e.getStatus(), e.getMessage());
        } catch (Exception e) {
            log.error("API {} {} failed", method, path, e);
            return error(500, "Internal error: " + e.getMessage());
        }
    }

    // ---------------- endpoint implementations (existing masking API) ----------------

    public HttpResult health() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("status", "ok");
        m.put("service", "appointment-service (call-first, on Vobiz masking)");
        m.put("ivr_enabled", settings.ivrEnabled());
        m.put("time", Instant.now().toString());
        return ok(m);
    }

    public HttpResult stats() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.putAll(agentService.stats());
        m.putAll(callDao.stats());
        m.putAll(callbackDao.stats());
        return ok(m);
    }

    public HttpResult appointmentStats() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.putAll(appointmentDao.stats());
        m.putAll(reminderDao.stats());
        return ok(m);
    }

    public HttpResult listAgents() {
        List<Agent> items = agentService.list();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("items", items);
        m.put("count", items.size());
        return ok(m);
    }

    public HttpResult createAgent(String body) {
        Agent a = agentService.create(Json.parseObject(body));
        return created(a);
    }

    public HttpResult listCalls(Map<String, String> q) {
        int limit = parseLimit(q.get("limit"));
        List<CallRecord> items = callDao.list(q.get("status"), q.get("from"), q.get("to"), q.get("q"), limit);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("items", items);
        m.put("count", items.size());
        m.put("limit", limit);
        return ok(m);
    }

    public HttpResult listCallbacks(Map<String, String> q) {
        List<CallbackRequest> items = callbackDao.list(q.get("status"));
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("items", items);
        m.put("count", items.size());
        return ok(m);
    }

    // ---------------- appointment endpoints ----------------

    public HttpResult listAppointments(Map<String, String> q) {
        int limit = parseLimit(q.get("limit"));
        Long doctorId = q.get("doctor_id") == null || q.get("doctor_id").isBlank() ? null : Long.parseLong(q.get("doctor_id").trim());
        Long patientId = q.get("patient_id") == null || q.get("patient_id").isBlank() ? null : Long.parseLong(q.get("patient_id").trim());
        List<Appointment> items = appointmentDao.list(q.get("status"), doctorId, patientId, q.get("from"), q.get("to"), q.get("q"), limit);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("items", items);
        m.put("count", items.size());
        return ok(m);
    }

    public HttpResult createAppointment(String body) {
        Map<String, Object> b = Json.parseObject(body);
        Long patientId = b.get("patient_id") instanceof Number n ? n.longValue() : null;
        if (patientId == null && b.get("patient_phone_e164") != null) {
            patientId = patientDirectoryService.resolveByPhone(String.valueOf(b.get("patient_phone_e164")))
                    .map(p -> p.getId())
                    .orElseThrow(() -> new ApiException(404, "No patient with phone " + b.get("patient_phone_e164")
                            + " — register them first (POST /api/patients)"));
        }
        if (patientId == null) throw new ApiException(400, "Provide patient_id or patient_phone_e164");
        if (!(b.get("doctor_id") instanceof Number)) throw new ApiException(400, "Missing field: doctor_id");
        long doctorId = ((Number) b.get("doctor_id")).longValue();
        Instant startsAt = Instant.parse(reqStr(b, "starts_at"));
        String reason = b.get("reason") == null ? null : String.valueOf(b.get("reason"));
        String source = b.get("source") == null ? "web" : String.valueOf(b.get("source"));
        Appointment a = appointmentService.create(patientId, doctorId, startsAt, null, reason, source, null);
        return created(a);
    }

    private List<Map<String, Object>> availabilitySlots(long doctorId, LocalDate from, LocalDate to) {
        ZoneId zone = settings.clinicZone();
        List<Map<String, Object>> out = new ArrayList<>();
        for (AvailabilityService.Slot s : availabilityService.freeSlots(doctorId, from, to, 200)) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("starts_at", s.start().toString());
            m.put("ends_at", s.end().toString());
            m.put("label", s.label(zone));
            out.add(m);
        }
        return out;
    }

    // ---------------- helpers ----------------

    private HttpResult ok(Object o) {
        return HttpResult.json(200, Json.write(o));
    }

    private HttpResult created(Object o) {
        return HttpResult.json(201, Json.write(o));
    }

    private HttpResult error(int status, String msg) {
        return HttpResult.json(status, Json.write(Map.of("error", msg)));
    }

    private static String reqStr(Map<String, Object> b, String key) {
        Object v = b.get(key);
        if (v == null) throw new ApiException(400, "Missing field: " + key);
        return String.valueOf(v).trim();
    }

    private static String strip(String rest, String suffix) {
        return rest.substring(0, rest.length() - suffix.length());
    }

    private static long parseId(String s) {
        try {
            return Long.parseLong(s.trim().replaceAll("/+$", ""));
        } catch (Exception e) {
            throw new ApiException(400, "Invalid id: " + s);
        }
    }

    private static int parseLimit(String s) {
        try {
            return Math.min(500, Math.max(1, (s == null || s.isBlank()) ? 100 : Integer.parseInt(s.trim())));
        } catch (NumberFormatException e) {
            return 100;
        }
    }
}
