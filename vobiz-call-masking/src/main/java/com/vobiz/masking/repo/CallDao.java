package com.vobiz.masking.repo;

import com.vobiz.masking.model.CallRecord;
import com.vobiz.masking.util.Json;
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
 * Calls store. Two halves, as requested:
 * <ul>
 *   <li>single-row operations go through JPA ({@link CallRepository}) — load entity, mutate, save;</li>
 *   <li>the filtered call-log view and the dashboard aggregates stay native SQL
 *       (joins, ILIKE, FILTER) and run through {@link JdbcTemplate}.</li>
 * </ul>
 */
public class CallDao {

    private final CallRepository repo;
    private final JdbcTemplate jdbc;

    public CallDao(CallRepository repo, JdbcTemplate jdbc) {
        this.repo = repo;
        this.jdbc = jdbc;
    }

    @Transactional
    public CallRecord insertInbound(String callUuid, String from, String to) {
        CallRecord c = new CallRecord();
        c.setCallUuid(callUuid);
        c.setDirection("inbound");
        c.setCallerNumber(from);
        c.setDialedNumber(to);
        c.setStatus("in_progress");
        c.setStartedAt(Instant.now());
        return repo.save(c);
    }

    @Transactional
    public CallRecord insertOutbound(String callUuid, String customerNumber, long agentId, long callbackId) {
        CallRecord c = new CallRecord();
        c.setCallUuid(callUuid);
        c.setDirection("outbound");
        c.setCallerNumber(customerNumber);
        c.setDialedNumber("via M1");
        c.setAgentId(agentId);
        c.setCallbackId(callbackId);
        c.setStatus("in_progress");
        c.setStartedAt(Instant.now());
        return repo.save(c);
    }

    public Optional<CallRecord> findByUuid(String callUuid) {
        return repo.findByCallUuid(callUuid);
    }

    public Optional<CallRecord> findById(long id) {
        return repo.findById(id);
    }

    /** Append one agent id to the JSONB dialed_agents array. */
    @Transactional
    public void appendDialedAgent(String callUuid, long agentId) {
        repo.findByCallUuid(callUuid).ifPresent(c -> {
            List<Long> dialed = c.getDialedAgents();
            if (dialed == null) {
                dialed = new ArrayList<>();
                c.setDialedAgents(dialed);
            }
            dialed.add(agentId);
            repo.save(c);
        });
    }

    public List<Long> getDialedAgents(String callUuid) {
        return repo.findByCallUuid(callUuid)
                .map(c -> c.getDialedAgents() == null ? List.<Long>of() : List.copyOf(c.getDialedAgents()))
                .orElse(List.of());
    }

    @Transactional
    public void markAnswered(String callUuid, Long agentId) {
        repo.findByCallUuid(callUuid).ifPresent(c -> {
            c.setStatus("answered");
            c.setAnsweredAt(Instant.now());
            if (agentId != null) c.setAgentId(agentId);
            repo.save(c);
        });
    }

    @Transactional
    public void setStatus(String callUuid, String status, String cause) {
        repo.findByCallUuid(callUuid).ifPresent(c -> {
            c.setStatus(status);
            if (cause != null) c.setHangupCause(cause);
            repo.save(c);
        });
    }

    @Transactional
    public void markVoicemail(String callUuid, String recordingUrl) {
        repo.findByCallUuid(callUuid).ifPresent(c -> {
            c.setStatus("voicemail");
            if (recordingUrl != null) c.setRecordingUrl(recordingUrl);
            repo.save(c);
        });
    }

    /** Final hangup event: duration + cause; a still-ringing call counts as no_answer. */
    @Transactional
    public void finalizeHangup(String callUuid, Integer durationSec, String cause) {
        repo.findByCallUuid(callUuid).ifPresent(c -> {
            c.setEndedAt(Instant.now());
            if (durationSec != null) c.setDurationSec(durationSec);
            if (cause != null) c.setHangupCause(cause);
            if ("in_progress".equals(c.getStatus())) c.setStatus("no_answer");
            repo.save(c);
        });
    }

    /** Filtered call log: status / date range / free-text (ILIKE on numbers) + agent-name join. Native SQL via JDBC. */
    public List<CallRecord> list(String status, String fromDate, String toDate, String q, int limit) {
        StringBuilder sql = new StringBuilder(
                "SELECT c.*, a.name AS agent_name FROM calls c LEFT JOIN agents a ON a.id = c.agent_id WHERE 1=1 ");
        List<Object> params = new ArrayList<>();
        if (status != null && !status.isBlank()) {
            sql.append("AND c.status = ? ");
            params.add(status);
        }
        if (fromDate != null && !fromDate.isBlank()) {
            sql.append("AND c.created_at >= ?::date ");
            params.add(fromDate);
        }
        if (toDate != null && !toDate.isBlank()) {
            sql.append("AND c.created_at < (?::date + 1) ");
            params.add(toDate);
        }
        if (q != null && !q.isBlank()) {
            sql.append("AND (c.caller_number ILIKE ? OR c.dialed_number ILIKE ?) ");
            params.add("%" + q + "%");
            params.add("%" + q + "%");
        }
        sql.append("ORDER BY c.created_at DESC LIMIT ?");
        params.add(limit);
        return jdbc.query(sql.toString(), ROW_MAPPER, params.toArray());
    }

    /** Dashboard counters. Native SQL via JDBC (FILTER aggregates). */
    public Map<String, Object> stats() {
        String sql = "SELECT count(*) AS total, "
                + "count(*) FILTER (WHERE direction = 'inbound') AS inbound, "
                + "count(*) FILTER (WHERE status = 'in_progress') AS live, "
                + "count(*) FILTER (WHERE created_at >= current_date) AS today, "
                + "count(*) FILTER (WHERE created_at >= current_date AND status = 'answered') AS answered_today, "
                + "count(*) FILTER (WHERE status = 'voicemail') AS voicemails "
                + "FROM calls";
        Map<String, Object> row = jdbc.queryForMap(sql);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("calls_total", lng(row.get("total")));
        m.put("calls_inbound", lng(row.get("inbound")));
        m.put("calls_live", lng(row.get("live")));
        m.put("calls_today", lng(row.get("today")));
        m.put("calls_answered_today", lng(row.get("answered_today")));
        m.put("calls_voicemail", lng(row.get("voicemails")));
        return m;
    }

    private static long lng(Object v) {
        return v instanceof Number n ? n.longValue() : 0L;
    }

    private static final RowMapper<CallRecord> ROW_MAPPER = (rs, rowNum) -> {
        CallRecord c = new CallRecord();
        c.setId(rs.getLong("id"));
        c.setCallUuid(rs.getString("call_uuid"));
        c.setDirection(rs.getString("direction"));
        c.setCallerNumber(rs.getString("caller_number"));
        c.setDialedNumber(rs.getString("dialed_number"));
        long agentId = rs.getLong("agent_id");
        c.setAgentId(rs.wasNull() ? null : agentId);
        c.setDialedAgents(parseIds(rs.getString("dialed_agents")));
        c.setStatus(rs.getString("status"));
        c.setStartedAt(toInstant(rs.getTimestamp("started_at")));
        c.setAnsweredAt(toInstant(rs.getTimestamp("answered_at")));
        c.setEndedAt(toInstant(rs.getTimestamp("ended_at")));
        int dur = rs.getInt("duration_sec");
        c.setDurationSec(rs.wasNull() ? null : dur);
        c.setHangupCause(rs.getString("hangup_cause"));
        c.setRecordingUrl(rs.getString("recording_url"));
        long cb = rs.getLong("callback_id");
        c.setCallbackId(rs.wasNull() ? null : cb);
        c.setCreatedAt(toInstant(rs.getTimestamp("created_at")));
        c.setAgentName(rs.getString("agent_name"));
        return c;
    };

    private static Instant toInstant(Timestamp t) {
        return t == null ? null : t.toInstant();
    }

    private static List<Long> parseIds(String json) {
        if (json == null || json.isBlank()) return List.of();
        Object v = Json.parse(json);
        if (v instanceof List<?> list) {
            List<Long> out = new ArrayList<>();
            for (Object o : list) {
                if (o instanceof Number n) out.add(n.longValue());
            }
            return out;
        }
        return List.of();
    }
}
