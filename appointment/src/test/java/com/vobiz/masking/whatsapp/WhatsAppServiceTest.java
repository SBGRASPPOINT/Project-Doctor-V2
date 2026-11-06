package com.vobiz.masking.whatsapp;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class WhatsAppServiceTest {

    @Test
    void extractsPlainStringAsInboundTextInsteadOfPlaceholder() {
        WhatsAppService.MessageContent content = WhatsAppService.extractContent("text", "Hello from Vobiz");

        assertEquals("Hello from Vobiz", content.body());
    }
}
