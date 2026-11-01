package com.vobiz.masking.model;

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

import com.vobiz.masking.appointment.model.Doctor;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A real number (N2, N3, ...) that must stay hidden from callers.
 *
 * JPA entity: Hibernate creates/updates the {@code agents} table from this class
 * ({@code spring.jpa.hibernate.ddl-auto=update}) — there is no hand-written schema.sql.
 */
@Entity
@Table(name = "agents")
@Getter
@Setter
@NoArgsConstructor
public class Agent implements Jsonable {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 120)
    private String name;

    @Column(name = "phone_e164", nullable = false, unique = true, length = 32)
    private String phoneE164;

    /** Lower rings first in sequential mode. */
    @Column(nullable = false)
    private int priority = 0;

    /** available | busy | offline (enforced in AgentService). */
    @Column(nullable = false, length = 16)
    private String status = "available";

    @Column(nullable = false)
    private boolean active = true;

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
        m.put("phone_e164", phoneE164);
        m.put("priority", priority);
        m.put("status", status);
        m.put("active", active);
        m.put("created_at", createdAt == null ? null : createdAt.toString());
        return m;
    }
}
