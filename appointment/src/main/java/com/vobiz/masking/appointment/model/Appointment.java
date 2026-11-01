package com.vobiz.masking.appointment.model;

import com.vobiz.masking.util.Json.Jsonable;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import jakarta.persistence.Version;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One booked appointment. The double-booking guard is the UNIQUE {@code slot_key}
 * ("{doctorId}|{startsAt}"): it is set on booking and cleared (NULL) on cancel, which is
 * how a cancelled slot becomes bookable again while the row survives for history.
 * {@code starts_at}/{@code ends_at} are stored as UTC instants.
 */
@Entity
@Table(name = "appointments", indexes = {
        @Index(name = "idx_appt_doctor_start", columnList = "doctor_id, starts_at"),
        @Index(name = "idx_appt_patient_start", columnList = "patient_id, starts_at"),
        @Index(name = "idx_appt_status", columnList = "status"),
        @Index(name = "idx_appt_start", columnList = "starts_at")
})
@Getter
@Setter
@NoArgsConstructor
public class Appointment implements Jsonable {

    public static final String STATUS_REQUESTED = "requested";
    public static final String STATUS_CONFIRMED = "confirmed";
    public static final String STATUS_RESCHEDULED = "rescheduled";
    public static final String STATUS_CANCELLED = "cancelled";
    public static final String STATUS_COMPLETED = "completed";
    public static final String STATUS_NO_SHOW = "no_show";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** What the patient hears / reads back, e.g. APT-7K3Q. */
    @Column(name = "reference_code", unique = true, length = 16)
    private String referenceCode;

    @Column(name = "patient_id", nullable = false)
    private Long patientId;

    /** Denormalised snapshot — the patient may change their number later. */
    @Column(name = "patient_name", length = 120)
    private String patientName;

    @Column(name = "patient_phone_e164", length = 32)
    private String patientPhoneE164;

    @Column(name = "doctor_id", nullable = false)
    private Long doctorId;

    @Column(name = "department_id")
    private Long departmentId;

    @Column(name = "starts_at", nullable = false)
    private Instant startsAt;

    @Column(name = "ends_at", nullable = false)
    private Instant endsAt;

    @Column(name = "duration_min")
    private Integer durationMin;

    /** requested | confirmed | rescheduled | cancelled | completed | no_show */
    @Column(nullable = false, length = 32)
    private String status = STATUS_REQUESTED;

    /** ivr_call | agent | reminder_ivr | walk_in | web */
    @Column(nullable = false, length = 32)
    private String source = "ivr_call";

    /** "{doctorId}|{startsAt}" — UNIQUE while booked, NULL when cancelled. */
    @Column(name = "slot_key", unique = true, length = 64)
    private String slotKey;

    /** calls.id of the inbound call that created this appointment. */
    @Column(name = "created_via_call_id")
    private Long createdViaCallId;

    @Column(name = "reason", columnDefinition = "text")
    private String reason;

    @Column(name = "notes", columnDefinition = "text")
    private String notes;

    @Column(name = "cancellation_reason", length = 255)
    private String cancellationReason;

    @Column(name = "needs_rebooking", nullable = false)
    private boolean needsRebooking = false;

    @Column(name = "confirmed_at")
    private Instant confirmedAt;

    @Column(name = "cancelled_at")
    private Instant cancelledAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    /** Optimistic lock against two simultaneous bookings of the same slot. */
    @Version
    @Column(nullable = false)
    private int version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /** Joined for display, not a column. */
    @Transient
    private String doctorName;

    @Transient
    private String departmentName;

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
        m.put("reference_code", referenceCode);
        m.put("patient_id", patientId);
        m.put("patient_name", patientName);
        m.put("patient_phone_e164", patientPhoneE164);
        m.put("doctor_id", doctorId);
        m.put("doctor_name", doctorName);
        m.put("department_id", departmentId);
        m.put("department_name", departmentName);
        m.put("starts_at", startsAt == null ? null : startsAt.toString());
        m.put("ends_at", endsAt == null ? null : endsAt.toString());
        m.put("duration_min", durationMin);
        m.put("status", status);
        m.put("source", source);
        m.put("slot_key", slotKey);
        m.put("created_via_call_id", createdViaCallId);
        m.put("reason", reason);
        m.put("notes", notes);
        m.put("cancellation_reason", cancellationReason);
        m.put("needs_rebooking", needsRebooking);
        m.put("confirmed_at", confirmedAt == null ? null : confirmedAt.toString());
        m.put("cancelled_at", cancelledAt == null ? null : cancelledAt.toString());
        m.put("completed_at", completedAt == null ? null : completedAt.toString());
        m.put("version", version);
        m.put("created_at", createdAt == null ? null : createdAt.toString());
        m.put("updated_at", updatedAt == null ? null : updatedAt.toString());
        return m;
    }
}
