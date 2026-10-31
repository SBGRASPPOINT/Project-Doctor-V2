package com.vobiz.masking.model;

import com.vobiz.masking.util.Json.Jsonable;
import lombok.Data;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** One call leg: inbound to M1, or an outbound click-to-call leg. */
@Data
public class CallRecord implements Jsonable {

    private Long id;
    private String callUuid;            // Vobiz CallUUID — join key to CDRs
    private String direction;           // inbound | outbound
    private String callerNumber;
    private String dialedNumber;
    private Long agentId;
    private List<Long> dialedAgents;    // agents rung, in order (JSONB)
    private String status;              // in_progress | answered | no_answer | voicemail | failed
    private Instant startedAt;
    private Instant answeredAt;
    private Instant endedAt;
    private Integer durationSec;
    private String hangupCause;
    private String recordingUrl;
    private Long callbackId;
    private Instant createdAt;

    /** Joined from agents, not a column. */
    private String agentName;

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
