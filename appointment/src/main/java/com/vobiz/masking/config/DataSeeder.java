package com.vobiz.masking.config;

import com.vobiz.masking.appointment.model.Department;
import com.vobiz.masking.appointment.model.Doctor;
import com.vobiz.masking.appointment.model.DoctorSchedule;
import com.vobiz.masking.appointment.model.MessageTemplate;
import com.vobiz.masking.appointment.model.Patient;
import com.vobiz.masking.appointment.repo.DepartmentRepository;
import com.vobiz.masking.appointment.repo.DoctorRepository;
import com.vobiz.masking.appointment.repo.DoctorScheduleRepository;
import com.vobiz.masking.appointment.repo.MessageTemplateRepository;
import com.vobiz.masking.appointment.repo.PatientRepository;
import com.vobiz.masking.model.Agent;
import com.vobiz.masking.model.Setting;
import com.vobiz.masking.repo.AgentRepository;
import com.vobiz.masking.repo.SettingRepository;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.time.LocalTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Seeds default settings, IVR/reminder prompt templates and demo clinic data — inserted only
 * when missing. The tables themselves are created by Hibernate from the JPA entities in
 * {@code model/} / {@code appointment/model/} ({@code spring.jpa.hibernate.ddl-auto=update}),
 * so no SQL schema file is needed anywhere.
 */
@Component
@Order(1)
public class DataSeeder implements ApplicationRunner {

    private static final Logger log = LogManager.getLogger(DataSeeder.class);

    private static final Map<String, String> DEFAULT_SETTINGS = new LinkedHashMap<>();

    static {
        // --- call masking (existing) ---
        DEFAULT_SETTINGS.put("m1_number", "+10000000000");
        DEFAULT_SETTINGS.put("ring_timeout_sec", "20");
        DEFAULT_SETTINGS.put("voicemail_max_sec", "30");
        DEFAULT_SETTINGS.put("record_silence_timeout_sec", "5");
        DEFAULT_SETTINGS.put("routing_mode", "simultaneous");   // simultaneous | sequential
        DEFAULT_SETTINGS.put("respect_presence", "false");        // true: only ring agents marked 'available'
        DEFAULT_SETTINGS.put("announce_recording", "true");       // TRAI: announce before bridging
        DEFAULT_SETTINGS.put("announcement_text", "This call may be recorded for quality and training purposes.");
        DEFAULT_SETTINGS.put("greeting_text",
                "Sorry, none of our team is free right now. Please tell us your issue after the beep, and someone will call you back.");
        DEFAULT_SETTINGS.put("closing_text", "Thank you. We will call you back shortly.");
        // --- appointment IVR / booking ---
        DEFAULT_SETTINGS.put("ivr_enabled", "true");
        DEFAULT_SETTINGS.put("ivr_max_hops", "12");
        DEFAULT_SETTINGS.put("booking_horizon_days", "30");
        DEFAULT_SETTINGS.put("min_booking_lead_minutes", "60");
        DEFAULT_SETTINGS.put("max_slots_offered", "3");
        DEFAULT_SETTINGS.put("slot_granularity_min", "15");
        DEFAULT_SETTINGS.put("clinic_timezone", "Asia/Kolkata");
        DEFAULT_SETTINGS.put("clinic_name", "City Care Clinic");
        DEFAULT_SETTINGS.put("allow_self_booking", "true");
        DEFAULT_SETTINGS.put("allow_self_cancel_hours", "4");
        // --- reminders ---
        DEFAULT_SETTINGS.put("reminder_t24h_enabled", "true");
        DEFAULT_SETTINGS.put("reminder_t24h_lead_minutes", "1440");
        DEFAULT_SETTINGS.put("reminder_t2h_enabled", "true");
        DEFAULT_SETTINGS.put("reminder_t2h_lead_minutes", "120");
        DEFAULT_SETTINGS.put("reminder_retry_count", "2");
        DEFAULT_SETTINGS.put("reminder_retry_gap_minutes", "30");
        DEFAULT_SETTINGS.put("reminder_quiet_start", "08:00");
        DEFAULT_SETTINGS.put("reminder_quiet_end", "20:00");
        DEFAULT_SETTINGS.put("reminder_scan_ms", "60000");
        DEFAULT_SETTINGS.put("sms_fallback_enabled", "false");
        DEFAULT_SETTINGS.put("change_notification_enabled", "true");
    }

    /** English TTS prompts for the IVR + reminder calls (editable/translatable in the console). */
    private static final Map<String, String> TEMPLATES_EN = new LinkedHashMap<>();

    static {
        TEMPLATES_EN.put("ivr_welcome_known", "Welcome back, {name}.");
        TEMPLATES_EN.put("ivr_welcome_unknown",
                "Welcome to {clinic}. You can book, reschedule or cancel an appointment by phone.");
        TEMPLATES_EN.put("ivr_main_menu",
                "Press 1 to book an appointment, 2 to reschedule, 3 to cancel, or 0 to speak to reception.");
        TEMPLATES_EN.put("ivr_choose_department", "Choose a department. {list} Press 0 for reception.");
        TEMPLATES_EN.put("ivr_choose_doctor", "Choose a doctor. {list} Press 0 for reception.");
        TEMPLATES_EN.put("ivr_choose_slot", "Available slots. {list} Press 0 for reception.");
        TEMPLATES_EN.put("ivr_no_slots",
                "Sorry, {doctor} has no free slots in the coming days. Press 1 to hear other doctors, 0 for reception.");
        TEMPLATES_EN.put("ivr_confirm_booking",
                "{doctor}, {day} at {time}. Press 1 to confirm, 2 for a different slot, 0 for reception.");
        TEMPLATES_EN.put("ivr_booked_done",
                "Booked. Your reference code is {ref}. Say it back: {ref}. We will call you with a reminder before the appointment. Goodbye.");
        TEMPLATES_EN.put("ivr_rescheduled_done",
                "Rescheduled to {day} at {time}. Your reference is still {ref}. Goodbye.");
        TEMPLATES_EN.put("ivr_cancel_done", "Your appointment {ref} is cancelled. Goodbye.");
        TEMPLATES_EN.put("ivr_confirm_cancel",
                "Appointment {ref} with {doctor} on {day} at {time}. Press 1 to cancel it, 0 for reception.");
        TEMPLATES_EN.put("ivr_pick_appointment", "Which appointment? {list} Press 0 for reception.");
        TEMPLATES_EN.put("ivr_no_appointments", "You have no upcoming appointments. Press 0 for reception.");
        TEMPLATES_EN.put("ivr_no_input", "We did not get your input.");
        TEMPLATES_EN.put("ivr_booking_disabled", "Self booking is currently disabled. Connecting you to reception.");
        TEMPLATES_EN.put("ivr_slot_gone", "Sorry, that slot has just gone. Goodbye.");
        TEMPLATES_EN.put("ivr_cancel_too_late",
                "This appointment is too close to cancel by phone. Connecting you to reception.");
        TEMPLATES_EN.put("ivr_human", "Connecting you to reception now.");
        TEMPLATES_EN.put("reminder_t24h",
                "This is a reminder from {clinic}. You have an appointment with {doctor} on {day} at {time}, reference {ref}. Press 1 to confirm, 2 to reschedule, 3 to cancel.");
        TEMPLATES_EN.put("reminder_t2h",
                "Reminder from {clinic}: your appointment with {doctor} is on {day} at {time}, reference {ref}. Press 1 to confirm, 2 to reschedule, 3 to cancel.");
        TEMPLATES_EN.put("reminder_change",
                "This is {clinic}. Your appointment with {doctor} on {day} at {time} has changed. {reason} Press 1 to confirm the new time, 2 to reschedule, 3 to cancel.");
        TEMPLATES_EN.put("reminder_thanks", "Thank you. Your appointment is confirmed. Goodbye.");
        TEMPLATES_EN.put("reminder_goodbye", "Goodbye.");
    }

    private final SettingRepository settings;
    private final AgentRepository agents;
    private final DepartmentRepository departments;
    private final DoctorRepository doctors;
    private final DoctorScheduleRepository schedules;
    private final MessageTemplateRepository templates;
    private final PatientRepository patients;
    private final AppProperties app;

    public DataSeeder(SettingRepository settings, AgentRepository agents, DepartmentRepository departments,
                      DoctorRepository doctors, DoctorScheduleRepository schedules,
                      MessageTemplateRepository templates, PatientRepository patients, AppProperties app) {
        this.app = app;
        this.settings = settings;
        this.agents = agents;
        this.departments = departments;
        this.doctors = doctors;
        this.schedules = schedules;
        this.templates = templates;
        this.patients = patients;
    }

    @Override
    public void run(ApplicationArguments args) {
        DEFAULT_SETTINGS.forEach((k, v) -> {
            if (settings.findById(k).isEmpty()) settings.save(new Setting(k, v));
        });
        TEMPLATES_EN.forEach((key, text) -> {
            if (templates.findByKeyAndLanguageAndActiveTrue(key, "en").isEmpty()) {
                MessageTemplate t = new MessageTemplate();
                t.setKey(key);
                t.setLanguage("en");
                t.setText(text);
                t.setActive(true);
                templates.save(t);
            }
        });
        // Demo rows (fake agents RoutingService would actually dial, a fake doctor and patient)
        // must never reach a live database: gated behind app.seed-demo-data (APP_SEED_DEMO_DATA).
        if (app.isSeedDemoData()) {
            seedAgent("Agent N2", "+15551230001", 1);
            seedAgent("Agent N3", "+15551230002", 2);
            seedDemoClinic();
            seedPatient("Sannidh", "+919876543210");
            log.info("Demo data seeded (app.seed-demo-data=true): 2 agents, 1 doctor, 1 patient");
        } else {
            log.info("Demo data skipped (app.seed-demo-data=false). "
                    + "Register real agent numbers in the ops console → Agents.");
        }
        log.info("Seed check done: {} settings, {} templates (inserted only if missing)",
                DEFAULT_SETTINGS.size(), TEMPLATES_EN.size());
    }

    private void seedAgent(String name, String phone, int priority) {
        if (agents.findByPhoneE164(phone).isEmpty()) {
            Agent a = new Agent();
            a.setName(name);
            a.setPhoneE164(phone);
            a.setPriority(priority);
            a.setStatus("available");
            a.setActive(true);
            agents.save(a);
        }
    }

    /** One demo department + doctor (linked to agent N2's masked number) + a Mon–Fri morning template. */
    private void seedDemoClinic() {
        Department dep = departments.findByNameIgnoreCase("General Medicine").orElseGet(() -> {
            Department d = new Department();
            d.setName("General Medicine");
            d.setPriority(0);
            d.setActive(true);
            return departments.save(d);
        });
        if (doctors.findAll().stream().noneMatch(d -> "Dr. Sharma".equalsIgnoreCase(d.getName()))) {
            final Doctor doc = new Doctor();
            doc.setName("Dr. Sharma");
            doc.setSpecialization("General Practitioner");
            doc.setDepartmentId(dep.getId());
            doc.setSlotMinutes(15);
            doc.setActive(true);
            agents.findByPhoneE164("+15551230001").ifPresent(a -> doc.setAgentId(a.getId()));
            Doctor saved = doctors.save(doc);
            for (int dow = 1; dow <= 5; dow++) {   // Mon–Fri 10:00–13:00
                DoctorSchedule s = new DoctorSchedule();
                s.setDoctorId(saved.getId());
                s.setDayOfWeek(dow);
                s.setStartTime(LocalTime.of(10, 0));
                s.setEndTime(LocalTime.of(13, 0));
                s.setSlotMinutes(15);
                s.setActive(true);
                schedules.save(s);
            }
        }
    }

    private void seedPatient(String name, String phone) {
        if (patients.findByPhoneE164(phone).isEmpty()) {
            Patient p = new Patient();
            p.setName(name);
            p.setPhoneE164(phone);
            p.setPreferredLanguage("en");
            p.setActive(true);
            patients.save(p);
        }
    }
}
