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

/** A patient waiting for a slot with a doctor/department; offered freed slots on cancellation. */
@Entity
@Table(name = "waitlist_entries")
@Getter
@Setter
@NoArgsConstructor
public class WaitlistEntry implements Jsonable {

    // ---- JPA relations (LAZY, read-only onto the id columns) ----

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "patient_id", insertable = false, updatable = false)
    private Patient patient;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "doctor_id", insertable = false, updatable = false)
    private Doctor doctor;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "department_id", insertable = false, updatable = false)
    private Department department;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "patient_id", nullable = false)
    private Long patientId;

    @Column(name = "doctor_id")
    private Long doctorId;

    @Column(name = "department_id")
    private Long departmentId;

    @Column(name = "preferred_from")
    private Instant preferredFrom;

    @Column(name = "preferred_to")
    private Instant preferredTo;

    /** waiting | offered | booked | expired */
    @Column(nullable = false, length = 16)
    private String status = "waiting";

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
        m.put("patient_id", patientId);
        m.put("doctor_id", doctorId);
        m.put("department_id", departmentId);
        m.put("preferred_from", preferredFrom == null ? null : preferredFrom.toString());
        m.put("preferred_to", preferredTo == null ? null : preferredTo.toString());
        m.put("status", status);
        m.put("created_at", createdAt == null ? null : createdAt.toString());
        return m;
    }
}
