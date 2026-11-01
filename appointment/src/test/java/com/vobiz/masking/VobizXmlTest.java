package com.vobiz.masking;

import com.vobiz.masking.xml.VobizXml;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VobizXmlTest {

    @Test
    void escapesSpecialChars() {
        assertEquals("&lt;a&gt;&amp;&quot;x&quot;", VobizXml.escape("<a>&\"x\""));
    }

    @Test
    void dialContainsAllNumbersCallerIdAndAction() {
        String xml = VobizXml.dial(List.of("+15551230001", "+15551230002"),
                "+10000000000", 20, "https://app.example.com/voice/dial-result", false);
        assertTrue(xml.contains("callerId=\"+10000000000\""), xml);
        assertTrue(xml.contains("timeout=\"20\""), xml);
        assertTrue(xml.contains("action=\"https://app.example.com/voice/dial-result\""), xml);
        assertTrue(xml.contains("method=\"POST\""), xml);
        assertTrue(xml.contains("redirect=\"false\""), xml);
        assertTrue(xml.contains("<Number>+15551230001</Number>"), xml);
        assertTrue(xml.contains("<Number>+15551230002</Number>"), xml);
    }

    @Test
    void voicemailFallbackIsSpeakRecordSpeakHangup() {
        String xml = VobizXml.voicemailFallback("greeting", "closing",
                "https://app.example.com/voice/recording-done", 30, 5);
        assertTrue(xml.indexOf("<Speak>greeting</Speak>") < xml.indexOf("<Record"), xml);
        assertTrue(xml.contains("maxLength=\"30\""), xml);
        assertTrue(xml.contains("timeout=\"5\""), xml);
        assertTrue(xml.contains("finishOnKey=\"#\""), xml);
        assertTrue(xml.contains("playBeep=\"true\""), xml);
        assertTrue(xml.contains("action=\"https://app.example.com/voice/recording-done\""), xml);
        assertTrue(xml.contains("<Speak>closing</Speak>"), xml);
        assertTrue(xml.endsWith("<Hangup/>"), xml);
    }

    @Test
    void responseWrapsInXmlDeclaration() {
        assertTrue(VobizXml.response("<Hangup/>").startsWith("<?xml version=\"1.0\" encoding=\"UTF-8\"?>"));
        assertTrue(VobizXml.response("<Hangup/>").contains("<Response><Hangup/></Response>"));
    }

    @Test
    void outboundBridgeDialsCustomerWithM1() {
        String xml = VobizXml.bridgeOutbound("+10000000000", "+919876543210");
        assertTrue(xml.contains("callerId=\"+10000000000\""), xml);
        assertTrue(xml.contains("<Number>+919876543210</Number>"), xml);
    }
}
