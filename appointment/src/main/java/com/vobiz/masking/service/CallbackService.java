package com.vobiz.masking.service;

import com.vobiz.masking.model.Agent;
import com.vobiz.masking.model.CallbackRequest;
import com.vobiz.masking.repo.AgentDao;
import com.vobiz.masking.repo.CallDao;
import com.vobiz.masking.repo.CallbackDao;
import com.vobiz.masking.security.WebhookSigner;
import com.vobiz.masking.util.ApiException;
import com.vobiz.masking.vobiz.VobizClient;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * The callback queue. Because of masking, agents never see the customer's number, so
 * callbacks are system-initiated (click-to-call): we dial the AGENT first via the
 * Vobiz outbound Call API, and when they pick up we bridge to the customer with
 * callerId=M1 — both sides only ever see M1.
 */
public class CallbackService {

    private static final Logger log = LogManager.getLogger(CallbackService.class);
    private static final Set<String> STATUSES = Set.of("pending", "calling", "called_back", "closed");

    private final CallbackDao callbackDao;
    private final AgentDao agentDao;
    private final RoutingService routing;
    private final CallDao callDao;
    private final SettingsService settings;
    private final VobizClient vobiz;
    private final String publicBaseUrl;
    private final String defaultM1;
    private final WebhookSigner signer;

    public CallbackService(CallbackDao callbackDao, AgentDao agentDao, RoutingService routing, CallDao callDao,
                           SettingsService settings, VobizClient vobiz, String publicBaseUrl, String defaultM1,
                           WebhookSigner signer) {
        this.callbackDao = callbackDao;
        this.agentDao = agentDao;
        this.routing = routing;
        this.callDao = callDao;
        this.settings = settings;
        this.vobiz = vobiz;
        this.publicBaseUrl = publicBaseUrl;
        this.defaultM1 = defaultM1;
        this.signer = signer;
    }

    /** POST /api/callbacks/{id}/call — place the outbound click-to-call leg. */
    public Map<String, Object> initiate(long callbackId, Long agentIdOpt) {
        CallbackRequest cb = callbackDao.findById(callbackId)
                .orElseThrow(() -> new ApiException(404, "Callback request not found: " + callbackId));
        if (!"pending".equals(cb.getStatus()))
            throw new ApiException(409, "Callback #" + callbackId + " is '" + cb.getStatus() + "'; only 'pending' can be called");

        Agent agent = null;
        if (agentIdOpt != null) {
            agent = agentDao.findById(agentIdOpt)
                    .orElseThrow(() -> new ApiException(404, "Agent not found: " + agentIdOpt));
        } else if (cb.getAssignedAgentId() != null) {
            agent = agentDao.findById(cb.getAssignedAgentId()).orElse(null);
        }
        if (agent == null) {
            agent = routing.eligibleAgents().stream().findFirst()
                    .orElseThrow(() -> new ApiException(409, "No available agent to place the callback"));
        }
        if (!agent.isActive()) throw new ApiException(409, "Agent " + agent.getName() + " is inactive");

        String m1 = settings.m1(defaultM1);
        String answerUrl = publicBaseUrl + "/voice/outbound-answer?to="
                + URLEncoder.encode(cb.getCallerNumber(), StandardCharsets.UTF_8)
                + "&callback_id=" + cb.getId();
        String uuid;
        try {
            uuid = vobiz.placeCall(m1, agent.getPhoneE164(), signer.sign(answerUrl),
                    signer.sign(publicBaseUrl + "/voice/hangup"));
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            throw new ApiException(502, "Vobiz outbound call failed: " + e.getMessage());
        }
        callDao.insertOutbound(uuid, cb.getCallerNumber(), agent.getId(), cb.getId());
        callbackDao.markCalling(cb.getId(), agent.getId(), uuid);
        log.info("Click-to-call placed: callback #{} -> agent {} ({}) as outbound call {}",
                cb.getId(), agent.getName(), agent.getPhoneE164(), uuid);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("callback_id", cb.getId());
        out.put("status", "calling");
        out.put("agent", agent);
        out.put("outbound_call_uuid", uuid);
        return out;
    }

    /** PATCH /api/callbacks/{id} — status / notes / assignment from the ops console. */
    public CallbackRequest update(long id, Map<String, Object> body) {
        callbackDao.findById(id)
                .orElseThrow(() -> new ApiException(404, "Callback request not found: " + id));
        String status = null;
        if (body.containsKey("status")) {
            status = String.valueOf(body.get("status"));
            if (!STATUSES.contains(status))
                throw new ApiException(400, "status must be one of " + STATUSES);
        }
        String notes = body.containsKey("notes") ? String.valueOf(body.get("notes")) : null;
        if (notes != null && notes.length() > 2000)
            throw new ApiException(400, "notes too long (max 2000 chars)");
        Long assigned = null;
        if (body.containsKey("assigned_agent_id")) {
            Object v = body.get("assigned_agent_id");
            if (v == null) {
                assigned = null;
            } else if (v instanceof Number n) {
                assigned = n.longValue();
            } else {
                throw new ApiException(400, "assigned_agent_id must be a number or null");
            }
            if (assigned != null)
                agentDao.findById(assigned).orElseThrow(() -> new ApiException(404, "Agent not found: " + assigned));
        }
        callbackDao.update(id, status, notes, assigned);
        return callbackDao.findById(id).orElseThrow(() -> new ApiException(500, "Update failed"));
    }
}
