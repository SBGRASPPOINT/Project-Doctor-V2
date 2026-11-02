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
 * "Who do we owe a call": one row per voicemail, with click-to-call state.
 * JPA entity — the {@code callback_requests} table is created by Hibernate.
 */
@Entity
@Table(name = "callback_requests", indexes = {
        @Index(name = "idx_callbacks_status", columnList = "status")
})
@Getter
@Setter
@NoArgsConstructor
public class CallbackRequest implements Jsonable {

    // ---- JPA relations (LAZY, read-only onto the existing id columns) ----

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "call_id", insertable = false, updatable = false)
    private CallRecord call;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "assigned_agent_id", insertable = false, updatable = false)
    private Agent assignedAgent;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "call_id")
    private Long callId;

    @Column(name = "caller_number", nullable = false, length = 32)
    private String callerNumber;

    @Column(name = "voicemail_url", columnDefinition = "text")
    private String voicemailUrl;

    @Column(name = "voicemail_duration_sec")
    private Integer voicemailDurationSec;

    /** pending | calling | called_back | closed (enforced in CallbackService). */
    @Column(nullable = false, length = 16)
    private String status = "pending";

    @Column(name = "assigned_agent_id")
    private Long assignedAgentId;

    @Column(name = "notes", columnDefinition = "text")
    private String notes;

    @Column(name = "outbound_call_uuid", length = 64)
    private String outboundCallUuid;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "closed_at")
    private Instant closedAt;

    /** Joined from agents, not a column. */
    @Transient
    private String assignedAgentName;

    @PrePersist
    void onCreate() {
        if (createdAt == null) createdAt = Instant.now();
    }

    @Override
    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("call_id", callId);
        m.put("caller_number", callerNumber);
        m.put("voicemail_url", voicemailUrl);
        m.put("voicemail_duration_sec", voicemailDurationSec);
        m.put("status", status);
        m.put("assigned_agent_id", assignedAgentId);
        m.put("assigned_agent_name", assignedAgentName);
        m.put("notes", notes);
        m.put("outbound_call_uuid", outboundCallUuid);
        m.put("created_at", createdAt == null ? null : createdAt.toString());
        m.put("closed_at", closedAt == null ? null : closedAt.toString());
        return m;
    }
}
