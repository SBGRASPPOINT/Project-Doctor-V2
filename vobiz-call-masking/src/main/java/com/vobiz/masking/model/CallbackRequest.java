package com.vobiz.masking.model;

import com.vobiz.masking.util.Json.Jsonable;
import lombok.Data;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/** "Who do we owe a call": one row per voicemail, with click-to-call state. */
@Data
public class CallbackRequest implements Jsonable {

    private Long id;
    private Long callId;
    private String callerNumber;
    private String voicemailUrl;
    private Integer voicemailDurationSec;
    private String status;              // pending | calling | called_back | closed
    private Long assignedAgentId;
    private String notes;
    private String outboundCallUuid;
    private Instant createdAt;
    private Instant closedAt;

    /** Joined from agents, not a column. */
    private String assignedAgentName;

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
