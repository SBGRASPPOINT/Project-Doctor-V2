package com.vobiz.masking.appointment.model;

import com.vobiz.masking.util.Json.Jsonable;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/** A clinic department (General Medicine, Cardiology, …) — the first IVR menu level. */
@Entity
@Table(name = "departments")
@Getter
@Setter
@NoArgsConstructor
public class Department implements Jsonable {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 120)
    private String name;

    @Column(nullable = false)
    private boolean active = true;

    /** Menu order (lower first). */
    @Column(nullable = false)
    private int priority = 0;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) createdAt = Instant.now();
    }

    @Override
    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("name", name);
        m.put("active", active);
        m.put("priority", priority);
        m.put("created_at", createdAt == null ? null : createdAt.toString());
        return m;
    }
}
