package com.vobiz.masking.appointment.service;

import com.vobiz.masking.appointment.repo.IvrSessionRepository;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * Periodic scan: places due reminder calls and cleans up IVR sessions older than 24 h.
 * A scheduler exception must never take the app down — tick() catches everything and logs.
 * Disable with {@code app.reminder-scheduler-enabled=false} (default: on).
 */
@Component
@ConditionalOnProperty(name = "app.reminder-scheduler-enabled", havingValue = "true", matchIfMissing = true)
public class ReminderScheduler {

    private static final Logger log = LogManager.getLogger(ReminderScheduler.class);

    private final ReminderService reminderService;
    private final IvrSessionRepository sessions;

    public ReminderScheduler(ReminderService reminderService, IvrSessionRepository sessions) {
        this.reminderService = reminderService;
        this.sessions = sessions;
    }

    @Scheduled(fixedDelayString = "${app.reminder-scan-ms:60000}")
    public void tick() {
        try {
            reminderService.runDue();
            sessions.deleteAll(sessions.findByUpdatedAtBefore(Instant.now().minusSeconds(86400)));
        } catch (Exception e) {
            log.error("Reminder scheduler tick failed", e);
        }
    }
}
