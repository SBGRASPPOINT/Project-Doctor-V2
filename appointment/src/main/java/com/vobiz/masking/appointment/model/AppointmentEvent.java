package com.vobiz.masking.appointment.model;

import com.vobiz.masking.util.Json.Jsonable;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
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
import java.util.LinkedHashMap;
import java.util.Map;

/** Audit trail for an appointment: created/confirmed/rescheduled/cancelled/…/reminder_sent/patient_*. */
@Entity
@Table(name = "appointment_events")
@Getter
@Setter
@NoArgsConstructor
public class AppointmentEvent implements Jsonable {

    /** Owning many-to-one side of the audit trail — read-only view onto appointment_id. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "appointment_id", insertable = false, updatable = false)
    private Appointment appointment;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "appointment_id", nullable = false)
    private Long appointmentId;

    /** created | confirmed | rescheduled | cancelled | completed | no_show | reminder_sent | patient_confirmed | patient_rescheduled | patient_cancelled */
    @Column(name = "event_type", nullable = false, length = 32)
    private String eventType;

    @Column(name = "from_status", length = 32)
    private String fromStatus;

    @Column(name = "to_status", length = 32)
    private String toStatus;

    /** patient_ivr | agent:{id} | system | scheduler */
    @Column(name = "actor", length = 64)
    private String actor;

    @Column(name = "call_uuid", length = 64)
    private String callUuid;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "payload", columnDefinition = "jsonb")
    private Map<String, Object> payload;

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
        m.put("appointment_id", appointmentId);
        m.put("event_type", eventType);
        m.put("from_status", fromStatus);
        m.put("to_status", toStatus);
        m.put("actor", actor);
        m.put("call_uuid", callUuid);
        m.put("payload", payload);
        m.put("created_at", createdAt == null ? null : createdAt.toString());
        return m;
    }
}
