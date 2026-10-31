package com.vobiz.masking.web;

import com.vobiz.masking.util.Forms;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * Internal REST API for the ops console (served as static files from /static).
 * All dispatch lives in {@link Handlers#handleApi} so it is unit-testable without Spring.
 */
@RestController
public class ApiController {

    private final Handlers handlers;

    public ApiController(Handlers handlers) {
        this.handlers = handlers;
    }

    @GetMapping("/health")
    public ResponseEntity<String> health() {
        return toResponse(handlers.handleApi("GET", "/api/health", Map.of(), ""));
    }

    @RequestMapping("/api/**")
    public ResponseEntity<String> api(HttpServletRequest request) throws IOException {
        String body = switch (request.getMethod()) {
            case "GET", "HEAD", "DELETE" -> "";
            default -> new String(request.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        };
        HttpResult r = handlers.handleApi(request.getMethod(), request.getRequestURI(),
                Forms.parse(request.getQueryString()), body);
        return toResponse(r);
    }

    private static ResponseEntity<String> toResponse(HttpResult r) {
        return ResponseEntity.status(r.status())
                .contentType(MediaType.parseMediaType(r.contentType()))
                .body(r.body());
    }
}
