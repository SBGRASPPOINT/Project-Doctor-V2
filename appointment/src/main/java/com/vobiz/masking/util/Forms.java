package com.vobiz.masking.util;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/** Helpers for application/x-www-form-urlencoded webhook payloads (what Vobiz POSTs). */
public final class Forms {

    private Forms() {
    }

    /** Parse a urlencoded body (or query string) into a map. Later keys win. */
    public static Map<String, String> parse(String body) {
        Map<String, String> m = new LinkedHashMap<>();
        if (body == null || body.isEmpty()) return m;
        for (String pair : body.split("&")) {
            if (pair.isEmpty()) continue;
            int i = pair.indexOf('=');
            String k = i < 0 ? pair : pair.substring(0, i);
            String v = i < 0 ? "" : pair.substring(i + 1);
            m.put(URLDecoder.decode(k, StandardCharsets.UTF_8), URLDecoder.decode(v, StandardCharsets.UTF_8));
        }
        return m;
    }

    /** Query params first, then form fields (form wins). */
    public static Map<String, String> merge(Map<String, String> query, Map<String, String> form) {
        Map<String, String> m = new LinkedHashMap<>(query);
        m.putAll(form);
        return m;
    }

    /** First non-blank value among the given keys (Vobiz field names vary slightly). */
    public static String firstNonBlank(Map<String, String> m, String... keys) {
        for (String k : keys) {
            String v = m.get(k);
            if (v != null && !v.isBlank()) return v;
        }
        return null;
    }
}
