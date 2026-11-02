package com.vobiz.masking.security;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The shared-secret that keeps /voice/** webhooks from being driven by anyone but Vobiz. */
class WebhookSignerTest {

    @Test
    void disabledWhenNoSecretConfigured() {
        WebhookSigner signer = new WebhookSigner("");
        assertFalse(signer.isEnabled());
        assertEquals("https://host/voice/answer", signer.sign("https://host/voice/answer"));
        // Fail-open only when unconfigured: every candidate matches.
        assertTrue(signer.matches(null));
    }

    @Test
    void appendsSecretWithTheRightSeparator() {
        WebhookSigner signer = new WebhookSigner("s3cret");
        assertTrue(signer.isEnabled());
        assertEquals("https://host/voice/dial-result?secret=s3cret",
                signer.sign("https://host/voice/dial-result"));
        assertEquals("https://host/voice/ivr/menu?call_uuid=abc&secret=s3cret",
                signer.sign("https://host/voice/ivr/menu?call_uuid=abc"));
    }

    @Test
    void comparesInConstantTimeAndRejectsWrongSecrets() {
        WebhookSigner signer = new WebhookSigner("s3cret");
        assertTrue(signer.matches("s3cret"));
        assertFalse(signer.matches("s3cres"));
        assertFalse(signer.matches(""));
        assertFalse(signer.matches(null));
    }

    @Test
    void escapesSecretsThatNeedUrlEncoding() {
        WebhookSigner signer = new WebhookSigner("a b&c");
        assertEquals("https://host/voice/answer?secret=a+b%26c",
                signer.sign("https://host/voice/answer"));
    }
}
