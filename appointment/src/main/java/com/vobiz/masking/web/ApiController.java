package com.vobiz.masking.web;

import com.vobiz.masking.util.Forms;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * Internal REST API for the ops console (served as static files from /static).
 * All dispatch lives in {@link Handlers#handleApi} so it is unit-testable without Spring.
 *
 * The body is read with a hard size cap: {@code readAllBytes()} on the raw stream would let any
 * caller make the JVM allocate an arbitrary amount of memory. Malformed already-decoded query
 * strings are turned into a 400 here, because they are parsed before {@link Handlers} can catch them.
 */
@RestController
public class ApiController {

    /** Largest accepted JSON body. The biggest real payload is a settings map or a time-off block. */
    static final int MAX_BODY_BYTES = 256 * 1024;

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
            default -> readCapped(request);
        };
        if (body == null) {
            return ResponseEntity.status(413)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body("{\"error\":\"Request body too large (max " + MAX_BODY_BYTES + " bytes)\"}");
        }
        Map<String, String> query;
        try {
            query = Forms.parse(request.getQueryString());
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest()
                    .contentType(MediaType.APPLICATION_JSON)
                    .body("{\"error\":\"Malformed query string\"}");
        }
        HttpResult r = handlers.handleApi(request.getMethod(), request.getRequestURI(), query, body);
        return toResponse(r);
    }

    /** @return the body, or {@code null} when it exceeds {@link #MAX_BODY_BYTES}. */
    private static String readCapped(HttpServletRequest request) throws IOException {
        try (InputStream in = request.getInputStream()) {
            byte[] bytes = in.readNBytes(MAX_BODY_BYTES + 1);
            if (bytes.length > MAX_BODY_BYTES) return null;
            return new String(bytes, StandardCharsets.UTF_8);
        }
    }

    private static ResponseEntity<String> toResponse(HttpResult r) {
        return ResponseEntity.status(r.status())
                .contentType(MediaType.parseMediaType(r.contentType()))
                .body(r.body());
    }
}
