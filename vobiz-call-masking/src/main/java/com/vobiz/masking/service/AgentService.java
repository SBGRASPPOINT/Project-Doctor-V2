package com.vobiz.masking.service;

import com.vobiz.masking.model.Agent;
import com.vobiz.masking.repo.AgentDao;
import com.vobiz.masking.util.ApiException;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** Agent (real-number) management with validation. */
public class AgentService {

    private static final Pattern E164 = Pattern.compile("^\\+[1-9]\\d{6,14}$");
    private static final Set<String> STATUSES = Set.of("available", "busy", "offline");

    private final AgentDao dao;

    public AgentService(AgentDao dao) {
        this.dao = dao;
    }

    public List<Agent> list() {
        return dao.listAll();
    }

    public Agent get(long id) {
        return dao.findById(id).orElseThrow(() -> new ApiException(404, "Agent not found: " + id));
    }

    public Map<String, Object> stats() {
        List<Agent> all = dao.listAll();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("agents_active", all.stream().filter(Agent::isActive).count());
        m.put("agents_total", all.size());
        return m;
    }

    public Agent create(Map<String, Object> body) {
        String name = reqString(body, "name", 1, 120);
        String phone = reqString(body, "phone_e164", 1, 32);
        if (!E164.matcher(phone).matches())
            throw new ApiException(400, "phone_e164 must be E.164 format, e.g. +919876543210");
        int priority = optInt(body, "priority", 0);
        if (dao.findByPhone(phone).isPresent())
            throw new ApiException(409, "Phone already registered to another agent: " + phone);
        return dao.create(name, phone, priority);
    }

    public Agent update(long id, Map<String, Object> body) {
        Agent existing = get(id);
        String name = body.containsKey("name") ? reqString(body, "name", 1, 120) : null;
        String phone = null;
        if (body.containsKey("phone_e164")) {
            phone = reqString(body, "phone_e164", 1, 32);
            if (!E164.matcher(phone).matches())
                throw new ApiException(400, "phone_e164 must be E.164 format, e.g. +919876543210");
            if (!phone.equals(existing.getPhoneE164()) && dao.findByPhone(phone).isPresent())
                throw new ApiException(409, "Phone already registered to another agent: " + phone);
        }
        Integer priority = body.containsKey("priority") ? optInt(body, "priority", 0) : null;
        String status = null;
        if (body.containsKey("status")) {
            status = reqString(body, "status", 1, 16);
            if (!STATUSES.contains(status))
                throw new ApiException(400, "status must be one of " + STATUSES);
        }
        Boolean active = body.containsKey("active") ? optBool(body, "active") : null;
        dao.update(id, name, phone, priority, status, active);
        return get(id);
    }

    /** Soft delete: leaves the rotation, keeps history. */
    public void deactivate(long id) {
        get(id);
        dao.deactivate(id);
    }

    public Agent setStatus(long id, String status) {
        if (!STATUSES.contains(status))
            throw new ApiException(400, "status must be one of " + STATUSES);
        get(id);
        dao.setStatus(id, status);
        return get(id);
    }

    private static String reqString(Map<String, Object> body, String key, int min, int max) {
        Object v = body.get(key);
        if (v == null) throw new ApiException(400, "Missing field: " + key);
        String s = String.valueOf(v).trim();
        if (s.length() < min || s.length() > max)
            throw new ApiException(400, key + " must be " + min + ".." + max + " characters");
        return s;
    }

    private static int optInt(Map<String, Object> body, String key, int def) {
        Object v = body.get(key);
        if (v == null) return def;
        if (v instanceof Number n) return n.intValue();
        try {
            return Integer.parseInt(String.valueOf(v).trim());
        } catch (NumberFormatException e) {
            throw new ApiException(400, key + " must be an integer");
        }
    }

    private static boolean optBool(Map<String, Object> body, String key) {
        Object v = body.get(key);
        if (v == null) throw new ApiException(400, "Missing field: " + key);
        if (v instanceof Boolean b) return b;
        return Boolean.parseBoolean(String.valueOf(v));
    }
}
