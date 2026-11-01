package com.vobiz.masking;

import com.vobiz.masking.xml.VobizXml;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VobizXmlIvrTest {

    @Test
    void gatherWrapsPromptAndPostsDigitsToAction() {
        String xml = VobizXml.gather(VobizXml.speak("Press 1"), "https://h.example.com/voice/ivr/menu?call_uuid=a b", 1, 8);
        assertTrue(xml.contains("<Gather numDigits=\"1\" timeout=\"8\""), xml);
        assertTrue(xml.contains("action=\"https://h.example.com/voice/ivr/menu?call_uuid=a b\""), xml);
        assertTrue(xml.contains("method=\"POST\""), xml);
        assertTrue(xml.contains("<Speak>Press 1</Speak>"), xml);
        assertTrue(xml.endsWith("</Gather>"), xml);
    }

    @Test
    void gatherEscapesSpecialCharsInUrl() {
        String xml = VobizXml.gather("", "https://h.example.com/x?a=1&b=<2>", 1, 5);
        assertTrue(xml.contains("a=1&amp;b=&lt;2&gt;"), xml);
    }

    @Test
    void redirectPausePlay() {
        assertEquals("<Redirect method=\"POST\">https://h.example.com/next</Redirect>",
                VobizXml.redirect("https://h.example.com/next"));
        assertEquals("<Pause length=\"3\"/>", VobizXml.pause(3));
        assertEquals("<Play>https://h.example.com/a.mp3</Play>", VobizXml.play("https://h.example.com/a.mp3"));
    }

    @Test
    void confirmPromptExampleIsValidShape() {
        String doc = VobizXml.response(
                VobizXml.gather(VobizXml.speak("Dr Sharma, Tuesday at 10:30 AM. Press 1 to confirm."),
                        "https://h.example.com/voice/ivr/confirm", 1, 8)
                        + VobizXml.redirect("https://h.example.com/voice/ivr/no-input"));
        assertTrue(doc.startsWith("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<Response>"), doc);
        assertTrue(doc.contains("<Gather"), doc);
        assertTrue(doc.contains("<Redirect"), doc);
        assertTrue(doc.endsWith("</Response>"), doc);
    }
}
