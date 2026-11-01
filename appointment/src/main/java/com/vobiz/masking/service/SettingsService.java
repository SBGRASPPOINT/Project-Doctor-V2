package com.vobiz.masking.service;

import com.vobiz.masking.repo.SettingsDao;
import com.vobiz.masking.util.ApiException;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** Typed access to the settings table + validated updates from the ops console. */
public class SettingsService {

    public static final Set<String> KEYS = Set.of(
            "m1_number", "ring_timeout_sec", "voicemail_max_sec", "record_silence_timeout_sec",
            "routing_mode", "respect_presence", "announce_recording",
            "announcement_text", "greeting_text", "closing_text");

    private static final Pattern E164 = Pattern.compile("^\\+[1-9]\\d{6,14}$");

    private final SettingsDao dao;

    public SettingsService(SettingsDao dao) {
        this.dao = dao;
    }

    public Map<String, String> getAll() {
        return dao.getAll();
    }

    public String m1(String fallback) {
        String v = dao.get("m1_number", null);
        return (v == null || v.isBlank()) ? fallback : v;
    }

    public int ringTimeoutSec() {
        return dao.getInt("ring_timeout_sec", 20);
    }

    public int voicemailMaxSec() {
        return dao.getInt("voicemail_max_sec", 30);
    }

    public int silenceTimeoutSec() {
        return dao.getInt("record_silence_timeout_sec", 5);
    }

    public String routingMode() {
        return dao.get("routing_mode", "simultaneous");
    }

    public boolean respectPresence() {
        return dao.getBool("respect_presence", false);
    }

    public boolean announceRecording() {
        return dao.getBool("announce_recording", true);
    }

    public String announcementText() {
        return dao.get("announcement_text", "This call may be recorded for quality and training purposes.");
    }

    public String greetingText() {
        return dao.get("greeting_text",
                "Sorry, none of our team is free right now. Please tell us your issue after the beep, and someone will call you back.");
    }

    public String closingText() {
        return dao.get("closing_text", "Thank you. We will call you back shortly.");
    }

    public Map<String, String> update(Map<String, Object> body) {
        if (body == null || body.isEmpty()) throw new ApiException(400, "Empty settings payload");
        for (Map.Entry<String, Object> e : body.entrySet()) {
            String key = e.getKey();
            if (!KEYS.contains(key)) throw new ApiException(400, "Unknown setting: " + key);
            String value = e.getValue() == null ? "" : String.valueOf(e.getValue());
            validate(key, value);
            dao.put(key, value);
        }
        return getAll();
    }

    private static void validate(String key, String value) {
        switch (key) {
            case "m1_number" -> {
                if (!E164.matcher(value).matches())
                    throw new ApiException(400, "m1_number must be E.164 format, e.g. +919876500000");
            }
            case "ring_timeout_sec" -> intIn(key, value, 5, 120);
            case "voicemail_max_sec" -> intIn(key, value, 5, 120);
            case "record_silence_timeout_sec" -> intIn(key, value, 1, 30);
            case "routing_mode" -> {
                if (!value.equals("simultaneous") && !value.equals("sequential"))
                    throw new ApiException(400, "routing_mode must be 'simultaneous' or 'sequential'");
            }
            case "respect_presence", "announce_recording" -> {
                if (!value.equalsIgnoreCase("true") && !value.equalsIgnoreCase("false"))
                    throw new ApiException(400, key + " must be 'true' or 'false'");
            }
            case "announcement_text", "greeting_text", "closing_text" -> {
                if (value.length() > 500) throw new ApiException(400, key + " is too long (max 500 chars)");
            }
            // --- appointment / IVR / reminder keys ---
            case "ivr_enabled", "allow_self_booking", "reminder_t24h_enabled", "reminder_t2h_enabled",
                 "sms_fallback_enabled", "change_notification_enabled" -> {
                if (!value.equalsIgnoreCase("true") && !value.equalsIgnoreCase("false"))
                    throw new ApiException(400, key + " must be 'true' or 'false'");
            }
            case "ivr_max_hops" -> intIn(key, value, 1, 50);
            case "booking_horizon_days" -> intIn(key, value, 1, 365);
            case "min_booking_lead_minutes" -> intIn(key, value, 0, 10080);
            case "max_slots_offered" -> intIn(key, value, 1, 10);
            case "slot_granularity_min" -> intIn(key, value, 5, 120);
            case "allow_self_cancel_hours" -> intIn(key, value, 0, 168);
            case "reminder_t24h_lead_minutes" -> intIn(key, value, 60, 10080);
            case "reminder_t2h_lead_minutes" -> intIn(key, value, 5, 1440);
            case "reminder_retry_count" -> intIn(key, value, 0, 5);
            case "reminder_retry_gap_minutes" -> intIn(key, value, 5, 1440);
            case "reminder_scan_ms" -> intIn(key, value, 5000, 600000);
            case "reminder_quiet_start", "reminder_quiet_end" -> {
                if (!HH_MM.matcher(value.trim()).matches())
                    throw new ApiException(400, key + " must be HH:mm (24h), e.g. 08:00");
            }
            case "clinic_timezone" -> {
                try {
                    ZoneId.of(value.trim());
                } catch (Exception e) {
                    throw new ApiException(400, "clinic_timezone must be an IANA zone, e.g. Asia/Kolkata");
                }
            }
            case "clinic_name" -> {
                if (value.isBlank() || value.length() > 120)
                    throw new ApiException(400, "clinic_name must be 1..120 characters");
            }
            default -> {
                // no validation
            }
        }
    }

    private static void intIn(String key, String value, int min, int max) {
        try {
            int v = Integer.parseInt(value.trim());
            if (v < min || v > max)
                throw new ApiException(400, key + " must be between " + min + " and " + max);
        } catch (NumberFormatException e) {
            throw new ApiException(400, key + " must be an integer");
        }
    }
}
