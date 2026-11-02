package com.vobiz.masking.config;

import com.vobiz.masking.service.SettingsService;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Fails startup loudly instead of booting a "successful" service that can never place a call.
 *
 * Previously the defaults ({@code VOBIZ_MOCK=true}, {@code M1=+10000000000},
 * {@code PUBLIC_BASE_URL=http://localhost:8080}) were indistinguishable from a real
 * configuration, so a production deploy came up green and silently did nothing.
 *
 * Validation is active when {@code app.require-production-config=true} (set it in production)
 * or as soon as real calling is switched on with {@code VOBIZ_MOCK=false}. Run it before the
 * seeder's demo data is trusted: {@code @Order} keeps this after {@link DataSeeder}.
 */
@Component
@Order(2)
public class ConfigValidator implements ApplicationRunner {

    private static final Logger log = LogManager.getLogger(ConfigValidator.class);

    private static final String PLACEHOLDER_M1 = "+10000000000";

    private final AppProperties app;
    private final VobizProperties vobiz;
    private final SettingsService settings;

    public ConfigValidator(AppProperties app, VobizProperties vobiz, SettingsService settings) {
        this.app = app;
        this.vobiz = vobiz;
        this.settings = settings;
    }

    @Override
    public void run(ApplicationArguments args) {
        boolean realCalling = !vobiz.isMock()
                && vobiz.getAuthId() != null && !vobiz.getAuthId().isBlank()
                && vobiz.getAuthToken() != null && !vobiz.getAuthToken().isBlank();
        if (!realCalling && !app.isRequireProductionConfig()) {
            log.warn("Running in development configuration (Vobiz mock / placeholder numbers). "
                    + "Set VOBIZ_MOCK=false with real credentials and APP_REQUIRE_PRODUCTION_CONFIG=true for production.");
            return;
        }

        List<String> problems = new ArrayList<>();
        String base = app.getPublicBaseUrl() == null ? "" : app.getPublicBaseUrl().trim();
        if (base.isEmpty()) {
            problems.add("PUBLIC_BASE_URL is empty");
        } else if (base.contains("localhost") || base.contains("127.0.0.1")) {
            problems.add("PUBLIC_BASE_URL points at localhost (" + base + ") — Vobiz cannot reach it");
        } else if (!base.startsWith("https://")) {
            problems.add("PUBLIC_BASE_URL must be https:// in production (" + base + ")");
        }
        if (app.getApiToken() == null || app.getApiToken().isBlank())
            problems.add("API_TOKEN is empty — /api/** would be open to anyone");
        if (app.getWebhookSecret() == null || app.getWebhookSecret().isBlank())
            problems.add("WEBHOOK_SECRET is empty — /voice/** webhooks would be unauthenticated");
        if (vobiz.getAuthId() == null || vobiz.getAuthId().isBlank() || vobiz.getAuthToken() == null
                || vobiz.getAuthToken().isBlank())
            problems.add("VOBIZ_AUTH_ID / VOBIZ_AUTH_TOKEN are empty — outbound calls and reminders cannot be placed");
        String m1 = settings.m1(app.getDefaultM1Number());
        if (PLACEHOLDER_M1.equals(m1) || m1 == null || m1.isBlank())
            problems.add("Masked number M1 is still the placeholder (" + m1 + ") — set it in Settings or M1_NUMBER");
        if (app.isSeedDemoData())
            problems.add("app.seed-demo-data=true would insert demo agents/patients into a live database");

        if (!problems.isEmpty()) {
            throw new IllegalStateException("Refusing to start with a production-incomplete configuration:\n  - "
                    + String.join("\n  - ", problems));
        }
        log.info("Production configuration check passed (public base {}, M1 {}).", base, m1);
    }
}
