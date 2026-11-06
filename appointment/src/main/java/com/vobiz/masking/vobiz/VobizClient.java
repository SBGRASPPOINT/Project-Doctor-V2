package com.vobiz.masking.vobiz;

import com.vobiz.masking.util.ApiException;
import com.vobiz.masking.util.Json;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Thin client for the Vobiz REST API (used for outbound click-to-call).
 *
 * Credentials travel only as X-Auth-ID / X-Auth-Token headers and never touch code
 * or the database. The XML webhooks themselves need no auth — that is why the
 * Answer URL must be public HTTPS.
 *
 * Mock mode (default until VOBIZ_AUTH_ID/VOBIZ_AUTH_TOKEN are set): the call payload
 * is logged and a fake CallUUID is returned, so the whole flow can be exercised
 * end-to-end without a Vobiz account.
 */
public class VobizClient {

    private static final Logger log = LogManager.getLogger(VobizClient.class);
    private static final Pattern CALL_UUID = Pattern.compile("\"call_uuid\"\\s*:\\s*\"([^\"]+)\"");

    private final String authId;
    private final String authToken;
    private final String apiBase;
    private final boolean mock;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    public VobizClient(String authId, String authToken, String apiBase, boolean mock) {
        this.authId = authId == null ? "" : authId.trim();
        this.authToken = authToken == null ? "" : authToken.trim();
        this.apiBase = (apiBase == null || apiBase.isBlank())
                ? "https://api.vobiz.ai"
                : apiBase.trim().replaceAll("/+$", "");
        this.mock = mock || this.authId.isEmpty() || this.authToken.isEmpty();
    }

    public boolean isMock() {
        return mock;
    }

    /**
     * POST /api/v1/Account/{auth_id}/Call/ — places an outbound call.
     *
     * @return the new CallUUID
     */
    public String placeCall(String from, String to, String answerUrl, String hangupUrl) {
        if (mock) {
            String fake = "mock-" + UUID.randomUUID();
            log.info("[MOCK Vobiz] placeCall from={} to={} answer_url={} hangup_url={} -> {}",
                    from, to, answerUrl, hangupUrl, fake);
            return fake;
        }
        String url = apiBase + "/api/v1/Account/" + authId + "/Call/";
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("from", from);                 // M1 — the customer sees the familiar number
        payload.put("to", to);                     // the agent's real number
        payload.put("answer_url", answerUrl);      // fired when the agent picks up
        payload.put("answer_method", "POST");
        payload.put("hangup_url", hangupUrl);
        payload.put("hangup_method", "POST");
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(15))
                    .header("X-Auth-ID", authId)
                    .header("X-Auth-Token", authToken)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(Json.write(payload)))
                    .build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() / 100 != 2) {
                log.error("Vobiz placeCall failed: HTTP {} {}", resp.statusCode(), resp.body());
                throw new ApiException(502, "Vobiz API error HTTP " + resp.statusCode());
            }
            Matcher m = CALL_UUID.matcher(resp.body());
            if (m.find()) return m.group(1);
            log.warn("Vobiz response had no call_uuid: {}", resp.body());
            throw new ApiException(502, "Vobiz response missing call_uuid");
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            throw new ApiException(502, "Vobiz API unreachable: " + e.getMessage());
        }
    }
}
