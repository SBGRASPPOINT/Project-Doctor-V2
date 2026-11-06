package com.vobiz.masking.whatsapp.web;

import com.vobiz.masking.security.WebhookSigner;
import com.vobiz.masking.util.ApiException;
import com.vobiz.masking.util.Json;
import com.vobiz.masking.whatsapp.WhatsAppService;
import jakarta.servlet.http.HttpServletRequest;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/** Vobiz WhatsApp inbound-message and delivery-status callbacks. */
@RestController
@RequestMapping("/webhooks/vobiz/whatsapp")
public class WhatsAppWebhookController {

    private static final Logger log = LogManager.getLogger(WhatsAppWebhookController.class);
    private static final int MAX_BODY_BYTES = 1024 * 1024;

    private final WebhookSigner webhookSigner;
    private final WhatsAppService whatsapp;

    public WhatsAppWebhookController(@Qualifier("vobizWhatsappWebhookSigner") WebhookSigner webhookSigner,
                                     WhatsAppService whatsapp) {
        this.webhookSigner = webhookSigner;
        this.whatsapp = whatsapp;
    }

    @PostMapping
    public ResponseEntity<?> receive(HttpServletRequest request) {
        if (!webhookSigner.isEnabled()) {
            return ResponseEntity.status(503).contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("error", "Configure VOBIZ_WHATSAPP_WEBHOOK_SECRET before enabling Vobiz messaging callbacks."));
        }
        if (!authorized(request)) return unauthorized();

        byte[] body;
        try {
            body = readCapped(request.getInputStream());
        } catch (IOException e) {
            log.warn("Could not read Vobiz WhatsApp webhook body: {}", e.getClass().getSimpleName());
            return ResponseEntity.badRequest().contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("error", "Invalid webhook request."));
        }
        if (body == null) {
            return ResponseEntity.status(413).contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("error", "Vobiz webhook body is too large."));
        }
        try {
            Object parsed = Json.parse(new String(body, StandardCharsets.UTF_8));
            if (!(parsed instanceof Map<?, ?> raw))
                return ResponseEntity.badRequest().contentType(MediaType.APPLICATION_JSON)
                        .body(Map.of("error", "Expected a Vobiz webhook object."));
            Map<String, Object> payload = new java.util.LinkedHashMap<>();
            raw.forEach((key, value) -> payload.put(String.valueOf(key), value));
            whatsapp.receiveWebhook(payload);
            return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(Map.of("received", true));
        } catch (ApiException e) {
            return ResponseEntity.status(e.getStatus()).contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            // Return non-2xx for transient failures so Vobiz can retry; never log message contents.
            log.error("Vobiz WhatsApp webhook processing failed", e);
            return ResponseEntity.internalServerError().contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("error", "Webhook processing failed."));
        }
    }

    private boolean authorized(HttpServletRequest request) {
        if (!webhookSigner.isEnabled()) return false;
        String presented = request.getHeader(WebhookSigner.HEADER);
        if (presented == null || presented.isBlank()) presented = request.getParameter(WebhookSigner.PARAM);
        return webhookSigner.matches(presented);
    }

    private static ResponseEntity<?> unauthorized() {
        return ResponseEntity.status(401).contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("error", "Invalid Vobiz webhook secret."));
    }

    private static byte[] readCapped(InputStream input) throws IOException {
        try (InputStream in = input) {
            byte[] body = in.readNBytes(MAX_BODY_BYTES + 1);
            return body.length > MAX_BODY_BYTES ? null : body;
        }
    }
}
