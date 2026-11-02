package com.vobiz.masking.appointment;

import com.vobiz.masking.appointment.service.AvailabilityService;
import com.vobiz.masking.appointment.service.AvailabilityService.Slot;
import com.vobiz.masking.appointment.service.AvailabilityService.Template;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AvailabilityServiceTest {

    private static final ZoneId KOLKATA = ZoneId.of("Asia/Kolkata");

    private static Instant at(LocalDate date, int hour, int minute) {
        return date.atTime(hour, minute).atZone(KOLKATA).toInstant();
    }

    @Test
    void templateIsSplitIntoSlots() {
        // Tuesday 2026-11-03, 10:00–11:00 IST, 15-minute slots → 4 slots
        LocalDate tue = LocalDate.of(2026, 11, 3);
        List<Slot> slots = AvailabilityService.generateDaySlots(
                tue, KOLKATA,
                List.of(new Template(LocalTime.of(10, 0), LocalTime.of(11, 0), 15)),
                List.of(), List.of(),
                at(tue, 8, 0),   // now = 08:00 IST, 60 min lead
                60, 30);
        assertEquals(4, slots.size());
        assertEquals(at(tue, 10, 0), slots.get(0).start());
        assertEquals(at(tue, 10, 15), slots.get(1).start());
        assertEquals(at(tue, 10, 45), slots.get(3).start());
        assertEquals(at(tue, 11, 0), slots.get(3).end());
    }

    @Test
    void pastAndLeadTimeSlotsAreDropped() {
        LocalDate tue = LocalDate.of(2026, 11, 3);
        // now = 10:07 IST, 60-minute lead → earliest bookable start is 11:07 → only nothing in 10:00-11:00 window
        List<Slot> slots = AvailabilityService.generateDaySlots(
                tue, KOLKATA,
                List.of(new Template(LocalTime.of(10, 0), LocalTime.of(12, 0), 30)),
                List.of(), List.of(),
                at(tue, 10, 7), 60, 30);
        assertTrue(slots.stream().allMatch(s -> !s.start().isBefore(at(tue, 11, 7))));
    }

    @Test
    void existingAppointmentBlocksOnlyItsOwnSlot() {
        LocalDate tue = LocalDate.of(2026, 11, 3);
        List<Instant[]> busy = java.util.Collections.singletonList(
                new Instant[]{at(tue, 10, 15), at(tue, 10, 30)});
        List<Slot> slots = AvailabilityService.generateDaySlots(
                tue, KOLKATA,
                List.of(new Template(LocalTime.of(10, 0), LocalTime.of(11, 0), 15)),
                busy, List.of(),
                at(tue, 8, 0), 60, 30);
        assertEquals(List.of(at(tue, 10, 0), at(tue, 10, 30), at(tue, 10, 45)),
                slots.stream().map(Slot::start).toList());
    }

    @Test
    void timeOffBlockExcludesOverlappingSlots() {
        LocalDate tue = LocalDate.of(2026, 11, 3);
        List<Instant[]> timeOff = java.util.Collections.singletonList(
                new Instant[]{at(tue, 10, 30), at(tue, 11, 30)});
        List<Slot> slots = AvailabilityService.generateDaySlots(
                tue, KOLKATA,
                List.of(new Template(LocalTime.of(10, 0), LocalTime.of(12, 0), 15)),
                List.of(), timeOff,
                at(tue, 8, 0), 60, 30);
        assertEquals(List.of(at(tue, 10, 0), at(tue, 10, 15), at(tue, 11, 30), at(tue, 11, 45)),
                slots.stream().map(Slot::start).toList());
    }

    @Test
    void daysOutsideHorizonAndPastDaysYieldNothing() {
        LocalDate tue = LocalDate.of(2026, 11, 3);
        Instant now = at(tue, 8, 0);
        assertTrue(AvailabilityService.generateDaySlots(
                tue.minusDays(1), KOLKATA,
                List.of(new Template(LocalTime.of(10, 0), LocalTime.of(11, 0), 15)),
                List.of(), List.of(), now, 60, 30).isEmpty());
        assertTrue(AvailabilityService.generateDaySlots(
                tue.plusDays(31), KOLKATA,
                List.of(new Template(LocalTime.of(10, 0), LocalTime.of(11, 0), 15)),
                List.of(), List.of(), now, 60, 30).isEmpty());
        assertFalse(AvailabilityService.generateDaySlots(
                tue.plusDays(30), KOLKATA,
                List.of(new Template(LocalTime.of(10, 0), LocalTime.of(11, 0), 15)),
                List.of(), List.of(), now, 60, 30).isEmpty());
    }

    @Test
    void overlappingTemplatesAreDeduped() {
        LocalDate tue = LocalDate.of(2026, 11, 3);
        List<Slot> slots = AvailabilityService.generateDaySlots(
                tue, KOLKATA,
                List.of(new Template(LocalTime.of(10, 0), LocalTime.of(11, 0), 30),
                        new Template(LocalTime.of(10, 0), LocalTime.of(11, 0), 30)),
                List.of(), List.of(),
                at(tue, 8, 0), 60, 30);
        assertEquals(2, slots.size());
    }

    @Test
    void slotLabelRendersInClinicZone() {
        Slot s = new Slot(at(LocalDate.of(2026, 11, 3), 10, 30), at(LocalDate.of(2026, 11, 3), 10, 45));
        assertEquals("Tue 3 Nov, 10:30 AM", s.label(KOLKATA));
    }
}
