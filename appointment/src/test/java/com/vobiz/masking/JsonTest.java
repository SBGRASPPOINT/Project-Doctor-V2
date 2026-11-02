package com.vobiz.masking;

import com.vobiz.masking.util.ApiException;
import com.vobiz.masking.util.Json;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JsonTest {

    @Test
    void roundTrip() {
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("name", "Agent \"N2\"");
        m.put("id", 7L);
        m.put("active", true);
        m.put("tags", List.of("a", "b"));
        m.put("nothing", null);
        String s = Json.write(m);
        Object back = Json.parse(s);
        assertTrue(back instanceof Map);
        @SuppressWarnings("unchecked")
        Map<String, Object> bm = (Map<String, Object>) back;
        assertEquals("Agent \"N2\"", bm.get("name"));
        assertEquals(7L, bm.get("id"));
        assertEquals(Boolean.TRUE, bm.get("active"));
        assertEquals(List.of("a", "b"), bm.get("tags"));
        assertTrue(bm.containsKey("nothing") && bm.get("nothing") == null);
    }

    @Test
    void parsesNumbersAndNesting() {
        Object v = Json.parse("{\"a\":{\"b\":[1, 2.5, -3]}, \"c\":\"x\\ny\"}");
        assertTrue(v instanceof Map);
        @SuppressWarnings("unchecked")
        Map<String, Object> m = (Map<String, Object>) v;
        @SuppressWarnings("unchecked")
        List<Object> arr = (List<Object>) ((Map<String, Object>) m.get("a")).get("b");
        assertEquals(1L, arr.get(0));
        assertEquals(2.5, arr.get(1));
        assertEquals(-3L, arr.get(2));
        assertEquals("x\ny", m.get("c"));
        // Regression guard: an integral literal must stay a Long, not widen to Double just
        // because the sibling branch of the parser's ternary is a double.
        assertTrue(arr.get(0) instanceof Long, "integral literals must parse as Long, got " + arr.get(0).getClass());
        assertTrue(arr.get(1) instanceof Double, "decimal literals must parse as Double");
    }

    @Test
    void rejectsGarbage() {
        assertThrows(ApiException.class, () -> Json.parse("{not json"));
        assertThrows(ApiException.class, () -> Json.parseObject("[1,2]"));
        assertThrows(ApiException.class, () -> Json.parse(""));
    }
}
