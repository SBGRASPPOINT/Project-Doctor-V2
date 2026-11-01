package com.vobiz.masking.repo;

import com.vobiz.masking.model.CallRecord;
import com.vobiz.masking.util.Json;
import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Plain-JDBC DAO over the calls table. */
public class CallDao {

    private static final String COLUMNS =
            "id, call_uuid, direction, caller_number, dialed_number, agent_id, dialed_agents, status, "
                    + "started_at, answered_at, ended_at, duration_sec, hangup_cause, recording_url, callback_id, created_at";

    private final DataSource ds;

    public CallDao(DataSource ds) {
        this.ds = ds;
    }

    public CallRecord insertInbound(String callUuid, String from, String to) {
        String sql = "INSERT INTO calls(call_uuid, direction, caller_number, dialed_number, status, started_at) "
                + "VALUES (?, 'inbound', ?, ?, 'in_progress', now()) RETURNING " + COLUMNS;
        try (Connection c = ds.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, callUuid);
            ps.setString(2, from);
            ps.setString(3, to);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return map(rs);
                throw new IllegalStateException("insert returned no row");
            }
        } catch (SQLException e) {
            throw new RuntimeException("DB error inserting call " + callUuid, e);
        }
    }

    public CallRecord insertOutbound(String callUuid, String customerNumber, long agentId, long callbackId) {
        String sql = "INSERT INTO calls(call_uuid, direction, caller_number, dialed_number, agent_id, callback_id, status, started_at) "
                + "VALUES (?, 'outbound', ?, ?, ?, ?, 'in_progress', now()) RETURNING " + COLUMNS;
        try (Connection c = ds.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, callUuid);
            ps.setString(2, customerNumber);
            ps.setString(3, "via M1");
            ps.setLong(4, agentId);
            ps.setLong(5, callbackId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return map(rs);
                throw new IllegalStateException("insert returned no row");
            }
        } catch (SQLException e) {
            throw new RuntimeException("DB error inserting outbound call " + callUuid, e);
        }
    }

    public Optional<CallRecord> findByUuid(String callUuid) {
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement("SELECT " + COLUMNS + " FROM calls WHERE call_uuid = ?")) {
            ps.setString(1, callUuid);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(map(rs)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new RuntimeException("DB error finding call " + callUuid, e);
        }
    }

    public Optional<CallRecord> findById(long id) {
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement("SELECT " + COLUMNS + " FROM calls WHERE id = ?")) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(map(rs)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new RuntimeException("DB error finding call id " + id, e);
        }
    }

    /** Append one agent id to the JSONB dialed_agents array. */
    public void appendDialedAgent(String callUuid, long agentId) {
        String sql = "UPDATE calls SET dialed_agents = COALESCE(dialed_agents, '[]'::jsonb) || ?::jsonb WHERE call_uuid = ?";
        try (Connection c = ds.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, "[" + agentId + "]");
            ps.setString(2, callUuid);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("DB error appending dialed agent", e);
        }
    }

    public List<Long> getDialedAgents(String callUuid) {
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement("SELECT dialed_agents FROM calls WHERE call_uuid = ?")) {
            ps.setString(1, callUuid);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return List.of();
                return parseIds(rs.getString("dialed_agents"));
            }
        } catch (SQLException e) {
            throw new RuntimeException("DB error reading dialed agents", e);
        }
    }

    public void markAnswered(String callUuid, Long agentId) {
        String sql = "UPDATE calls SET status = 'answered', answered_at = now(), agent_id = COALESCE(?, agent_id) WHERE call_uuid = ?";
        try (Connection c = ds.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            if (agentId == null) ps.setNull(1, java.sql.Types.BIGINT); else ps.setLong(1, agentId);
            ps.setString(2, callUuid);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("DB error marking call answered", e);
        }
    }

    public void setStatus(String callUuid, String status, String cause) {
        String sql = "UPDATE calls SET status = ?, hangup_cause = COALESCE(?, hangup_cause) WHERE call_uuid = ?";
        try (Connection c = ds.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, status);
            ps.setString(2, cause);
            ps.setString(3, callUuid);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("DB error setting call status", e);
        }
    }

    public void markVoicemail(String callUuid, String recordingUrl) {
        String sql = "UPDATE calls SET status = 'voicemail', recording_url = COALESCE(?, recording_url) WHERE call_uuid = ?";
        try (Connection c = ds.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, recordingUrl);
            ps.setString(2, callUuid);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("DB error marking voicemail", e);
        }
    }

    /** Final hangup event: duration + cause; a still-ringing call counts as no_answer. */
    public void finalizeHangup(String callUuid, Integer durationSec, String cause) {
        String sql = "UPDATE calls SET ended_at = now(), duration_sec = COALESCE(?, duration_sec), "
                + "hangup_cause = COALESCE(?, hangup_cause), "
                + "status = CASE WHEN status = 'in_progress' THEN 'no_answer' ELSE status END "
                + "WHERE call_uuid = ?";
        try (Connection c = ds.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            if (durationSec == null) ps.setNull(1, java.sql.Types.INTEGER); else ps.setInt(1, durationSec);
            ps.setString(2, cause);
            ps.setString(3, callUuid);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("DB error finalizing call " + callUuid, e);
        }
    }

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
        try (Connection c = ds.getConnection(); PreparedStatement ps = c.prepareStatement(sql.toString())) {
            for (int i = 0; i < params.size(); i++) ps.setObject(i + 1, params.get(i));
            try (ResultSet rs = ps.executeQuery()) {
                List<CallRecord> out = new ArrayList<>();
                while (rs.next()) out.add(map(rs));
                return out;
            }
        } catch (SQLException e) {
            throw new RuntimeException("DB error listing calls", e);
        }
    }

    public Map<String, Object> stats() {
        String sql = "SELECT count(*) AS total, "
                + "count(*) FILTER (WHERE direction = 'inbound') AS inbound, "
                + "count(*) FILTER (WHERE status = 'in_progress') AS live, "
                + "count(*) FILTER (WHERE created_at >= current_date) AS today, "
                + "count(*) FILTER (WHERE created_at >= current_date AND status = 'answered') AS answered_today, "
                + "count(*) FILTER (WHERE status = 'voicemail') AS voicemails "
                + "FROM calls";
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            Map<String, Object> m = new LinkedHashMap<>();
            if (rs.next()) {
                m.put("calls_total", rs.getLong("total"));
                m.put("calls_inbound", rs.getLong("inbound"));
                m.put("calls_live", rs.getLong("live"));
                m.put("calls_today", rs.getLong("today"));
                m.put("calls_answered_today", rs.getLong("answered_today"));
                m.put("calls_voicemail", rs.getLong("voicemails"));
            }
            return m;
        } catch (SQLException e) {
            throw new RuntimeException("DB error computing call stats", e);
        }
    }

    private static CallRecord map(ResultSet rs) throws SQLException {
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
        c.setStartedAt(AgentDao.toInstant(rs.getTimestamp("started_at")));
        c.setAnsweredAt(AgentDao.toInstant(rs.getTimestamp("answered_at")));
        c.setEndedAt(AgentDao.toInstant(rs.getTimestamp("ended_at")));
        int dur = rs.getInt("duration_sec");
        c.setDurationSec(rs.wasNull() ? null : dur);
        c.setHangupCause(rs.getString("hangup_cause"));
        c.setRecordingUrl(rs.getString("recording_url"));
        long cb = rs.getLong("callback_id");
        c.setCallbackId(rs.wasNull() ? null : cb);
        c.setCreatedAt(AgentDao.toInstant(rs.getTimestamp("created_at")));
        try {
            c.setAgentName(rs.getString("agent_name"));
        } catch (SQLException ignore) {
            // agent_name is only present in joined queries
        }
        return c;
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
