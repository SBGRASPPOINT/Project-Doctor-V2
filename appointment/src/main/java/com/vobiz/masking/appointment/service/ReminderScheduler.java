package com.vobiz.masking.appointment.service;

import com.vobiz.masking.appointment.repo.IvrSessionRepository;
import com.vobiz.masking.service.SettingsService;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * Periodic scan: places due reminder calls and cleans up IVR sessions older than 24 h.
 * A scheduler exception must never take the app down — tick() catches everything and logs.
 *
 * The interval is read from the {@code reminder_scan_ms} setting on every tick (it used to
 * read a Spring property that nothing set, so editing the value in the console did nothing).
 * The {@code @Scheduled} interval below is only the tick granularity, i.e. the shortest
 * interval the setting can express.
 * Disable the whole scheduler with {@code app.reminder-scheduler-enabled=false}.
 */
@Component
@ConditionalOnProperty(name = "app.reminder-scheduler-enabled", havingValue = "true", matchIfMissing = true)
public class ReminderScheduler {

    private static final Logger log = LogManager.getLogger(ReminderScheduler.class);

    /** How often we check whether reminder_scan_ms has elapsed (setting range starts at 5000). */
    private static final long TICK_MS = 5000;

    private final ReminderService reminderService;
    private final IvrSessionRepository sessions;
    private final SettingsService settings;

    private long lastRunMs = 0;

    public ReminderScheduler(ReminderService reminderService, IvrSessionRepository sessions,
                             SettingsService settings) {
        this.reminderService = reminderService;
        this.sessions = sessions;
        this.settings = settings;
    }

    @Scheduled(fixedDelay = TICK_MS)
    public void tick() {
        long now = System.currentTimeMillis();
        if (now - lastRunMs < settings.reminderScanMs()) return;   // honour the console setting
        lastRunMs = now;
        try {
            reminderService.runDue();
            sessions.deleteAll(sessions.findByUpdatedAtBefore(Instant.now().minusSeconds(86400)));
        } catch (Exception e) {
            log.error("Reminder scheduler tick failed", e);
        }
    }
}
