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
}
