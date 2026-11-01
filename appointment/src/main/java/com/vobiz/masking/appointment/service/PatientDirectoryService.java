package com.vobiz.masking.appointment.service;

import com.vobiz.masking.appointment.model.Patient;
import com.vobiz.masking.appointment.repo.PatientRepository;
import com.vobiz.masking.util.ApiException;
import org.springframework.data.domain.PageRequest;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Patient directory. On an inbound call the patient is identified purely by caller ID
 * ({@link #resolveByPhone}). Unknown callers who book become patient rows with
 * {@code patient_ref = null}; {@code patient_ref} optionally links to the Node/MySQL
 * app's {@code Patients.patient_id} (one-way, by opaque reference — no cross-DB FKs).
 */
public class PatientDirectoryService {

    private static final Pattern E164 = Pattern.compile("^\\+[1-9]\\d{6,14}$");
    private static final List<String> LANGS = List.of("en", "hi", "mr", "ta", "te", "kn", "bn", "gu");

    private final PatientRepository repo;

    public PatientDirectoryService(PatientRepository repo) {
        this.repo = repo;
    }

    /** The IVR identity step: caller ID → patient. */
    public Optional<Patient> resolveByPhone(String e164) {
        if (e164 == null || e164.isBlank()) return Optional.empty();
        return repo.findByPhoneE164(e164.trim());
    }

    /** Register a caller we have never seen before (they chose to book). */
    public Patient registerUnknownCaller(String phoneE164, String name) {
        return repo.findByPhoneE164(phoneE164.trim()).orElseGet(() -> {
            Patient p = new Patient();
            p.setPhoneE164(phoneE164.trim());
            p.setName((name == null || name.isBlank()) ? "Caller " + tail(phoneE164) : name.trim());
            p.setPreferredLanguage("en");
            p.setActive(true);
            return repo.save(p);
        });
    }

    public Patient get(long id) {
        return repo.findById(id).orElseThrow(() -> new ApiException(404, "Patient not found: " + id));
    }

    public List<Patient> list(String q, int limit) {
        if (q == null || q.isBlank()) return repo.findAll(PageRequest.of(0, Math.min(limit, 500))).getContent();
        return repo.search(q.trim(), PageRequest.of(0, Math.min(limit, 500)));
    }

    public Patient create(Map<String, Object> body) {
        String name = reqString(body, "name", 1, 120);
        String phone = reqString(body, "phone_e164", 1, 32);
        if (!E164.matcher(phone).matches())
            throw new ApiException(400, "phone_e164 must be E.164 format, e.g. +919876543210");
        if (repo.findByPhoneE164(phone).isPresent())
            throw new ApiException(409, "Phone already registered: " + phone);
        Patient p = new Patient();
        p.setName(name);
        p.setPhoneE164(phone);
        p.setPatientRef(optString(body, "patient_ref", 64));
        p.setPreferredLanguage(optLang(body));
        p.setDnd(optBool(body, "dnd", false));
        p.setNotes(optString(body, "notes", 2000));
        p.setActive(true);
        return repo.save(p);
    }

    public Patient update(long id, Map<String, Object> body) {
        Patient p = get(id);
        if (body.containsKey("name")) p.setName(reqString(body, "name", 1, 120));
        if (body.containsKey("phone_e164")) {
            String phone = reqString(body, "phone_e164", 1, 32);
            if (!E164.matcher(phone).matches())
                throw new ApiException(400, "phone_e164 must be E.164 format");
            repo.findByPhoneE164(phone).ifPresent(other -> {
                if (!other.getId().equals(id)) throw new ApiException(409, "Phone already registered: " + phone);
            });
            p.setPhoneE164(phone);
        }
        if (body.containsKey("patient_ref")) p.setPatientRef(optString(body, "patient_ref", 64));
        if (body.containsKey("preferred_language")) p.setPreferredLanguage(optLang(body));
        if (body.containsKey("dnd")) p.setDnd(optBool(body, "dnd", false));
        if (body.containsKey("active")) p.setActive(optBool(body, "active", true));
        if (body.containsKey("notes")) p.setNotes(optString(body, "notes", 2000));
        return repo.save(p);
    }

    private static String tail(String phone) {
        return phone.length() <= 4 ? phone : phone.substring(phone.length() - 4);
    }

    private static String optLang(Map<String, Object> body) {
        String l = optString(body, "preferred_language", 8);
        if (l == null) return "en";
        l = l.trim().toLowerCase();
        return LANGS.contains(l) ? l : "en";
    }

    private static String reqString(Map<String, Object> body, String key, int min, int max) {
        Object v = body.get(key);
        if (v == null) throw new ApiException(400, "Missing field: " + key);
        String s = String.valueOf(v).trim();
        if (s.length() < min || s.length() > max) throw new ApiException(400, key + " must be " + min + ".." + max + " characters");
        return s;
    }

    private static String optString(Map<String, Object> body, String key, int max) {
        Object v = body.get(key);
        if (v == null) return null;
        String s = String.valueOf(v).trim();
        if (s.length() > max) throw new ApiException(400, key + " too long (max " + max + ")");
        return s;
    }

    private static boolean optBool(Map<String, Object> body, String key, boolean def) {
        Object v = body.get(key);
        if (v == null) return def;
        if (v instanceof Boolean b) return b;
        return Boolean.parseBoolean(String.valueOf(v));
    }
}
