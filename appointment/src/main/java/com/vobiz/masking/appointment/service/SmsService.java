package com.vobiz.masking.appointment.service;

/**
 * SMS fallback seam. Voice calls are the primary reminder channel; SMS is only used as a
 * fallback when a reminder call is unanswered twice (setting {@code sms_fallback_enabled}).
 * No paid provider is wired — the default bean just logs, so the seam exists without cost.
 */
@FunctionalInterface
public interface SmsService {

    void send(String phoneE164, String text);
}
