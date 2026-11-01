package com.vobiz.masking.web;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Receives all Vobiz voice webhooks (form-encoded POSTs, plus GET for outbound-answer)
 * and returns Vobiz XML. All routing logic lives in {@link Handlers#handleVoice}, which
 * is unit-testable without Spring.
 *
 * Configure these URLs on the Vobiz Application attached to M1:
 *   Answer URL:  <public-base-url>/voice/answer
 *   Hangup URL:  <public-base-url>/voice/hangup
 * The Dial action / Record callback URLs are generated from public-base-url automatically.
 */
@RestController
public class VoiceWebhookController {

    private final Handlers handlers;

    public VoiceWebhookController(Handlers handlers) {
        this.handlers = handlers;
    }

    @RequestMapping("/voice/**")
    public ResponseEntity<String> voice(HttpServletRequest request) {
        Map<String, String> params = new LinkedHashMap<>();
        request.getParameterMap().forEach((k, v) -> params.put(k, v != null && v.length > 0 ? v[0] : ""));
        HttpResult r = handlers.handleVoice(request.getMethod(), request.getRequestURI(), params);
        return ResponseEntity.status(r.status())
                .contentType(MediaType.parseMediaType(r.contentType()))
                .body(r.body());
    }
}
