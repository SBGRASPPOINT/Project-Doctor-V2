package com.vobiz.masking.appointment.model;

import com.vobiz.masking.util.Json.Jsonable;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.FetchType;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A scheduled outbound notification. kind: t24h | t2h | change | custom.
 * UNIQUE (appointment_id, kind) de-duplicates — a reschedule REPLACES the pending
 * reminders instead of adding a second set. (status, scheduled_at) is the scheduler's hot index.
 */
@Entity
@Table(name = "reminders", uniqueConstraints = {
        @UniqueConstraint(name = "uq_reminder_appt_kind", columnNames = {"appointment_id", "kind"})
}, indexes = {
        @Index(name = "idx_reminder_due", columnList = "status, scheduled_at")
})
@Getter
@Setter
@NoArgsConstructor
public class Reminder implements Jsonable {

    /** The appointment this reminder belongs to — read-only view onto appointment_id. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "appointment_id", insertable = false, updatable = false)
    private Appointment appointment;

    public static final String KIND_T24H = "t24h";
    public static final String KIND_T2H = "t2h";
    public static final String KIND_CHANGE = "change";
    public static final String KIND_CUSTOM = "custom";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "appointment_id", nullable = false)
    private Long appointmentId;

    @Column(nullable = false, length = 16)
    private String kind = KIND_CUSTOM;

    /** When the outbound call should be placed (UTC instant). */
    @Column(name = "scheduled_at", nullable = false)
    private Instant scheduledAt;

    /** voice (default) | sms */
    @Column(nullable = false, length = 8)
    private String channel = "voice";

    /**
     * pending     — due scan will place a call
     * placed      — outbound leg placed, outcome not known yet
     * answered    — patient picked up (>= 5s) but pressed no key; never auto-redialled
     * answered_confirmed / answered_cancelled — keypad 1 / 3
     * answered_reschedule_requested — keypad 2: caller sent to reception, nothing rescheduled yet
     * no_answer / failed / suppressed
     */
    @Column(nullable = false, length = 24)
    private String status = "pending";

    @Column(nullable = false)
    private int attempts = 0;

    @Column(name = "last_attempt_at")
    private Instant lastAttemptAt;

    @Column(name = "outbound_call_uuid", length = 64)
    private String outboundCallUuid;

    /** The keypad digit the patient pressed on the reminder call. */
    @Column(name = "response_digit", length = 4)
    private String responseDigit;

    @Column(name = "error_text", length = 255)
    private String errorText;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /** Joined for display, not a column. */
    @Transient
    private String appointmentRef;

    @PrePersist
    void onCreate() {
        if (createdAt == null) createdAt = Instant.now();
    }

    @Override
    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("appointment_id", appointmentId);
        m.put("appointment_ref", appointmentRef);
        m.put("kind", kind);
        m.put("scheduled_at", scheduledAt == null ? null : scheduledAt.toString());
        m.put("channel", channel);
        m.put("status", status);
        m.put("attempts", attempts);
        m.put("last_attempt_at", lastAttemptAt == null ? null : lastAttemptAt.toString());
        m.put("outbound_call_uuid", outboundCallUuid);
        m.put("response_digit", responseDigit);
        m.put("error_text", errorText);
        m.put("created_at", createdAt == null ? null : createdAt.toString());
        return m;
    }
}
