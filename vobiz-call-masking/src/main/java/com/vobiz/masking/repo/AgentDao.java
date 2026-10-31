package com.vobiz.masking.repo;

import com.vobiz.masking.model.Agent;
import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Plain-JDBC DAO over the agents table. */
public class AgentDao {

    private static final String COLUMNS = "id, name, phone_e164, priority, status, active, created_at";

    private final DataSource ds;

    public AgentDao(DataSource ds) {
        this.ds = ds;
    }

    public List<Agent> listAll() {
        String sql = "SELECT " + COLUMNS + " FROM agents ORDER BY priority ASC, id ASC";
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            List<Agent> out = new ArrayList<>();
            while (rs.next()) out.add(map(rs));
            return out;
        } catch (SQLException e) {
            throw new RuntimeException("DB error listing agents", e);
        }
    }

    public List<Agent> listActive() {
        String sql = "SELECT " + COLUMNS + " FROM agents WHERE active = TRUE ORDER BY priority ASC, id ASC";
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            List<Agent> out = new ArrayList<>();
            while (rs.next()) out.add(map(rs));
            return out;
        } catch (SQLException e) {
            throw new RuntimeException("DB error listing active agents", e);
        }
    }

    public Optional<Agent> findById(long id) {
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement("SELECT " + COLUMNS + " FROM agents WHERE id = ?")) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(map(rs)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new RuntimeException("DB error finding agent " + id, e);
        }
    }

    public Optional<Agent> findByPhone(String phone) {
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement("SELECT " + COLUMNS + " FROM agents WHERE phone_e164 = ?")) {
            ps.setString(1, phone);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(map(rs)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new RuntimeException("DB error finding agent by phone", e);
        }
    }

    public Agent create(String name, String phone, int priority) {
        String sql = "INSERT INTO agents(name, phone_e164, priority, status) VALUES (?, ?, ?, 'available') "
                + "RETURNING " + COLUMNS;
        try (Connection c = ds.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, name);
            ps.setString(2, phone);
            ps.setInt(3, priority);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return map(rs);
                throw new IllegalStateException("insert returned no row");
            }
        } catch (SQLException e) {
            throw new RuntimeException("DB error creating agent", e);
        }
    }

    /** Dynamic partial update; null arguments are left unchanged. Returns false if nothing to update. */
    public boolean update(long id, String name, String phone, Integer priority, String status, Boolean active) {
        StringBuilder sql = new StringBuilder("UPDATE agents SET ");
        List<Object> params = new ArrayList<>();
        if (name != null) { sql.append("name = ?, "); params.add(name); }
        if (phone != null) { sql.append("phone_e164 = ?, "); params.add(phone); }
        if (priority != null) { sql.append("priority = ?, "); params.add(priority); }
        if (status != null) { sql.append("status = ?, "); params.add(status); }
        if (active != null) { sql.append("active = ?, "); params.add(active); }
        if (params.isEmpty()) return false;
        sql.setLength(sql.length() - 2);
        sql.append(" WHERE id = ?");
        params.add(id);
        try (Connection c = ds.getConnection(); PreparedStatement ps = c.prepareStatement(sql.toString())) {
            for (int i = 0; i < params.size(); i++) ps.setObject(i + 1, params.get(i));
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            throw new RuntimeException("DB error updating agent " + id, e);
        }
    }

    public boolean setStatus(long id, String status) {
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement("UPDATE agents SET status = ? WHERE id = ?")) {
            ps.setString(1, status);
            ps.setLong(2, id);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            throw new RuntimeException("DB error setting agent status", e);
        }
    }

    /** Soft delete: the number leaves the rotation but history is kept. */
    public boolean deactivate(long id) {
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement("UPDATE agents SET active = FALSE WHERE id = ?")) {
            ps.setLong(1, id);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            throw new RuntimeException("DB error deactivating agent " + id, e);
        }
    }

    private static Agent map(ResultSet rs) throws SQLException {
        Agent a = new Agent();
        a.setId(rs.getLong("id"));
        a.setName(rs.getString("name"));
        a.setPhoneE164(rs.getString("phone_e164"));
        a.setPriority(rs.getInt("priority"));
        a.setStatus(rs.getString("status"));
        a.setActive(rs.getBoolean("active"));
        a.setCreatedAt(toInstant(rs.getTimestamp("created_at")));
        return a;
    }

    static Instant toInstant(Timestamp t) {
        return t == null ? null : t.toInstant();
    }
}
