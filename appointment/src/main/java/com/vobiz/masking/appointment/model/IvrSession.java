package com.vobiz.masking.appointment.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.FetchType;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * IVR call state. Vobiz holds NO state between webhooks, so every IVR handler reads this row
 * by CallUUID, mutates it and writes it back. Rows older than 24 h are cleaned up by the scheduler.
 *
 * {@code collected} (jsonb) holds whatever the flow has gathered so far: chosen department,
 * doctor, offered slot list, selected index, appointment id, …
 */
@Entity
@Table(name = "ivr_sessions")
@Getter
@Setter
@NoArgsConstructor
public class IvrSession {

    // ---- JPA relations (LAZY, read-only onto the id columns) ----

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "patient_id", insertable = false, updatable = false)
    private Patient patient;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "appointment_id", insertable = false, updatable = false)
    private Appointment appointment;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "reminder_id", insertable = false, updatable = false)
    private Reminder reminder;

    /** Vobiz CallUUID — the primary key. */
    @Id
    @Column(name = "call_uuid", length = 64)
    private String callUuid;

    @Column(name = "patient_id")
    private Long patientId;

    @Column(name = "caller_number", length = 32)
    private String callerNumber;

    /** greeting | main_menu | choose_department | choose_doctor | choose_slot | confirm |
     *  reschedule_pick | reschedule_slot | cancel_confirm | reminder_menu | bridged | done */
    @Column(nullable = false, length = 32)
    private String state = "greeting";

    /** book | reschedule | cancel | human | reminder */
    @Column(length = 16)
    private String intent;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "collected", columnDefinition = "jsonb")
    private Map<String, Object> collected;

    @Column(name = "appointment_id")
    private Long appointmentId;

    @Column(name = "reminder_id")
    private Long reminderId;

    /** Hop counter — caps loops from a stuck caller. */
    @Column(nullable = false)
    private int hops = 0;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    void onCreate() {
        if (updatedAt == null) updatedAt = Instant.now();
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }

    public Map<String, Object> collected() {
        if (collected == null) collected = new HashMap<>();
        return collected;
    }
}
