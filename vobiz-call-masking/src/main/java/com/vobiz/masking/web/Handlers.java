package com.vobiz.masking.web;

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
import com.vobiz.masking.util.Json;
import com.vobiz.masking.xml.VobizXml;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Framework-free request handlers. The Spring controllers in this package are thin
 * adapters over {@link #handleVoice} / {@link #handleApi}, which keeps 100% of the
 * routing logic unit-testable without Spring MVC on the classpath.
 */
public class Handlers {

    private static final Logger log = LogManager.getLogger(Handlers.class);

    private final AgentService agentService;
    private final CallFlowService callFlow;
    private final CallbackService callbackService;
    private final SettingsService settings;
    private final CallDao callDao;
    private final CallbackDao callbackDao;

    public Handlers(AgentService agentService, CallFlowService callFlow, CallbackService callbackService,
                    SettingsService settings, CallDao callDao, CallbackDao callbackDao) {
        this.agentService = agentService;
        this.callFlow = callFlow;
        this.callbackService = callbackService;
        this.settings = settings;
        this.callDao = callDao;
        this.callbackDao = callbackDao;
    }

    // ---------------- Vobiz voice webhooks (XML in/out) ----------------

    public HttpResult handleVoice(String method, String path, Map<String, String> params) {
        try {
            return switch (path) {
                case "/voice/answer" -> callFlow.voiceAnswer(params);
                case "/voice/dial-result" -> callFlow.voiceDialResult(params);
                case "/voice/dial-step" -> callFlow.voiceDialStep(params);
                case "/voice/recording-done" -> callFlow.voiceRecordingDone(params);
                case "/voice/hangup" -> callFlow.voiceHangup(params);
                case "/voice/outbound-answer" -> callFlow.voiceOutboundAnswer(params);
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

            return error(404, "Not found: " + method + " " + path);
        } catch (ApiException e) {
            return error(e.getStatus(), e.getMessage());
        } catch (Exception e) {
            log.error("API {} {} failed", method, path, e);
            return error(500, "Internal error: " + e.getMessage());
        }
    }

    // ---------------- endpoint implementations ----------------

    public HttpResult health() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("status", "ok");
        m.put("service", "vobiz-call-masking");
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

    public HttpResult listAgents() {
        List<Agent> items = agentService.list();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("items", items);
        m.put("count", items.size());
        return ok(m);
    }

    public HttpResult createAgent(String body) {
        Agent a = agentService.create(Json.parseObject(body));
        return HttpResult.json(201, Json.write(a));
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

    // ---------------- helpers ----------------

    private HttpResult ok(Object o) {
        return HttpResult.json(200, Json.write(o));
    }

    private HttpResult error(int status, String msg) {
        return HttpResult.json(status, Json.write(Map.of("error", msg)));
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
