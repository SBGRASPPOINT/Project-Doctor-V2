package com.vobiz.masking.appointment;

import com.vobiz.masking.appointment.service.PromptService;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PromptServiceTest {

    @Test
    void languageFallsBackToEnglish() {
        Map<String, String> byLang = Map.of("en", "Hello", "hi", "Namaste");
        assertEquals("Namaste", PromptService.pick("hi", byLang));
        assertEquals("Hello", PromptService.pick("mr", byLang));   // missing → en
        assertEquals("Hello", PromptService.pick(null, byLang));
        assertEquals("", PromptService.pick("hi", Map.of()));
    }

    @Test
    void placeholdersAreSubstituted() {
        String out = PromptService.substitute("Dr {doctor} on {day} at {time}",
                Map.of("doctor", "Sharma", "day", "Tuesday", "time", "10:30 AM"));
        assertEquals("Dr Sharma on Tuesday at 10:30 AM", out);
    }

    @Test
    void nullVarsLeaveTemplateIntact() {
        assertEquals("Press 1", PromptService.substitute("Press 1", null));
        assertEquals("", PromptService.substitute(null, Map.of()));
    }
}
