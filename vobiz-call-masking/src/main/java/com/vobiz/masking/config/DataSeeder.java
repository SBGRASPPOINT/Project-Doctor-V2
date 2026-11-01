package com.vobiz.masking.config;

import com.vobiz.masking.model.Agent;
import com.vobiz.masking.model.Setting;
import com.vobiz.masking.repo.AgentRepository;
import com.vobiz.masking.repo.SettingRepository;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Replaces the seed INSERTs that used to live in schema.sql: default settings plus two
 * demo agents, inserted only when missing. The tables themselves are created by Hibernate
 * from the JPA entities in {@code model/} ({@code spring.jpa.hibernate.ddl-auto=update}),
 * so no SQL schema file is needed anywhere.
 */
@Component
public class DataSeeder implements ApplicationRunner {

    private static final Logger log = LogManager.getLogger(DataSeeder.class);

    private static final Map<String, String> DEFAULT_SETTINGS = new LinkedHashMap<>();

    static {
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
    }

    private final SettingRepository settings;
    private final AgentRepository agents;

    public DataSeeder(SettingRepository settings, AgentRepository agents) {
        this.settings = settings;
        this.agents = agents;
    }

    @Override
    public void run(ApplicationArguments args) {
        DEFAULT_SETTINGS.forEach((k, v) -> {
            if (settings.findById(k).isEmpty()) settings.save(new Setting(k, v));
        });
        seedAgent("Agent N2", "+15551230001", 1);
        seedAgent("Agent N3", "+15551230002", 2);
        log.info("Seed check done: {} default settings, demo agents N2/N3 inserted only if missing",
                DEFAULT_SETTINGS.size());
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
}
