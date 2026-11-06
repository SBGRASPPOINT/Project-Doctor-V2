package com.vobiz.masking.security;

import java.util.LinkedHashMap;
import java.util.Map;

/** Request-scoped WhatsApp identity. The master API token is treated as the inbox administrator. */
public record WhatsAppPrincipal(Long id, String name, String role,
                                boolean canRead, boolean canSend, boolean admin) {

    public static final String REQUEST_ATTRIBUTE = WhatsAppPrincipal.class.getName();

    public static WhatsAppPrincipal administrator() {
        return new WhatsAppPrincipal(null, "Administrator", "admin", true, true, true);
    }

    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("name", name);
        m.put("role", role);
        m.put("can_read", canRead);
        m.put("can_send", canSend);
        m.put("admin", admin);
        return m;
    }
}
