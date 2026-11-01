package com.vobiz.masking;

import com.vobiz.masking.util.Forms;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class FormsTest {

    @Test
    void parsesUrlEncodedBody() {
        Map<String, String> m = Forms.parse("CallUUID=abc-123&From=%2B919876543210&q=x+y&empty=");
        assertEquals("abc-123", m.get("CallUUID"));
        assertEquals("+919876543210", m.get("From"));
        assertEquals("x y", m.get("q"));
        assertEquals("", m.get("empty"));
    }

    @Test
    void firstNonBlankSkipsMissingAndBlank() {
        Map<String, String> m = Map.of("a", "", "b", "x");
        assertEquals("x", Forms.firstNonBlank(m, "zzz", "a", "b"));
        assertNull(Forms.firstNonBlank(m, "zzz", "a"));
    }
}
