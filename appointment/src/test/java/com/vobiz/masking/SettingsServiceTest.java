package com.vobiz.masking;

import com.vobiz.masking.repo.SettingsDao;
import com.vobiz.masking.service.SettingsService;
import com.vobiz.masking.util.ApiException;
import org.junit.jupiter.api.Test;

import java.time.LocalTime;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the appointment/IVR/reminder accessors that the service layer depends on (and that
 * were missing entirely, so the module did not compile) plus the settings validation rules.
 */
class SettingsServiceTest {

    /** In-memory SettingsDao: no repository, no Spring, no database. */
    private static final class FakeDao extends SettingsDao {
        private final Map<String, String> values = new LinkedHashMap<>();

        FakeDao(Map<String, String> seed) {
            super(null);
            values.putAll(seed);
        }

        @Override
        public String get(String key, String def) {
            String v = values.get(key);
            return v == null ? def : v;
        }

        @Override
        public Map<String, String> getAll() {
            return new LinkedHashMap<>(values);
        }

        @Override
        public void put(String key, String value) {
            values.put(key, value);
        }
    }

    private static SettingsService service(Map<String, String> seed) {
        return new SettingsService(new FakeDao(seed));
    }

    @Test
    void accessorsFallBackToSeededDefaults() {
        SettingsService s = service(Map.of());
        assertEquals(ZoneId.of("Asia/Kolkata"), s.clinicZone());
        assertEquals(LocalTime.of(8, 0), s.reminderQuietStart());
        assertEquals(LocalTime.of(20, 0), s.reminderQuietEnd());
        assertEquals(15, s.slotGranularityMin());
        assertEquals(30, s.bookingHorizonDays());
        assertEquals(60, s.minBookingLeadMinutes());
        assertEquals(3, s.maxSlotsOffered());
        assertEquals(4, s.allowSelfCancelHours());
        assertEquals(2, s.reminderRetryCount());
        assertEquals(60000, s.reminderScanMs());
        assertEquals("City Care Clinic", s.clinicName());
        assertTrue(s.ivrEnabled());
        assertTrue(s.allowSelfBooking());
        assertTrue(s.changeNotificationEnabled());
        assertFalse(s.smsFallbackEnabled());
    }

    @Test
    void storedValuesWinOverDefaults() {
        SettingsService s = service(Map.of(
                "clinic_timezone", "Asia/Dubai",
                "reminder_quiet_start", "09:30",
                "reminder_quiet_end", "18:15",
                "reminder_scan_ms", "30000",
                "booking_horizon_days", "7"));
        assertEquals(ZoneId.of("Asia/Dubai"), s.clinicZone());
        assertEquals(LocalTime.of(9, 30), s.reminderQuietStart());
        assertEquals(LocalTime.of(18, 15), s.reminderQuietEnd());
        assertEquals(30000, s.reminderScanMs());
        assertEquals(7, s.bookingHorizonDays());
    }

    @Test
    void garbageValuesDoNotBlowUpTheCallFlow() {
        SettingsService s = service(Map.of(
                "clinic_timezone", "Mars/Olympus",
                "reminder_quiet_start", "not-a-time",
                "reminder_scan_ms", "abc"));
        // Falls back instead of throwing inside a live call handler.
        assertEquals(ZoneId.systemDefault(), s.clinicZone());
        assertEquals(LocalTime.of(8, 0), s.reminderQuietStart());
        assertEquals(60000, s.reminderScanMs());
    }

    @Test
    void m1PrefersTheSettingsValueAndFallsBack() {
        assertEquals("+911234567890", service(Map.of("m1_number", "+911234567890")).m1("+10000000000"));
        assertEquals("+10000000000", service(Map.of()).m1("+10000000000"));
        assertEquals("+10000000000", service(Map.of("m1_number", "  ")).m1("+10000000000"));
    }

    @Test
    void updateValidatesKnownKeys() {
        SettingsService s = service(Map.of());
        assertThrows(ApiException.class, () -> s.update(Map.of("nope", "1")));
        assertThrows(ApiException.class, () -> s.update(Map.of("m1_number", "12345")));
        assertThrows(ApiException.class, () -> s.update(Map.of("reminder_scan_ms", "10")));
        assertThrows(ApiException.class, () -> s.update(Map.of("clinic_timezone", "Not/AZone")));
        assertThrows(ApiException.class, () -> s.update(Map.of("reminder_quiet_start", "25:00")));
        assertThrows(ApiException.class, () -> s.update(Map.of("routing_mode", "random")));
        // valid payload is applied
        Map<String, String> after = s.update(Map.of("clinic_name", "Salt Lake Clinic", "reminder_scan_ms", "15000"));
        assertEquals("Salt Lake Clinic", after.get("clinic_name"));
        assertEquals("15000", after.get("reminder_scan_ms"));
    }
}
