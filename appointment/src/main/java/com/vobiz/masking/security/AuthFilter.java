package com.vobiz.masking.security;

import com.vobiz.masking.config.AppProperties;
import com.vobiz.masking.util.Json;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;

/**
 * Puts a lock on both halves of the HTTP surface.
 *
 * <ul>
 *   <li><b>/api/**</b> (patient list, appointment CRUD, settings, "place a call now") requires
 *       {@code Authorization: Bearer <app.api-token>}. {@code /api/health} stays open so a load
 *       balancer can probe it.</li>
 *   <li><b>/voice/**</b> (the Vobiz webhooks that book, cancel, set recordings and hangup
 *       outcomes) requires the {@code app.webhook-secret} via {@code ?secret=} or the
 *       {@code X-Webhook-Secret} header. Every URL this service generates is signed by
 *       {@link WebhookSigner}.</li>
 *   <li>Static console files and {@code /health} stay public; the console prompts for the API
 *       token and sends it as a bearer header.</li>
 * </ul>
 *
 * Both checks are skipped when the corresponding value is not configured (local development),
 * and {@code ConfigValidator} logs a loud warning in that case. This is deliberately a plain
 * servlet filter rather than Spring Security: the console is a single-operator tool and this
 * keeps the webhook path dependency-free — swap in Spring Security when roles/audit are needed.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class AuthFilter extends OncePerRequestFilter {

    private static final Logger log = LogManager.getLogger(AuthFilter.class);

    private final String apiToken;
    private final WebhookSigner webhookSigner;

    public AuthFilter(AppProperties props, WebhookSigner webhookSigner) {
        this.apiToken = props.getApiToken() == null ? "" : props.getApiToken().trim();
        this.webhookSigner = webhookSigner;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String path = request.getRequestURI();
        String method = request.getMethod();

        if ("OPTIONS".equals(method)) {                 // CORS preflight never carries credentials
            chain.doFilter(request, response);
            return;
        }

        if (path.startsWith("/api/") && !path.equals("/api/health")) {
            if (!apiToken.isEmpty() && !bearerMatches(request.getHeader("Authorization"))) {
                log.warn("Rejected unauthenticated {} {} from {}", method, path, request.getRemoteAddr());
                denyApi(response, "Missing or invalid API token (Authorization: Bearer …)");
                return;
            }
        } else if (path.startsWith("/voice/")) {
            if (webhookSigner.isEnabled()) {
                String presented = request.getHeader(WebhookSigner.HEADER);
                if (presented == null || presented.isBlank()) presented = request.getParameter(WebhookSigner.PARAM);
                if (!webhookSigner.matches(presented)) {
                    log.warn("Rejected unsigned webhook {} {} from {}", method, path, request.getRemoteAddr());
                    denyVoice(response);
                    return;
                }
            }
        }

        chain.doFilter(request, response);
    }

    private boolean bearerMatches(String header) {
        if (header == null) return false;
        String prefix = "Bearer ";
        if (!header.regionMatches(true, 0, prefix, 0, prefix.length())) return false;
        return MessageDigest.isEqual(
                apiToken.getBytes(StandardCharsets.UTF_8),
                header.substring(prefix.length()).trim().getBytes(StandardCharsets.UTF_8));
    }

    private void denyApi(HttpServletResponse response, String message) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/json; charset=UTF-8");
        response.setHeader("WWW-Authenticate", "Bearer realm=\"ops-console\"");
        response.getWriter().write(Json.write(Map.of("error", message)));
    }

    /** Voice: answer with a well-formed (empty) Response so the call ends cleanly, not with a retry loop. */
    private void denyVoice(HttpServletResponse response) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("text/xml; charset=UTF-8");
        response.getWriter().write("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<Response/>");
    }
}
