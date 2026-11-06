package com.vobiz.masking.vobiz;

import com.vobiz.masking.config.VobizProperties;
import com.vobiz.masking.util.ApiException;
import com.vobiz.masking.util.Json;
import com.vobiz.masking.whatsapp.WhatsAppTransport;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/** Vobiz WhatsApp Messaging API adapter; all network calls use Vobiz account credentials. */
public class VobizWhatsAppClient implements WhatsAppTransport {

    private static final Logger log = LogManager.getLogger(VobizWhatsAppClient.class);
    private static final Pattern E164 = Pattern.compile("^\\+[1-9]\\d{6,14}$");
    private static final Pattern GRAPHLESS_ID = Pattern.compile("[A-Za-z0-9_.:-]{1,180}");

    private final String authId;
    private final String authToken;
    private final String channelId;
    private final String wabaId;
    private final String apiBase;
    private final boolean mock;
    private final HttpClient http;

    public VobizWhatsAppClient(VobizProperties properties) {
        this.authId = clean(properties.getAuthId());
        this.authToken = clean(properties.getAuthToken());
        this.channelId = clean(properties.getWhatsappChannelId());
        this.wabaId = clean(properties.getWhatsappWabaId());
        this.apiBase = clean(properties.getApiBase());
        this.mock = properties.isMock() || authId.isEmpty() || authToken.isEmpty();
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    @Override
    public boolean isMock() {
        return mock;
    }

    @Override
    public boolean isConfigured() {
        return mock || (!authId.isEmpty() && !authToken.isEmpty()
                && validId(channelId) && validId(wabaId));
    }

    @Override
    public String sendText(String recipientE164, String text) {
        if (mock) {
            log.info("[Vobiz WhatsApp mock] accepted outbound text (delivery disabled)");
            return "vobiz.mock." + UUID.randomUUID();
        }
        if (!isConfigured())
            throw new ApiException(503, "Vobiz WhatsApp is not configured; set the Vobiz channel and WABA IDs.");
        if (recipientE164 == null || !E164.matcher(recipientE164).matches())
            throw new ApiException(400, "Recipient WhatsApp number must be in E.164 format.");

        Map<String, Object> textObject = new LinkedHashMap<>();
        textObject.put("body", text);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("channel_id", channelId);
        payload.put("waba_id", wabaId);
        payload.put("to", recipientE164);
        payload.put("type", "text");
        payload.put("text", textObject);

        HttpRequest request = HttpRequest.newBuilder(messagesUri())
                .timeout(Duration.ofSeconds(20))
                .header("X-Auth-ID", authId)
                .header("X-Auth-Token", authToken)
                .header("Content-Type", "application/json; charset=UTF-8")
                .POST(HttpRequest.BodyPublishers.ofString(Json.write(payload), StandardCharsets.UTF_8))
                .build();
        try {
            HttpResponse<String> response = http.send(request,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                log.warn("Vobiz WhatsApp send rejected (HTTP {})", response.statusCode());
                throw new ApiException(502, "Vobiz could not send this WhatsApp message. Check the channel, recipient, and messaging window.");
            }
            String messageId = findMessageId(response.body());
            if (messageId == null) log.warn("Vobiz accepted a WhatsApp send without returning a message ID");
            return messageId;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ApiException(502, "Vobiz WhatsApp send was interrupted. Please try again.");
        } catch (java.io.IOException e) {
            log.warn("Vobiz WhatsApp API is unreachable: {}", e.getClass().getSimpleName());
            throw new ApiException(502, "Vobiz WhatsApp is temporarily unreachable. Please try again.");
        }
    }

    private URI messagesUri() {
        if (apiBase.isEmpty()) throw new ApiException(503, "VOBIZ_API_BASE is not configured.");
        try {
            URI base = URI.create(apiBase);
            if (!"https".equalsIgnoreCase(base.getScheme())
                    || !"api.vobiz.ai".equalsIgnoreCase(base.getHost())
                    || (base.getPort() != -1 && base.getPort() != 443)
                    || base.getUserInfo() != null || base.getQuery() != null || base.getFragment() != null
                    || (base.getPath() != null && !base.getPath().isEmpty() && !"/".equals(base.getPath())))
                throw new ApiException(503, "Vobiz API base must be https://api.vobiz.ai.");
            return URI.create(apiBase.replaceAll("/+$", "") + "/api/v1/messaging/messages");
        } catch (IllegalArgumentException e) {
            throw new ApiException(503, "Vobiz API base URL is invalid.");
        }
    }

    private static String findMessageId(String responseBody) {
        if (responseBody == null || responseBody.isBlank()) return null;
        try {
            Map<String, Object> root = Json.parseObject(responseBody);
            String id = idFrom(root);
            if (id != null) return id;
            Object data = root.get("data");
            if (data instanceof Map<?, ?> map) {
                id = idFrom(map);
                if (id != null) return id;
                Object message = map.get("message");
                if (message instanceof Map<?, ?> messageMap) {
                    id = idFrom(messageMap);
                    if (id != null) return id;
                }
                Object messages = map.get("messages");
                if (messages instanceof List<?> list && !list.isEmpty() && list.get(0) instanceof Map<?, ?> first) {
                    id = idFrom(first);
                    if (id != null) return id;
                }
            }
            return null;
        } catch (RuntimeException e) {
            // A 2xx response still means Vobiz accepted the send; the response schema may omit an ID.
            return null;
        }
    }

    private static String idFrom(Map<?, ?> map) {
        for (String key : List.of("message_id", "id", "wamid")) {
            Object value = map.get(key);
            if (value != null) {
                String candidate = String.valueOf(value).trim();
                if (GRAPHLESS_ID.matcher(candidate).matches()) return candidate;
            }
        }
        return null;
    }

    private static boolean validId(String value) {
        return value != null && GRAPHLESS_ID.matcher(value).matches();
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }
}
