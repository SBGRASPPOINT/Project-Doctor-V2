package com.vobiz.masking.appointment.service;

import com.vobiz.masking.appointment.model.Department;
import com.vobiz.masking.appointment.repo.DepartmentRepository;
import com.vobiz.masking.util.ApiException;

import java.util.List;
import java.util.Map;

/** Department CRUD (the first IVR menu level). */
public class DepartmentService {

    private final DepartmentRepository repo;

    public DepartmentService(DepartmentRepository repo) {
        this.repo = repo;
    }

    public List<Department> listActive() {
        return repo.findByActiveTrueOrderByPriorityAscNameAsc();
    }

    public List<Department> listAll() {
        return repo.findAll();
    }

    public Department get(long id) {
        return repo.findById(id).orElseThrow(() -> new ApiException(404, "Department not found: " + id));
    }

    public Department create(Map<String, Object> body) {
        String name = reqString(body, "name", 1, 120);
        if (repo.findByNameIgnoreCase(name).isPresent())
            throw new ApiException(409, "Department already exists: " + name);
        Department d = new Department();
        d.setName(name);
        d.setPriority(optInt(body, "priority", 0));
        d.setActive(true);
        return repo.save(d);
    }

    public Department update(long id, Map<String, Object> body) {
        Department d = get(id);
        if (body.containsKey("name")) {
            String name = reqString(body, "name", 1, 120);
            repo.findByNameIgnoreCase(name).ifPresent(other -> {
                if (!other.getId().equals(id)) throw new ApiException(409, "Department already exists: " + name);
            });
            d.setName(name);
        }
        if (body.containsKey("priority")) d.setPriority(optInt(body, "priority", 0));
        if (body.containsKey("active")) d.setActive(optBool(body, "active", true));
        return repo.save(d);
    }

    private static String reqString(Map<String, Object> body, String key, int min, int max) {
        Object v = body.get(key);
        if (v == null) throw new ApiException(400, "Missing field: " + key);
        String s = String.valueOf(v).trim();
        if (s.length() < min || s.length() > max) throw new ApiException(400, key + " must be " + min + ".." + max + " chars");
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

    private static boolean optBool(Map<String, Object> body, String key, boolean def) {
        Object v = body.get(key);
        if (v == null) return def;
        if (v instanceof Boolean b) return b;
        return Boolean.parseBoolean(String.valueOf(v));
    }
}
