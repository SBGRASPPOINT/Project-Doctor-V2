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

/** A time-off block for a doctor (holiday, conference, partial day). Overlapping appointments get change notifications. */
@Entity
@Table(name = "doctor_time_off")
@Getter
@Setter
@NoArgsConstructor
public class DoctorTimeOff implements Jsonable {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "doctor_id", nullable = false)
    private Long doctorId;

    @Column(name = "from_ts", nullable = false)
    private Instant fromTs;

    @Column(name = "to_ts", nullable = false)
    private Instant toTs;

    @Column(name = "full_day", nullable = false)
    private boolean fullDay = false;

    @Column(name = "reason", length = 255)
    private String reason;

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
        m.put("doctor_id", doctorId);
        m.put("from_ts", fromTs == null ? null : fromTs.toString());
        m.put("to_ts", toTs == null ? null : toTs.toString());
        m.put("full_day", fullDay);
        m.put("reason", reason);
        m.put("created_at", createdAt == null ? null : createdAt.toString());
        return m;
    }
}
