package com.vobiz.masking.appointment.model;

import com.vobiz.masking.util.Json.Jsonable;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A patient of the clinic. Identity on an inbound call is the caller ID:
 * {@code phone_e164} is UNIQUE and matched against Vobiz's {@code From}.
 * {@code patient_ref} optionally links to the Node/MySQL app's {@code Patients.patient_id}.
 */
@Entity
@Table(name = "patients")
@Getter
@Setter
@NoArgsConstructor
public class Patient implements Jsonable {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Opaque link to the MySQL app's Patients.patient_id; unique when set. */
    @Column(name = "patient_ref", unique = true, length = 64)
    private String patientRef;

    @Column(nullable = false, length = 120)
    private String name;

    /** Caller-ID match key. */
    @Column(name = "phone_e164", nullable = false, unique = true, length = 32)
    private String phoneE164;

    /** en | hi | mr | ta | te | kn | bn | gu — used to pick IVR/reminder prompt language. */
    @Column(name = "preferred_language", nullable = false, length = 8)
    private String preferredLanguage = "en";

    /** Do-Not-Disturb: suppress reminder calls. */
    @Column(nullable = false)
    private boolean dnd = false;

    @Column(nullable = false)
    private boolean active = true;

    @Column(name = "notes", columnDefinition = "text")
    private String notes;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        if (createdAt == null) createdAt = now;
        if (updatedAt == null) updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }

    @Override
    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("patient_ref", patientRef);
        m.put("name", name);
        m.put("phone_e164", phoneE164);
        m.put("preferred_language", preferredLanguage);
        m.put("dnd", dnd);
        m.put("active", active);
        m.put("notes", notes);
        m.put("created_at", createdAt == null ? null : createdAt.toString());
        m.put("updated_at", updatedAt == null ? null : updatedAt.toString());
        return m;
    }
}
