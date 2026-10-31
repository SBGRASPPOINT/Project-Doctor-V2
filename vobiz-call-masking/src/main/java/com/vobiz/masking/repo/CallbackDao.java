package com.vobiz.masking.repo;

import com.vobiz.masking.model.CallbackRequest;
import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Plain-JDBC DAO over callback_requests. */
public class CallbackDao {

    private static final String COLUMNS =
            "id, call_id, caller_number, voicemail_url, voicemail_duration_sec, status, assigned_agent_id, "
                    + "notes, outbound_call_uuid, created_at, closed_at";

    private final DataSource ds;

    public CallbackDao(DataSource ds) {
        this.ds = ds;
    }

    public CallbackRequest create(long callId, String callerNumber, String voicemailUrl, Integer durationSec) {
        String sql = "INSERT INTO callback_requests(call_id, caller_number, voicemail_url, voicemail_duration_sec, status) "
                + "VALUES (?, ?, ?, ?, 'pending') RETURNING " + COLUMNS;
        try (Connection c = ds.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, callId);
            ps.setString(2, callerNumber);
            ps.setString(3, voicemailUrl);
            if (durationSec == null) ps.setNull(4, java.sql.Types.INTEGER); else ps.setInt(4, durationSec);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return map(rs);
                throw new IllegalStateException("insert returned no row");
            }
        } catch (SQLException e) {
            throw new RuntimeException("DB error creating callback request", e);
        }
    }

    public Optional<CallbackRequest> findById(long id) {
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT cb.*, a.name AS assigned_agent_name FROM callback_requests cb "
                             + "LEFT JOIN agents a ON a.id = cb.assigned_agent_id WHERE cb.id = ?")) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(map(rs)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new RuntimeException("DB error finding callback " + id, e);
        }
    }

    public List<CallbackRequest> list(String status) {
        StringBuilder sql = new StringBuilder(
                "SELECT cb.*, a.name AS assigned_agent_name FROM callback_requests cb "
                        + "LEFT JOIN agents a ON a.id = cb.assigned_agent_id WHERE 1=1 ");
        if (status != null && !status.isBlank()) sql.append("AND cb.status = ? ");
        sql.append("ORDER BY cb.created_at DESC");
        try (Connection c = ds.getConnection(); PreparedStatement ps = c.prepareStatement(sql.toString())) {
            if (status != null && !status.isBlank()) ps.setString(1, status);
            try (ResultSet rs = ps.executeQuery()) {
                List<CallbackRequest> out = new ArrayList<>();
                while (rs.next()) out.add(map(rs));
                return out;
            }
        } catch (SQLException e) {
            throw new RuntimeException("DB error listing callbacks", e);
        }
    }

    public List<CallbackRequest> listByCallId(long callId) {
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT " + COLUMNS + " FROM callback_requests WHERE call_id = ? ORDER BY created_at DESC")) {
            ps.setLong(1, callId);
            try (ResultSet rs = ps.executeQuery()) {
                List<CallbackRequest> out = new ArrayList<>();
                while (rs.next()) out.add(map(rs));
                return out;
            }
        } catch (SQLException e) {
            throw new RuntimeException("DB error listing callbacks for call " + callId, e);
        }
    }

    public void markCalling(long id, long agentId, String outboundCallUuid) {
        String sql = "UPDATE callback_requests SET status = 'calling', assigned_agent_id = ?, outbound_call_uuid = ? WHERE id = ?";
        try (Connection c = ds.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, agentId);
            ps.setString(2, outboundCallUuid);
            ps.setLong(3, id);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("DB error marking callback calling", e);
        }
    }

    public void markCalledBack(long id) {
        String sql = "UPDATE callback_requests SET status = 'called_back', closed_at = now() WHERE id = ?";
        try (Connection c = ds.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, id);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("DB error marking callback called_back", e);
        }
    }

    /** Partial update from the ops console; null arguments are left unchanged. */
    public void update(long id, String status, String notes, Long assignedAgentId) {
        StringBuilder sql = new StringBuilder("UPDATE callback_requests SET ");
        List<Object> params = new ArrayList<>();
        if (status != null) {
            sql.append("status = ?, ");
            params.add(status);
            if (status.equals("called_back") || status.equals("closed")) sql.append("closed_at = now(), ");
        }
        if (notes != null) { sql.append("notes = ?, "); params.add(notes); }
        if (assignedAgentId != null) { sql.append("assigned_agent_id = ?, "); params.add(assignedAgentId); }
        if (params.isEmpty()) return;
        sql.setLength(sql.length() - 2);
        sql.append(" WHERE id = ?");
        params.add(id);
        try (Connection c = ds.getConnection(); PreparedStatement ps = c.prepareStatement(sql.toString())) {
            for (int i = 0; i < params.size(); i++) ps.setObject(i + 1, params.get(i));
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("DB error updating callback " + id, e);
        }
    }

    public Map<String, Object> stats() {
        String sql = "SELECT count(*) AS total, count(*) FILTER (WHERE status = 'pending') AS pending FROM callback_requests";
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            Map<String, Object> m = new LinkedHashMap<>();
            if (rs.next()) {
                m.put("callbacks_total", rs.getLong("total"));
                m.put("callbacks_pending", rs.getLong("pending"));
            }
            return m;
        } catch (SQLException e) {
            throw new RuntimeException("DB error computing callback stats", e);
        }
    }

    private static CallbackRequest map(ResultSet rs) throws SQLException {
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
        cb.setCreatedAt(AgentDao.toInstant(rs.getTimestamp("created_at")));
        cb.setClosedAt(AgentDao.toInstant(rs.getTimestamp("closed_at")));
        try {
            cb.setAssignedAgentName(rs.getString("assigned_agent_name"));
        } catch (SQLException ignore) {
            // only present in joined queries
        }
        return cb;
    }
}
