package com.vobiz.masking.whatsapp;

/** Provider-neutral outbound seam; the Vobiz adapter is supplied through Spring dependency injection. */
public interface WhatsAppTransport {

    boolean isMock();

    boolean isConfigured();

    /** Send a plain-text WhatsApp message to an E.164 recipient; returns the provider message ID when supplied. */
    String sendText(String recipientE164, String text);
}
