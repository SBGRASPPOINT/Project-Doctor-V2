package com.vobiz.masking;

import com.vobiz.masking.util.Json;
import org.junit.jupiter.api.Test;

class TmpDiagnosticTest {
    @Test
    void whatTypeIsAnIntegralLiteral() {
        Object o = ((java.util.List<?>) Json.parse("[1]")).get(0);
        org.junit.jupiter.api.Assertions.fail("integral literal parsed as " + o.getClass().getName() + " = " + o);
    }
}
