package com.vobiz.masking.appointment;

import com.vobiz.masking.appointment.model.Appointment;
import com.vobiz.masking.appointment.service.AppointmentService;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The lifecycle rules are pure, so they are testable without a database — that is exactly why
 * the status writer was refactored into {@link AppointmentService#canTransition}.
 *
 * Regression guards: a cancelled appointment used to be movable back to "confirmed" (which
 * resurrected it with slot_key = NULL, defeating the UNIQUE slot invariant), and
 * PATCH .../{"status":"cancelled"} used to skip the slot release and reminder suppression.
 */
class AppointmentStateMachineTest {

    @Test
    void openStatusesCanBeConfirmedRescheduledOrClosed() {
        for (String from : new String[]{Appointment.STATUS_REQUESTED, Appointment.STATUS_CONFIRMED,
                Appointment.STATUS_RESCHEDULED}) {
            assertTrue(AppointmentService.canTransition(from, Appointment.STATUS_CONFIRMED), from);
            assertTrue(AppointmentService.canTransition(from, Appointment.STATUS_RESCHEDULED), from);
            assertTrue(AppointmentService.canTransition(from, Appointment.STATUS_COMPLETED), from);
            assertTrue(AppointmentService.canTransition(from, Appointment.STATUS_NO_SHOW), from);
            assertTrue(AppointmentService.canTransition(from, Appointment.STATUS_CANCELLED), from);
        }
    }

    @Test
    void closedStatusesAreTerminal() {
        for (String from : new String[]{Appointment.STATUS_CANCELLED, Appointment.STATUS_COMPLETED,
                Appointment.STATUS_NO_SHOW}) {
            for (String to : new String[]{Appointment.STATUS_REQUESTED, Appointment.STATUS_CONFIRMED,
                    Appointment.STATUS_RESCHEDULED, Appointment.STATUS_COMPLETED, Appointment.STATUS_NO_SHOW}) {
                assertFalse(AppointmentService.canTransition(from, to), from + " -> " + to);
            }
        }
    }

    @Test
    void cancelledCannotResurrectButStaysCancelled() {
        assertFalse(AppointmentService.canTransition(Appointment.STATUS_CANCELLED, Appointment.STATUS_CONFIRMED));
        assertTrue(AppointmentService.canTransition(Appointment.STATUS_CANCELLED, Appointment.STATUS_CANCELLED));
    }

    @Test
    void unknownOrNullStatusesAreRejected() {
        assertFalse(AppointmentService.canTransition("booked", Appointment.STATUS_CONFIRMED));
        assertFalse(AppointmentService.canTransition(null, Appointment.STATUS_CONFIRMED));
        assertFalse(AppointmentService.canTransition(Appointment.STATUS_CONFIRMED, null));
    }
}
