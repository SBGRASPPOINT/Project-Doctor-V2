package com.vobiz.masking.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** app.* properties: where this service is publicly reachable, and the fallback M1 number. */
@Data
@ConfigurationProperties(prefix = "app")
public class AppProperties {

    /** Public base URL of this service; used to build the webhook URLs sent to Vobiz. */
    private String publicBaseUrl = "http://localhost:8080";

    /** Fallback masked DID (M1); the settings-table value wins once edited in the UI. */
    private String defaultM1Number = "+10000000000";

    /** Bearer token for /api/**. Empty = API is unauthenticated (development only). */
    private String apiToken = "";

    /** Shared secret for /voice/** webhooks (sent as ?secret= or X-Webhook-Secret). Empty = open. */
    private String webhookSecret = "";

    /** Seed demo agents/doctors/patients. Off by default outside dev. */
    private boolean seedDemoData = false;

    /** Fail startup when config still looks like a local placeholder (set false in tests). */
    private boolean requireProductionConfig = false;
}
