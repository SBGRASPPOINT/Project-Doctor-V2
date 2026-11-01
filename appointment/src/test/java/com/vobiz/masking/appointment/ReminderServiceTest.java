package com.vobiz.masking.appointment;

import com.vobiz.masking.appointment.model.Reminder;
import com.vobiz.masking.appointment.service.ReminderService;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReminderServiceTest {

    private static final ZoneId KOLKATA = ZoneId.of("Asia/Kolkata");
    private static final LocalTime Q_START = LocalTime.of(8, 0);
    private static final LocalTime Q_END = LocalTime.of(20, 0);

    private static Instant ist(LocalDate date, int hour, int minute) {
        return date.atTime(hour, minute).atZone(KOLKATA).toInstant();
    }

    @Test
    void quietHoursDetection() {
        LocalDate tue = LocalDate.of(2026, 11, 3);
        assertFalse(ReminderService.outsideAllowedWindow(ist(tue, 9, 0), KOLKATA, Q_START, Q_END));
        assertTrue(ReminderService.outsideAllowedWindow(ist(tue, 7, 59), KOLKATA, Q_START, Q_END));
        assertTrue(ReminderService.outsideAllowedWindow(ist(tue, 20, 0), KOLKATA, Q_START, Q_END));
        assertTrue(ReminderService.outsideAllowedWindow(ist(tue, 23, 30), KOLKATA, Q_START, Q_END));
    }

    @Test
    void rollForwardMovesIntoNextAllowedWindow() {
        LocalDate tue = LocalDate.of(2026, 11, 3);
        // 07:00 IST → same day 08:00 IST
        assertEquals(ist(tue, 8, 0),
                ReminderService.rollIntoAllowedWindow(ist(tue, 7, 0), KOLKATA, Q_START, Q_END));
        // 21:00 IST → next day 08:00 IST
        assertEquals(ist(tue.plusDays(1), 8, 0),
                ReminderService.rollIntoAllowedWindow(ist(tue, 21, 0), KOLKATA, Q_START, Q_END));
        // inside the window → unchanged
        assertEquals(ist(tue, 12, 0),
                ReminderService.rollIntoAllowedWindow(ist(tue, 12, 0), KOLKATA, Q_START, Q_END));
    }

    @Test
    void scheduleForComputesT24hAndT2hRelativeToAppointment() {
        LocalDate wed = LocalDate.of(2026, 11, 4);
        Instant appt = ist(wed, 10, 0);                 // appointment Wed 10:00 IST
        Instant now = ist(LocalDate.of(2026, 11, 3), 9, 0); // now Tue 09:00 IST
        Map<String, Instant> plan = ReminderService.scheduleFor(appt, now, true, 1440, true, 120,
                KOLKATA, Q_START, Q_END);
        assertEquals(2, plan.size());
        assertEquals(appt.minusSeconds(1440 * 60L), plan.get(Reminder.KIND_T24H));  // Tue 10:00
        assertEquals(appt.minusSeconds(120 * 60L), plan.get(Reminder.KIND_T2H));     // Wed 08:00
    }

    @Test
    void scheduleForDropsRemindersInThePastAndDisabledKinds() {
        LocalDate wed = LocalDate.of(2026, 11, 4);
        Instant appt = ist(wed, 10, 0);
        Instant now = ist(wed, 9, 30);                  // only 30 min before the appointment
        Map<String, Instant> plan = ReminderService.scheduleFor(appt, now, true, 1440, true, 120,
                KOLKATA, Q_START, Q_END);
        assertFalse(plan.containsKey(Reminder.KIND_T24H), "T-24h is in the past and must be dropped");
        assertTrue(plan.containsKey(Reminder.KIND_T2H));
        Map<String, Instant> none = ReminderService.scheduleFor(appt, now, false, 1440, false, 120,
                KOLKATA, Q_START, Q_END);
        assertTrue(none.isEmpty());
    }

    @Test
    void scheduleForRollsQuietHours() {
        LocalDate wed = LocalDate.of(2026, 11, 4);
        Instant appt = ist(wed, 8, 30);                 // appointment 08:30 IST
        Instant now = ist(LocalDate.of(2026, 11, 2), 12, 0);
        Map<String, Instant> plan = ReminderService.scheduleFor(appt, now, true, 0 + 120, false, 120,
                KOLKATA, Q_START, Q_END);
        // T-2h = 06:30 IST → quiet hours → rolls to 08:00 IST same day
        assertEquals(ist(wed, 8, 0), plan.get(Reminder.KIND_T24H));
    }
}
