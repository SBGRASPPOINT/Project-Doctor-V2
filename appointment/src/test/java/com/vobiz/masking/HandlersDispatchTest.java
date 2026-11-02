package com.vobiz.masking;

import com.vobiz.masking.appointment.repo.AppointmentDao;
import com.vobiz.masking.appointment.repo.AppointmentEventRepository;
import com.vobiz.masking.appointment.repo.MessageTemplateRepository;
import com.vobiz.masking.appointment.repo.ReminderDao;
import com.vobiz.masking.appointment.service.AppointmentIvrService;
import com.vobiz.masking.appointment.service.AppointmentService;
import com.vobiz.masking.appointment.service.AvailabilityService;
import com.vobiz.masking.appointment.service.DepartmentService;
import com.vobiz.masking.appointment.service.DoctorService;
import com.vobiz.masking.appointment.service.PatientDirectoryService;
import com.vobiz.masking.appointment.service.ReminderService;
import com.vobiz.masking.appointment.service.ScheduleService;
import com.vobiz.masking.repo.CallDao;
import com.vobiz.masking.repo.CallbackDao;
import com.vobiz.masking.service.AgentService;
import com.vobiz.masking.service.CallFlowService;
import com.vobiz.masking.service.CallbackService;
import com.vobiz.masking.service.SettingsService;
import com.vobiz.masking.web.Handlers;
import com.vobiz.masking.web.HttpResult;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Exercises the REST dispatcher — 500 lines of routing that had no test at all, which is how an
 * unhandled exception used to be turned into {@code "Internal error: <driver/sql text>"} for any
 * unauthenticated caller.
 *
 * Everything is mocked, so this stays a plain JUnit test with no Spring context and no database.
 */
class HandlersDispatchTest {

    private static Handlers handlers(SettingsService settings, DoctorService doctorService) {
        return new Handlers(
                mock(AgentService.class), mock(CallFlowService.class), mock(CallbackService.class),
                settings, mock(CallDao.class), mock(CallbackDao.class),
                mock(AppointmentIvrService.class), mock(ReminderService.class),
                mock(AppointmentDao.class), mock(ReminderDao.class),
                mock(AppointmentService.class), mock(PatientDirectoryService.class),
                doctorService, mock(DepartmentService.class), mock(ScheduleService.class),
                mock(AvailabilityService.class), mock(AppointmentEventRepository.class),
                mock(MessageTemplateRepository.class));
    }

    @Test
    void healthNeedsNoDatabaseAndReportsIvrState() {
        SettingsService settings = mock(SettingsService.class);
        when(settings.ivrEnabled()).thenReturn(true);
        HttpResult r = handlers(settings, mock(DoctorService.class)).handleApi("GET", "/api/health", Map.of(), "");
        assertEquals(200, r.status());
        assertTrue(r.body().contains("\"status\":\"ok\""), r.body());
        assertTrue(r.body().contains("\"ivr_enabled\":true"), r.body());
        assertTrue(r.contentType().startsWith("application/json"));
    }

    @Test
    void unknownRoutesAre404Json() {
        HttpResult r = handlers(mock(SettingsService.class), mock(DoctorService.class))
                .handleApi("GET", "/api/does-not-exist", Map.of(), "");
        assertEquals(404, r.status());
        assertTrue(r.body().contains("Not found"), r.body());
    }

    @Test
    void malformedJsonIsAClientError() {
        HttpResult r = handlers(mock(SettingsService.class), mock(DoctorService.class))
                .handleApi("PUT", "/api/settings", Map.of(), "{not json");
        assertEquals(400, r.status());
    }

    @Test
    void internalFailureDoesNotLeakDriverOrSqlText() {
        DoctorService doctors = mock(DoctorService.class);
        when(doctors.listAll()).thenThrow(new IllegalStateException(
                "could not execute statement; SQL [select ...]; SQLSTATE 42P01 relation \"doctors\" does not exist"));

        HttpResult r = handlers(mock(SettingsService.class), doctors).handleApi("GET", "/api/doctors", Map.of(), "");

        assertEquals(500, r.status());
        assertEquals("{\"error\":\"Internal error\"}", r.body());
        assertFalse(r.body().contains("SQLSTATE"), r.body());
        assertFalse(r.body().contains("doctors"), r.body());
    }
}
