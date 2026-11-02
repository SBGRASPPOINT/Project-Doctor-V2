package com.vobiz.masking;

import com.vobiz.masking.util.ApiException;
import com.vobiz.masking.util.Forms;
import com.vobiz.masking.util.Json;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Input-hardening regressions. Both cases used to produce a 500 (or worse) from unauthenticated
 * callers: a deeply nested body overflowed the parser stack, and a malformed escape/percent
 * sequence raised an exception no layer caught.
 */
class JsonHardeningTest {

    @Test
    void deeplyNestedBodiesAreRejectedRatherThanOverflowingTheStack() {
        StringBuilder sb = new StringBuilder();
        int depth = 5000;
        for (int i = 0; i < depth; i++) sb.append("{\"a\":");
        sb.append("1");
        for (int i = 0; i < depth; i++) sb.append('}');

        ApiException e = assertThrows(ApiException.class, () -> Json.parse(sb.toString()));
        assertEquals(400, e.getStatus());
    }

    @Test
    void reasonableNestingStillParses() {
        String body = "{\"a\":{\"b\":[1,2,{\"c\":\"d\"}]}}";
        assertEquals(body, Json.write(Json.parse(body)));   // parses and round-trips
    }

    @Test
    void malformedUnicodeEscapeIsAClientError() {
        ApiException e = assertThrows(ApiException.class, () -> Json.parse("{\"k\":\"\\uZZZZ\"}"));
        assertEquals(400, e.getStatus());
    }

    @Test
    void malformedPercentEncodingIsReportedNotThrownAsRuntimeNoise() {
        assertThrows(IllegalArgumentException.class, () -> Forms.parse("a=%"));
        assertEquals("v", Forms.parse("a=v").get("a"));
    }
}
