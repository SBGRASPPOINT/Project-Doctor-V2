package com.vobiz.masking.service;

import com.vobiz.masking.model.Agent;
import com.vobiz.masking.model.CallRecord;
import com.vobiz.masking.model.CallbackRequest;
import com.vobiz.masking.repo.CallDao;
import com.vobiz.masking.repo.CallbackDao;
import com.vobiz.masking.security.WebhookSigner;
import com.vobiz.masking.util.Forms;
import com.vobiz.masking.web.HttpResult;
import com.vobiz.masking.xml.VobizXml;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * Implements the Vobiz voice webhooks. Vobiz holds no state between webhooks, so every
 * handler works from CallUUID (+ query params) alone.
 *
 * Flow: /voice/answer logs the call and returns the routing XML. If nobody answers, the
 * XML after the Dial (voicemail fallback) runs in the same document; /voice/dial-result
 * just records the outcome. The recording callback opens a callback request.
 */
public class CallFlowService {

    private static final Logger log = LogManager.getLogger(CallFlowService.class);

    private final CallDao callDao;
    private final CallbackDao callbackDao;
    private final SettingsService settings;
    private final RoutingService routing;
    private final String publicBaseUrl;
    private final String defaultM1;
    private final WebhookSigner signer;

    public CallFlowService(CallDao callDao, CallbackDao callbackDao, SettingsService settings,
                           RoutingService routing, String publicBaseUrl, String defaultM1,
                           WebhookSigner signer) {
        this.callDao = callDao;
        this.callbackDao = callbackDao;
        this.settings = settings;
        this.routing = routing;
        this.publicBaseUrl = publicBaseUrl;
        this.defaultM1 = defaultM1;
        this.signer = signer;
    }

    /** POST /voice/answer — a caller dialled M1. Log the call, then return the routing XML. */
    public HttpResult voiceAnswer(Map<String, String> p) {
        String callUuid = Forms.firstNonBlank(p, "CallUUID", "call_uuid");
        if (callUuid == null) return HttpResult.xml(400, VobizXml.emptyResponse());
        String from = p.getOrDefault("From", "");
        String to = p.getOrDefault("To", "");
        callDao.insertInbound(callUuid, from, to);
        log.info("Inbound call {} from {} to {}", callUuid, mask(from), to);
        return HttpResult.xml(200, bridgeToAgentsXml(callUuid));
    }

    /**
     * The reusable "human path": optional recording announcement + <Dial> to the eligible
     * agents (simultaneous, or the first one in sequential mode) + voicemail fallback in the
     * same document. Extracted so the appointment IVR can fall through to reception (or a
     * specific doctor) without duplicating the XML.
     */
    public String bridgeToAgentsXml(String callUuid) {
        String m1 = settings.m1(defaultM1);
        int ringTimeout = settings.ringTimeoutSec();
        List<Agent> agents = routing.eligibleAgents();
        String mode = settings.routingMode();

        StringBuilder inner = new StringBuilder();
        String fallback = voicemailFallback();
        if (!agents.isEmpty() && settings.announceRecording()) {
            inner.append(VobizXml.speak(settings.announcementText()));
        }
        if (agents.isEmpty()) {
            log.info("No eligible agents for call {} — straight to voicemail", callUuid);
            inner.append(fallback);
        } else if ("sequential".equals(mode)) {
            Agent first = agents.get(0);
            callDao.appendDialedAgent(callUuid, first.getId());
            String action = signer.sign(publicBaseUrl + "/voice/dial-step?call_uuid=" + enc(callUuid) + "&step=1");
            inner.append(VobizXml.dial(List.of(first.getPhoneE164()), m1, ringTimeout, action, true));
            inner.append(fallback);
        } else {
            for (Agent a : agents) callDao.appendDialedAgent(callUuid, a.getId());
            List<String> phones = agents.stream().map(Agent::getPhoneE164).toList();
            // redirect=false: if the Dial fails, execution continues into the voicemail fallback below.
            inner.append(VobizXml.dial(phones, m1, ringTimeout, signer.sign(publicBaseUrl + "/voice/dial-result"), false));
            inner.append(fallback);
        }
        return VobizXml.response(inner.toString());
    }

    /** POST /voice/dial-result — action callback of the simultaneous Dial (logging only). */
    public HttpResult voiceDialResult(Map<String, String> p) {
        String callUuid = Forms.firstNonBlank(p, "CallUUID", "call_uuid");
        if (callUuid == null) return HttpResult.xml(200, VobizXml.emptyResponse());
        String dialStatus = p.getOrDefault("DialStatus", "");
        if ("answered".equalsIgnoreCase(dialStatus)) {
            List<Long> dialed = callDao.getDialedAgents(callUuid);
            Long agentId = dialed.size() == 1 ? dialed.get(0) : null;
            callDao.markAnswered(callUuid, agentId);
            log.info("Call {} answered (dialed_agents={}, agent_id={})", callUuid, dialed, agentId);
        } else {
            callDao.setStatus(callUuid, "no_answer", p.get("DialHangupCause"));
            log.info("Call {} not answered (DialStatus={}, cause={})", callUuid, dialStatus, p.get("DialHangupCause"));
        }
        return HttpResult.xml(200, VobizXml.emptyResponse());
    }

    /** POST /voice/dial-step?call_uuid=..&step=n — one Dial of the sequential chain; returns the next XML. */
    public HttpResult voiceDialStep(Map<String, String> p) {
        String callUuid = Forms.firstNonBlank(p, "call_uuid", "CallUUID");
        int step = parseInt(p.get("step"), 1);
        if (callUuid == null) return HttpResult.xml(200, VobizXml.emptyResponse());
        String dialStatus = p.getOrDefault("DialStatus", "");
        if ("answered".equalsIgnoreCase(dialStatus)) {
            List<Long> dialed = callDao.getDialedAgents(callUuid);
            Long agentId = (step >= 1 && step <= dialed.size()) ? dialed.get(step - 1)
                    : (dialed.size() == 1 ? dialed.get(0) : null);
            callDao.markAnswered(callUuid, agentId);
            log.info("Call {} answered at step {} by agent {}", callUuid, step, agentId);
            return HttpResult.xml(200, VobizXml.emptyResponse());
        }
        List<Agent> agents = routing.eligibleAgents();
        if (step < agents.size()) {
            Agent next = agents.get(step);
            callDao.appendDialedAgent(callUuid, next.getId());
            String m1 = settings.m1(defaultM1);
            String action = signer.sign(publicBaseUrl + "/voice/dial-step?call_uuid=" + enc(callUuid) + "&step=" + (step + 1));
            String inner = VobizXml.dial(List.of(next.getPhoneE164()), m1, settings.ringTimeoutSec(), action, true)
                    + voicemailFallback();
            return HttpResult.xml(200, VobizXml.response(inner));
        }
        callDao.setStatus(callUuid, "no_answer", p.get("DialHangupCause"));
        log.info("Call {} exhausted all agents at step {}", callUuid, step);
        return HttpResult.xml(200, VobizXml.response(voicemailFallback()));
    }

    /** POST /voice/recording-done — Record callback: store the voicemail, open a callback request. */
    public HttpResult voiceRecordingDone(Map<String, String> p) {
        String callUuid = Forms.firstNonBlank(p, "CallUUID", "call_uuid");
        if (callUuid == null) return HttpResult.xml(200, VobizXml.emptyResponse());
        String recUrl = Forms.firstNonBlank(p, "RecordingUrl", "RecordingURL", "RecordUrl", "RecordFile", "recording_url");
        Integer dur = parseIntOrNull(Forms.firstNonBlank(p, "RecordingDuration", "Duration"));
        CallRecord call = callDao.findByUuid(callUuid).orElse(null);
        if (call == null) {
            log.warn("Recording callback for unknown call {}", callUuid);
        } else {
            callDao.markVoicemail(callUuid, recUrl);
            CallbackRequest cb = callbackDao.create(call.getId(), call.getCallerNumber(), recUrl, dur);
            log.info("Voicemail stored for call {} ({}s) -> callback request #{}", callUuid, dur, cb.getId());
        }
        return HttpResult.xml(200, VobizXml.emptyResponse());
    }

    /** POST /voice/hangup — final event: duration + cause (logging); closes outbound callback legs. */
    public HttpResult voiceHangup(Map<String, String> p) {
        String callUuid = Forms.firstNonBlank(p, "CallUUID", "call_uuid");
        if (callUuid == null) return HttpResult.xml(200, VobizXml.emptyResponse());
        Integer dur = parseIntOrNull(Forms.firstNonBlank(p, "CallDuration", "Duration"));
        String cause = Forms.firstNonBlank(p, "HangupCause", "HangupCauseCode", "hangup_cause");
        CallRecord call = callDao.findByUuid(callUuid).orElse(null);
        if (call != null) {
            callDao.finalizeHangup(callUuid, dur, cause);
            if ("outbound".equals(call.getDirection()) && call.getCallbackId() != null) {
                callbackDao.markCalledBack(call.getCallbackId());
                log.info("Outbound callback call {} finished (cause={}) -> callback #{} called_back",
                        callUuid, cause, call.getCallbackId());
            }
        }
        return HttpResult.xml(200, VobizXml.emptyResponse());
    }

    /** GET /voice/outbound-answer?to=..&callback_id=.. — agent picked up the outbound leg; bridge to the customer. */
    public HttpResult voiceOutboundAnswer(Map<String, String> p) {
        String to = Forms.firstNonBlank(p, "to", "To");
        if (to == null) return HttpResult.xml(400, VobizXml.emptyResponse());
        String m1 = settings.m1(defaultM1);
        log.info("Bridging outbound leg to customer {}", mask(to));
        return HttpResult.xml(200, VobizXml.bridgeOutbound(m1, to));
    }

    private String voicemailFallback() {
        return VobizXml.voicemailFallback(
                settings.greetingText(), settings.closingText(),
                signer.sign(publicBaseUrl + "/voice/recording-done"),
                settings.voicemailMaxSec(), settings.silenceTimeoutSec());
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    static int parseInt(String s, int def) {
        try {
            return (s == null || s.isBlank()) ? def : Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    static Integer parseIntOrNull(String s) {
        try {
            return (s == null || s.isBlank()) ? null : Integer.valueOf(s.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Mask a phone number in logs (DPDP: minimise personal data in log files). */
    static String mask(String phone) {
        if (phone == null || phone.length() < 5) return "***";
        return phone.substring(0, 4) + "****" + phone.substring(phone.length() - 2);
    }
}
