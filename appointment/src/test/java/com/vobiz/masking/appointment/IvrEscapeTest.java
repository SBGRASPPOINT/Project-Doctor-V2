package com.vobiz.masking.appointment;

import com.vobiz.masking.appointment.service.AppointmentIvrService;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IvrEscapeTest {

    @Test
    void twoNoInputsEscapeToHuman() {
        assertFalse(AppointmentIvrService.shouldEscapeToHuman(1, 3, 12));
        assertTrue(AppointmentIvrService.shouldEscapeToHuman(2, 3, 12));
    }

    @Test
    void hopCapEscapesToHuman() {
        assertFalse(AppointmentIvrService.shouldEscapeToHuman(0, 12, 12));
        assertTrue(AppointmentIvrService.shouldEscapeToHuman(0, 13, 12));
    }

    @Test
    void normalNavigationDoesNotEscape() {
        assertFalse(AppointmentIvrService.shouldEscapeToHuman(0, 1, 12));
        assertFalse(AppointmentIvrService.shouldEscapeToHuman(1, 5, 12));
    }
}
