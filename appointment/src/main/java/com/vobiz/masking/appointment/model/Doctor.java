package com.vobiz.masking.appointment.model;

import com.vobiz.masking.util.Json.Jsonable;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinTable;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A doctor. Doctors are also callable parties: their masked phone number lives in the
 * existing {@code agents} table and is linked here via {@code agentId} — the real number
 * is never duplicated, so masking stays intact when we bridge to "Dr X".
 */
@Entity
@Table(name = "doctors")
@Getter
@Setter
@NoArgsConstructor
public class Doctor implements Jsonable {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Opaque link to the MySQL app's Doctors.doctor_id. */
    @Column(name = "doctor_ref")
    private Integer doctorRef;

    @Column(nullable = false, length = 120)
    private String name;

    @Column(name = "specialization", length = 120)
    private String specialization;

    @Column(name = "department_id")
    private Long departmentId;

    /** Link to agents.id — the doctor's masked, callable number. */
    @Column(name = "agent_id")
    private Long agentId;

    /** Default appointment length in minutes. */
    @Column(name = "slot_minutes", nullable = false)
    private int slotMinutes = 15;

    @Column(nullable = false)
    private boolean active = true;

    @Column(name = "consultation_fee", precision = 10, scale = 2)
    private BigDecimal consultationFee;

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
        m.put("doctor_ref", doctorRef);
        m.put("name", name);
        m.put("specialization", specialization);
        m.put("department_id", departmentId);
        m.put("agent_id", agentId);
        m.put("slot_minutes", slotMinutes);
        m.put("active", active);
        m.put("consultation_fee", consultationFee == null ? null : consultationFee.toPlainString());
        m.put("created_at", createdAt == null ? null : createdAt.toString());
        return m;
    }
}
