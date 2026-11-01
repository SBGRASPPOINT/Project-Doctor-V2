package com.vobiz.masking.model;

import com.vobiz.masking.util.Json.Jsonable;
import lombok.Data;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/** A real number (N2, N3, ...) that must stay hidden from callers. */
@Data
public class Agent implements Jsonable {

    private Long id;
    private String name;
    private String phoneE164;
    private int priority;
    private String status;      // available | busy | offline
    private boolean active;
    private Instant createdAt;

    @Override
    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("name", name);
        m.put("phone_e164", phoneE164);
        m.put("priority", priority);
        m.put("status", status);
        m.put("active", active);
        m.put("created_at", createdAt == null ? null : createdAt.toString());
        return m;
    }
}
