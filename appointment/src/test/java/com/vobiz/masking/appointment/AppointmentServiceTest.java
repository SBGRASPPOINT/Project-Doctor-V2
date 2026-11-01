package com.vobiz.masking.appointment;

import com.vobiz.masking.appointment.service.AppointmentService;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AppointmentServiceTest {

    @Test
    void referenceCodesHaveExpectedFormat() {
        for (int i = 0; i < 200; i++) {
            String ref = AppointmentService.newReferenceCode();
            assertTrue(ref.matches("APT-[A-Z2-9]{4}"), ref);
        }
    }

    @Test
    void referenceCodesAreUniqueAndUseUnambiguousAlphabet() {
        Set<String> refs = new HashSet<>();
        for (int i = 0; i < 2000; i++) refs.add(AppointmentService.newReferenceCode());
        assertTrue(refs.size() > 1900, "reference codes should be (nearly) unique, got " + refs.size());
        assertTrue(AppointmentService.isUnambiguousAlphabet("ABCDEFGHJKMNPQRSTUVWXYZ23456789"));
    }

    @Test
    void slotKeyIsDoctorPlusStart() {
        Instant t = Instant.parse("2026-11-03T05:00:00Z");
        assertEquals("7|2026-11-03T05:00:00Z", AppointmentService.slotKey(7L, t));
    }
}
