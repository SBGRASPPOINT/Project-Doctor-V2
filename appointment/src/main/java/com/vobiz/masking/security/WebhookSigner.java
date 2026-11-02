package com.vobiz.masking.security;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * Signs the webhook URLs this service hands to Vobiz (Dial actions, Gather actions, Record
 * callbacks, outbound answer/hangup URLs) by appending the shared {@code app.webhook-secret}.
 *
 * {@link AuthFilter} rejects an unsigned/incorrectly signed {@code /voice/**} request when a
 * secret is configured, so a third party cannot drive the IVR (book/cancel) or feed fake
 * recordings and hangup events. With no secret configured (local development) this is a no-op.
 */
public class WebhookSigner {

    /** Query parameter carrying the secret. */
    public static final String PARAM = "secret";
    /** Header alternative, useful when a query string is not available. */
    public static final String HEADER = "X-Webhook-Secret";

    private final String secret;

    public WebhookSigner(String secret) {
        this.secret = secret == null ? "" : secret.trim();
    }

    public boolean isEnabled() {
        return !secret.isEmpty();
    }

    /** Append the secret as a query parameter (no-op when unconfigured). */
    public String sign(String url) {
        if (!isEnabled() || url == null || url.isEmpty()) return url;
        String encoded = URLEncoder.encode(secret, StandardCharsets.UTF_8);
        return url + (url.contains("?") ? "&" : "?") + PARAM + "=" + encoded;
    }

    /** Constant-time comparison, so a wrong secret cannot be discovered by timing. */
    public boolean matches(String candidate) {
        if (!isEnabled()) return true;
        if (candidate == null) return false;
        byte[] a = secret.getBytes(StandardCharsets.UTF_8);
        byte[] b = candidate.getBytes(StandardCharsets.UTF_8);
        return java.security.MessageDigest.isEqual(a, b);
    }
}
