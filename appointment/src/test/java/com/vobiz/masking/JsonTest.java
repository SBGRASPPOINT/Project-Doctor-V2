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
        assertEquals(7L, ((Number) bm.get("id")).longValue());
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
        assertEquals(1L, ((Number) arr.get(0)).longValue());
        assertEquals(2.5, ((Number) arr.get(1)).doubleValue());
        assertEquals(-3L, ((Number) arr.get(2)).longValue());
        assertEquals("x\ny", m.get("c"));
    }

    @Test
    void rejectsGarbage() {
        assertThrows(ApiException.class, () -> Json.parse("{not json"));
        assertThrows(ApiException.class, () -> Json.parseObject("[1,2]"));
        assertThrows(ApiException.class, () -> Json.parse(""));
    }
}
