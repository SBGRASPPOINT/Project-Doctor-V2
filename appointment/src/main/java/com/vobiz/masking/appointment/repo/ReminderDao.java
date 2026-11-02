package com.vobiz.masking.appointment.repo;

import com.vobiz.masking.appointment.model.Reminder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Reminders store. Single-row operations via JPA ({@link ReminderRepository}); the due-scan
 * and dashboard aggregates via {@link JdbcTemplate}.
 */
public class ReminderDao {

    private final ReminderRepository repo;
    private final JdbcTemplate jdbc;

    public ReminderDao(ReminderRepository repo, JdbcTemplate jdbc) {
        this.repo = repo;
        this.jdbc = jdbc;
    }

    public ReminderRepository repo() {
        return repo;
    }

    /** Dashboard counters. "today" uses the clinic timezone, not the DB session zone. */
    public Map<String, Object> stats(ZoneId zone) {
        String sql = "SELECT count(*) AS total, "
                + "count(*) FILTER (WHERE status = 'pending') AS pending, "
                + "count(*) FILTER (WHERE status = 'placed') AS placed, "
                + "count(*) FILTER (WHERE status = 'answered_confirmed') AS confirmed, "
                // 'answered_reschedule_requested': patient asked for reception, nothing rescheduled yet.
                + "count(*) FILTER (WHERE status = 'answered_reschedule_requested') AS rescheduled, "
                + "count(*) FILTER (WHERE status = 'answered_cancelled') AS cancelled, "
                + "count(*) FILTER (WHERE status = 'answered') AS answered, "
                + "count(*) FILTER (WHERE status = 'no_answer') AS no_answer, "
                + "count(*) FILTER (WHERE last_attempt_at >= (now() AT TIME ZONE ?)::date) AS sent_today "
                + "FROM reminders";
        Map<String, Object> row = jdbc.queryForMap(sql, zone.getId());
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("reminders_total", lng(row.get("total")));
        m.put("reminders_pending", lng(row.get("pending")));
        m.put("reminders_placed", lng(row.get("placed")));
        m.put("reminders_confirmed", lng(row.get("confirmed")));
        m.put("reminders_rescheduled", lng(row.get("rescheduled")));
        m.put("reminders_cancelled", lng(row.get("cancelled")));
        m.put("reminders_answered", lng(row.get("answered")));
        m.put("reminders_no_answer", lng(row.get("no_answer")));
        m.put("reminders_sent_today", lng(row.get("sent_today")));
        return m;
    }

    private static long lng(Object v) {
        return v instanceof Number n ? n.longValue() : 0L;
    }

    static final RowMapper<Reminder> ROW_MAPPER = (rs, rowNum) -> {
        Reminder r = new Reminder();
        r.setId(rs.getLong("id"));
        r.setAppointmentId(rs.getLong("appointment_id"));
        r.setKind(rs.getString("kind"));
        r.setScheduledAt(toInstant(rs.getTimestamp("scheduled_at")));
        r.setChannel(rs.getString("channel"));
        r.setStatus(rs.getString("status"));
        r.setAttempts(rs.getInt("attempts"));
        r.setLastAttemptAt(toInstant(rs.getTimestamp("last_attempt_at")));
        r.setOutboundCallUuid(rs.getString("outbound_call_uuid"));
        r.setResponseDigit(rs.getString("response_digit"));
        r.setErrorText(rs.getString("error_text"));
        r.setCreatedAt(toInstant(rs.getTimestamp("created_at")));
        return r;
    };

    private static Instant toInstant(Timestamp t) {
        return t == null ? null : t.toInstant();
    }
}
