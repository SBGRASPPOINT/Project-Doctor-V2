package com.vobiz.masking.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Vobiz REST credentials and WhatsApp channel identity (never stored in code or the database). */
@Data
@ConfigurationProperties(prefix = "vobiz")
public class VobizProperties {

    private String authId = "";
    private String authToken = "";
    private String apiBase = "https://api.vobiz.ai";

    /** Vobiz Messaging API channel and linked WhatsApp Business Account IDs. */
    private String whatsappChannelId = "";
    private String whatsappWabaId = "";

    /** E.164 number attached to the Vobiz WhatsApp channel; must equal the clinic's M1. */
    private String whatsappNumber = "";

    /** Shared secret used to authenticate the configured Vobiz message webhook URL. */
    private String whatsappWebhookSecret = "";

    /** true = log/mock outbound Vobiz calls and messages instead of contacting Vobiz. */
    private boolean mock = true;
}
