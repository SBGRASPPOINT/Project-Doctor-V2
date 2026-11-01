package com.vobiz.masking.appointment.service;

import com.vobiz.masking.appointment.model.Doctor;
import com.vobiz.masking.appointment.repo.DoctorRepository;
import com.vobiz.masking.util.ApiException;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * Doctor CRUD. A doctor's callable (masked) number lives in the existing {@code agents}
 * table; {@code agent_id} links the two — the real number is never duplicated here.
 */
public class DoctorService {

    private final DoctorRepository repo;

    public DoctorService(DoctorRepository repo) {
        this.repo = repo;
    }

    public List<Doctor> listActive() {
        return repo.findByActiveTrueOrderByNameAsc();
    }

    public List<Doctor> listAll() {
        return repo.findAll();
    }

    public List<Doctor> listByDepartment(long departmentId) {
        return repo.findByActiveTrueAndDepartmentIdOrderByNameAsc(departmentId);
    }

    public Doctor get(long id) {
        return repo.findById(id).orElseThrow(() -> new ApiException(404, "Doctor not found: " + id));
    }

    public Doctor create(Map<String, Object> body) {
        Doctor d = new Doctor();
        apply(d, body, true);
        return repo.save(d);
    }

    public Doctor update(long id, Map<String, Object> body) {
        Doctor d = get(id);
        apply(d, body, false);
        return repo.save(d);
    }

    private void apply(Doctor d, Map<String, Object> body, boolean creating) {
        if (creating || body.containsKey("name")) d.setName(reqString(body, "name", 1, 120));
        if (body.containsKey("specialization")) d.setSpecialization(optString(body, "specialization", 120));
        if (body.containsKey("department_id")) {
            Object v = body.get("department_id");
            d.setDepartmentId(v == null ? null : ((Number) v).longValue());
        }
        if (body.containsKey("agent_id")) {
            Object v = body.get("agent_id");
            d.setAgentId(v == null ? null : ((Number) v).longValue());
        }
        if (body.containsKey("doctor_ref")) {
            Object v = body.get("doctor_ref");
            d.setDoctorRef(v == null ? null : ((Number) v).intValue());
        }
        if (body.containsKey("slot_minutes")) {
            int m = optInt(body, "slot_minutes", 15);
            if (m != 0 && (m < 5 || m > 120)) throw new ApiException(400, "slot_minutes must be 5..120 (0 = inherit)");
            d.setSlotMinutes(m == 0 ? 15 : m);
        }
        if (body.containsKey("consultation_fee")) {
            Object v = body.get("consultation_fee");
            d.setConsultationFee(v == null ? null : new BigDecimal(String.valueOf(v)));
        }
        if (body.containsKey("active")) d.setActive(optBool(body, "active", true));
    }

    private static String reqString(Map<String, Object> body, String key, int min, int max) {
        Object v = body.get(key);
        if (v == null) throw new ApiException(400, "Missing field: " + key);
        String s = String.valueOf(v).trim();
        if (s.length() < min || s.length() > max) throw new ApiException(400, key + " must be " + min + ".." + max + " chars");
        return s;
    }

    private static String optString(Map<String, Object> body, String key, int max) {
        Object v = body.get(key);
        if (v == null) return null;
        String s = String.valueOf(v).trim();
        if (s.length() > max) throw new ApiException(400, key + " too long (max " + max + ")");
        return s.isEmpty() ? null : s;
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

    private static boolean optBool(Map<String, Object> body, String key, boolean def) {
        Object v = body.get(key);
        if (v == null) return def;
        if (v instanceof Boolean b) return b;
        return Boolean.parseBoolean(String.valueOf(v));
    }
}
