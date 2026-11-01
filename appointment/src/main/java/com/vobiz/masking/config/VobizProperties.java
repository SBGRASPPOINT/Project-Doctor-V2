package com.vobiz.masking.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** vobiz.* properties: REST credentials for outbound click-to-call (never in code/DB). */
@Data
@ConfigurationProperties(prefix = "vobiz")
public class VobizProperties {

    private String authId = "";
    private String authToken = "";
    private String apiBase = "https://api.vobiz.example.com";

    /** true = log outbound calls instead of calling the Vobiz API (default until credentials are set). */
    private boolean mock = true;
}
