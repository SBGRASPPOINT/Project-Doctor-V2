package com.vobiz.masking.appointment.repo;

import com.vobiz.masking.appointment.model.Appointment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Appointments store. Single-row operations go through JPA ({@link AppointmentRepository});
 * the filtered list view (joins for display names, ILIKE search) and dashboard aggregates
 * stay native SQL via {@link JdbcTemplate}, same style as {@code CallDao}.
 */
public class AppointmentDao {

    private final AppointmentRepository repo;
    private final JdbcTemplate jdbc;

    public AppointmentDao(AppointmentRepository repo, JdbcTemplate jdbc) {
        this.repo = repo;
        this.jdbc = jdbc;
    }

    public AppointmentRepository repo() {
        return repo;
    }

    /** Filtered list: status / doctor / patient / date range / free text (ILIKE on patient name, phone, reference). */
    public List<Appointment> list(String status, Long doctorId, Long patientId, String fromDate, String toDate, String q, int limit) {
        StringBuilder sql = new StringBuilder(
                "SELECT a.*, d.name AS doctor_name, dep.name AS department_name "
                        + "FROM appointments a "
                        + "LEFT JOIN doctors d ON d.id = a.doctor_id "
                        + "LEFT JOIN departments dep ON dep.id = a.department_id "
                        + "WHERE 1=1 ");
        List<Object> params = new ArrayList<>();
        if (status != null && !status.isBlank()) { sql.append("AND a.status = ? "); params.add(status); }
        if (doctorId != null) { sql.append("AND a.doctor_id = ? "); params.add(doctorId); }
        if (patientId != null) { sql.append("AND a.patient_id = ? "); params.add(patientId); }
        if (fromDate != null && !fromDate.isBlank()) { sql.append("AND a.starts_at >= ?::date "); params.add(fromDate); }
        if (toDate != null && !toDate.isBlank()) { sql.append("AND a.starts_at < (?::date + 1) "); params.add(toDate); }
        if (q != null && !q.isBlank()) {
            sql.append("AND (lower(a.patient_name) LIKE lower(?) OR a.patient_phone_e164 LIKE ? OR lower(a.reference_code) LIKE lower(?)) ");
            params.add("%" + q + "%");
            params.add("%" + q + "%");
            params.add("%" + q + "%");
        }
        sql.append("ORDER BY a.starts_at DESC LIMIT ?");
        params.add(limit);
        return jdbc.query(sql.toString(), ROW_MAPPER, params.toArray());
    }

    /** Dashboard counters (native FILTER aggregates). */
    public Map<String, Object> stats() {
        String sql = "SELECT count(*) AS total, "
                + "count(*) FILTER (WHERE starts_at >= current_date AND starts_at < (current_date + 1)) AS today, "
                + "count(*) FILTER (WHERE starts_at >= date_trunc('week', now())) AS week, "
                + "count(*) FILTER (WHERE status = 'confirmed') AS confirmed, "
                + "count(*) FILTER (WHERE status = 'cancelled') AS cancelled, "
                + "count(*) FILTER (WHERE status = 'no_show') AS no_show, "
                + "count(*) FILTER (WHERE source = 'ivr_call') AS by_ivr "
                + "FROM appointments";
        Map<String, Object> row = jdbc.queryForMap(sql);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("appointments_total", lng(row.get("total")));
        m.put("appointments_today", lng(row.get("today")));
        m.put("appointments_week", lng(row.get("week")));
        m.put("appointments_confirmed", lng(row.get("confirmed")));
        m.put("appointments_cancelled", lng(row.get("cancelled")));
        m.put("appointments_no_show", lng(row.get("no_show")));
        m.put("appointments_by_ivr", lng(row.get("by_ivr")));
        return m;
    }

    private static long lng(Object v) {
        return v instanceof Number n ? n.longValue() : 0L;
    }

    private static final RowMapper<Appointment> ROW_MAPPER = (rs, rowNum) -> {
        Appointment a = new Appointment();
        a.setId(rs.getLong("id"));
        a.setReferenceCode(rs.getString("reference_code"));
        a.setPatientId(rs.getLong("patient_id"));
        a.setPatientName(rs.getString("patient_name"));
        a.setPatientPhoneE164(rs.getString("patient_phone_e164"));
        long doctorId = rs.getLong("doctor_id");
        a.setDoctorId(rs.wasNull() ? null : doctorId);
        long depId = rs.getLong("department_id");
        a.setDepartmentId(rs.wasNull() ? null : depId);
        a.setStartsAt(toInstant(rs.getTimestamp("starts_at")));
        a.setEndsAt(toInstant(rs.getTimestamp("ends_at")));
        int dur = rs.getInt("duration_min");
        a.setDurationMin(rs.wasNull() ? null : dur);
        a.setStatus(rs.getString("status"));
        a.setSource(rs.getString("source"));
        a.setSlotKey(rs.getString("slot_key"));
        long callId = rs.getLong("created_via_call_id");
        a.setCreatedViaCallId(rs.wasNull() ? null : callId);
        a.setReason(rs.getString("reason"));
        a.setNotes(rs.getString("notes"));
        a.setCancellationReason(rs.getString("cancellation_reason"));
        a.setNeedsRebooking(rs.getBoolean("needs_rebooking"));
        a.setConfirmedAt(toInstant(rs.getTimestamp("confirmed_at")));
        a.setCancelledAt(toInstant(rs.getTimestamp("cancelled_at")));
        a.setCompletedAt(toInstant(rs.getTimestamp("completed_at")));
        a.setVersion(rs.getInt("version"));
        a.setCreatedAt(toInstant(rs.getTimestamp("created_at")));
        a.setUpdatedAt(toInstant(rs.getTimestamp("updated_at")));
        a.setDoctorName(rs.getString("doctor_name"));
        a.setDepartmentName(rs.getString("department_name"));
        return a;
    };

    private static Instant toInstant(Timestamp t) {
        return t == null ? null : t.toInstant();
    }
}
