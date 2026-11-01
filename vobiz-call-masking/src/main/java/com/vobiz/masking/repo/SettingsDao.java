package com.vobiz.masking.repo;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Map;

/** Key/value settings store (ops-console editable). */
public class SettingsDao {


    private final DataSource ds;

    public SettingsDao(DataSource ds) {
        this.ds = ds;
    }

    public Map<String, String> getAll() {
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement("SELECT key, value FROM settings ORDER BY key");
             ResultSet rs = ps.executeQuery()) {
            Map<String, String> m = new LinkedHashMap<>();
            while (rs.next()) m.put(rs.getString("key"), rs.getString("value"));
            return m;
        } catch (SQLException e) {
            throw new RuntimeException("DB error reading settings", e);
        }
    }

    public String get(String key, String def) {
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement("SELECT value FROM settings WHERE key = ?")) {
            ps.setString(1, key);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString("value") : def;
            }
        } catch (SQLException e) {
            throw new RuntimeException("DB error reading setting " + key, e);
        }
    }

    public int getInt(String key, int def) {
        try {
            return Integer.parseInt(get(key, String.valueOf(def)).trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    public boolean getBool(String key, boolean def) {
        return Boolean.parseBoolean(get(key, String.valueOf(def)));
    }

    public void put(String key, String value) {
        String sql = "INSERT INTO settings(key, value) VALUES (?, ?) ON CONFLICT (key) DO UPDATE SET value = EXCLUDED.value";
        try (Connection c = ds.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, key);
            ps.setString(2, value);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("DB error writing setting " + key, e);
        }
    }
}
