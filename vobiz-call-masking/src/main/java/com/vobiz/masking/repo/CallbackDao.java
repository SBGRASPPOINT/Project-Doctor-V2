package com.vobiz.masking.repo;

import com.vobiz.masking.model.CallbackRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Callback queue store. Single-row operations go through JPA ({@link CallbackRepository});
 * the joined list view (agent name) and the dashboard counters stay native SQL via
 * {@link JdbcTemplate}.
 */
public class CallbackDao {

    private final CallbackRepository repo;
    private final AgentRepository agentRepo;
    private final JdbcTemplate jdbc;

    public CallbackDao(CallbackRepository repo, AgentRepository agentRepo, JdbcTemplate jdbc) {
        this.repo = repo;
        this.agentRepo = agentRepo;
        this.jdbc = jdbc;
    }

    @Transactional
    public CallbackRequest create(long callId, String callerNumber, String voicemailUrl, Integer durationSec) {
        CallbackRequest cb = new CallbackRequest();
        cb.setCallId(callId);
        cb.setCallerNumber(callerNumber);
        cb.setVoicemailUrl(voicemailUrl);
        cb.setVoicemailDurationSec(durationSec);
        cb.setStatus("pending");
        return repo.save(cb);
    }

    public Optional<CallbackRequest> findById(long id) {
        return repo.findById(id).map(this::withAgentName);
    }

    /** Joined list view (assigned agent name) with optional status filter. Native SQL via JDBC. */
    public List<CallbackRequest> list(String status) {
        StringBuilder sql = new StringBuilder(
                "SELECT cb.*, a.name AS assigned_agent_name FROM callback_requests cb "
                        + "LEFT JOIN agents a ON a.id = cb.assigned_agent_id WHERE 1=1 ");
        List<Object> params = new ArrayList<>();
        if (status != null && !status.isBlank()) {
            sql.append("AND cb.status = ? ");
            params.add(status);
        }
        sql.append("ORDER BY cb.created_at DESC");
        return jdbc.query(sql.toString(), ROW_MAPPER, params.toArray());
    }

    public List<CallbackRequest> listByCallId(long callId) {
        return repo.findByCallIdOrderByCreatedAtDesc(callId);
    }

    @Transactional
    public void markCalling(long id, long agentId, String outboundCallUuid) {
        repo.findById(id).ifPresent(cb -> {
            cb.setStatus("calling");
            cb.setAssignedAgentId(agentId);
            cb.setOutboundCallUuid(outboundCallUuid);
            repo.save(cb);
        });
    }

    @Transactional
    public void markCalledBack(long id) {
        repo.findById(id).ifPresent(cb -> {
            cb.setStatus("called_back");
            cb.setClosedAt(Instant.now());
            repo.save(cb);
        });
    }

    /** Partial update from the ops console; null arguments are left unchanged. */
    @Transactional
    public void update(long id, String status, String notes, Long assignedAgentId) {
        repo.findById(id).ifPresent(cb -> {
            if (status != null) {
                cb.setStatus(status);
                if ("called_back".equals(status) || "closed".equals(status)) cb.setClosedAt(Instant.now());
            }
            if (notes != null) cb.setNotes(notes);
            if (assignedAgentId != null) cb.setAssignedAgentId(assignedAgentId);
            repo.save(cb);
        });
    }

    /** Dashboard counters. Native SQL via JDBC. */
    public Map<String, Object> stats() {
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT count(*) AS total, count(*) FILTER (WHERE status = 'pending') AS pending FROM callback_requests");
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("callbacks_total", lng(row.get("total")));
        m.put("callbacks_pending", lng(row.get("pending")));
        return m;
    }

    private CallbackRequest withAgentName(CallbackRequest cb) {
        if (cb.getAssignedAgentId() != null) {
            agentRepo.findById(cb.getAssignedAgentId()).ifPresent(a -> cb.setAssignedAgentName(a.getName()));
        }
        return cb;
    }

    private static long lng(Object v) {
        return v instanceof Number n ? n.longValue() : 0L;
    }

    private static final RowMapper<CallbackRequest> ROW_MAPPER = (rs, rowNum) -> {
        CallbackRequest cb = new CallbackRequest();
        cb.setId(rs.getLong("id"));
        long callId = rs.getLong("call_id");
        cb.setCallId(rs.wasNull() ? null : callId);
        cb.setCallerNumber(rs.getString("caller_number"));
        cb.setVoicemailUrl(rs.getString("voicemail_url"));
        int dur = rs.getInt("voicemail_duration_sec");
        cb.setVoicemailDurationSec(rs.wasNull() ? null : dur);
        cb.setStatus(rs.getString("status"));
        long agentId = rs.getLong("assigned_agent_id");
        cb.setAssignedAgentId(rs.wasNull() ? null : agentId);
        cb.setNotes(rs.getString("notes"));
        cb.setOutboundCallUuid(rs.getString("outbound_call_uuid"));
        cb.setCreatedAt(toInstant(rs.getTimestamp("created_at")));
        cb.setClosedAt(toInstant(rs.getTimestamp("closed_at")));
        cb.setAssignedAgentName(rs.getString("assigned_agent_name"));
        return cb;
    };

    private static Instant toInstant(Timestamp t) {
        return t == null ? null : t.toInstant();
    }
}
