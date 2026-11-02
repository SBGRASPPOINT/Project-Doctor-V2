package com.vobiz.masking.appointment.model;

import com.vobiz.masking.util.Json.Jsonable;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.FetchType;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.time.LocalTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Recurring weekly availability template for a doctor. Slots are DERIVED from this template
 * + time-off + existing appointments — nothing is pre-materialised.
 */
@Entity
@Table(name = "doctor_schedules", uniqueConstraints = {
        @UniqueConstraint(name = "uq_doc_sched", columnNames = {"doctor_id", "day_of_week", "start_time"})
})
@Getter
@Setter
@NoArgsConstructor
public class DoctorSchedule implements Jsonable {

    /** Owning many-to-one side of the weekly template — read-only view onto doctor_id. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "doctor_id", insertable = false, updatable = false)
    private Doctor doctor;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "doctor_id", nullable = false)
    private Long doctorId;

    /** ISO day of week: 1 = Monday … 7 = Sunday. */
    @Column(name = "day_of_week", nullable = false)
    private int dayOfWeek;

    @Column(name = "start_time", nullable = false)
    private LocalTime startTime;

    @Column(name = "end_time", nullable = false)
    private LocalTime endTime;

    /** Overrides doctors.slot_minutes when > 0. */
    @Column(name = "slot_minutes", nullable = false)
    private int slotMinutes = 0;

    @Column(nullable = false)
    private boolean active = true;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @jakarta.persistence.PrePersist
    void onCreate() {
        if (createdAt == null) createdAt = Instant.now();
    }

    @Override
    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("doctor_id", doctorId);
        m.put("day_of_week", dayOfWeek);
        m.put("start_time", startTime == null ? null : startTime.toString());
        m.put("end_time", endTime == null ? null : endTime.toString());
        m.put("slot_minutes", slotMinutes);
        m.put("active", active);
        m.put("created_at", createdAt == null ? null : createdAt.toString());
        return m;
    }
}
