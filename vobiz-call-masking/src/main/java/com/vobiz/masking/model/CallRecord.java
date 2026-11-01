package com.vobiz.masking.model;

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
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One call leg: inbound to M1, or an outbound click-to-call leg.
 * {@code call_uuid} is the join key to Vobiz CDRs.
 *
 * JPA entity: Hibernate creates/updates the {@code calls} table (incl. the two indexes
 * below) from this class — no hand-written schema.sql. {@code dialed_agents} is a JSONB
 * column mapped straight onto a {@code List<Long>}.
 */
@Entity
@Table(name = "calls", indexes = {
        @Index(name = "idx_calls_status", columnList = "status"),
        @Index(name = "idx_calls_created", columnList = "created_at")
})
@Getter
@Setter
@NoArgsConstructor
public class CallRecord implements Jsonable {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Vobiz CallUUID — join key to CDRs. */
    @Column(name = "call_uuid", unique = true, length = 64)
    private String callUuid;

    /** inbound | outbound */
    @Column(nullable = false, length = 16)
    private String direction = "inbound";

    @Column(name = "caller_number", length = 32)
    private String callerNumber;

    @Column(name = "dialed_number", length = 32)
    private String dialedNumber;

    /** Set when a single agent took the call. */
    @Column(name = "agent_id")
    private Long agentId;

    /** Agents that were rung, in order (JSONB). */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "dialed_agents", columnDefinition = "jsonb")
    private List<Long> dialedAgents;

    /** in_progress | answered | no_answer | voicemail | failed */
    @Column(nullable = false, length = 16)
    private String status = "in_progress";

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "answered_at")
    private Instant answeredAt;

    @Column(name = "ended_at")
    private Instant endedAt;

    @Column(name = "duration_sec")
    private Integer durationSec;

    @Column(name = "hangup_cause", length = 64)
    private String hangupCause;

    @Column(name = "recording_url", columnDefinition = "text")
    private String recordingUrl;

    /** Set on outbound callback legs. */
    @Column(name = "callback_id")
    private Long callbackId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /** Joined from agents, not a column. */
    @Transient
    private String agentName;

    @PrePersist
    void onCreate() {
        if (createdAt == null) createdAt = Instant.now();
    }

    @Override
    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("call_uuid", callUuid);
        m.put("direction", direction);
        m.put("caller_number", callerNumber);
        m.put("dialed_number", dialedNumber);
        m.put("agent_id", agentId);
        m.put("agent_name", agentName);
        m.put("dialed_agents", dialedAgents);
        m.put("status", status);
        m.put("started_at", startedAt == null ? null : startedAt.toString());
        m.put("answered_at", answeredAt == null ? null : answeredAt.toString());
        m.put("ended_at", endedAt == null ? null : endedAt.toString());
        m.put("duration_sec", durationSec);
        m.put("hangup_cause", hangupCause);
        m.put("recording_url", recordingUrl);
        m.put("callback_id", callbackId);
        m.put("created_at", createdAt == null ? null : createdAt.toString());
        return m;
    }
}
