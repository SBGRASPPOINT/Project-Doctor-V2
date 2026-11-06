package com.vobiz.masking.whatsapp.security;

import com.vobiz.masking.security.WhatsAppPrincipal;
import com.vobiz.masking.util.ApiException;
import com.vobiz.masking.whatsapp.model.WhatsAppStaffAccess;
import com.vobiz.masking.whatsapp.repo.WhatsAppStaffAccessRepository;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Provisions individually revocable, least-privilege bearer tokens for WhatsApp-only access. */
public class WhatsAppAccessService {

    private static final Set<String> ROLES = Set.of("agent", "compounder");
    private static final SecureRandom RANDOM = new SecureRandom();

    private final WhatsAppStaffAccessRepository repository;

    public WhatsAppAccessService(WhatsAppStaffAccessRepository repository) {
        this.repository = repository;
    }

    /** Resolve an Authorization bearer token without ever storing or logging its clear text. */
    @Transactional(readOnly = true)
    public WhatsAppPrincipal authenticate(String authorizationHeader) {
        String token = bearerValue(authorizationHeader);
        if (token == null || token.length() < 32 || token.length() > 160) return null;
        String hash = sha256(token);
        return repository.findByTokenHashAndActiveTrue(hash)
                .map(a -> new WhatsAppPrincipal(a.getId(), a.getName(), a.getRole(),
                        a.isCanRead(), a.isCanSend(), false))
                .orElse(null);
    }

    @Transactional
    public Map<String, Object> create(Map<String, Object> body) {
        String name = requiredString(body, "name", 1, 120);
        String role = body.containsKey("role") ? requiredString(body, "role", 1, 24).toLowerCase(Locale.ROOT) : "agent";
        if (!ROLES.contains(role)) throw new ApiException(400, "role must be agent or compounder");

        String token = newToken();
        WhatsAppStaffAccess access = new WhatsAppStaffAccess();
        access.setName(name);
        access.setRole(role);
        access.setTokenHash(sha256(token));
        boolean canRead = optionalBoolean(body, "can_read", true);
        boolean canSend = optionalBoolean(body, "can_send", true);
        if (canSend && !canRead) throw new ApiException(400, "can_send requires can_read for a shared inbox account");
        access.setCanRead(canRead);
        access.setCanSend(canSend);
        access.setActive(true);
        access = repository.save(access);

        Map<String, Object> result = new LinkedHashMap<>(access.toMap());
        result.put("access_token", token); // returned exactly once; never persisted in clear text
        return result;
    }

    @Transactional(readOnly = true)
    public List<WhatsAppStaffAccess> list() {
        return repository.findAllByOrderByCreatedAtDescIdDesc();
    }

    @Transactional
    public WhatsAppStaffAccess update(long id, Map<String, Object> body) {
        WhatsAppStaffAccess access = get(id);
        if (body.containsKey("name")) access.setName(requiredString(body, "name", 1, 120));
        if (body.containsKey("role")) {
            String role = requiredString(body, "role", 1, 24).toLowerCase(Locale.ROOT);
            if (!ROLES.contains(role)) throw new ApiException(400, "role must be agent or compounder");
            access.setRole(role);
        }
        if (body.containsKey("can_read")) access.setCanRead(optionalBoolean(body, "can_read", access.isCanRead()));
        if (body.containsKey("can_send")) access.setCanSend(optionalBoolean(body, "can_send", access.isCanSend()));
        if (body.containsKey("active")) access.setActive(optionalBoolean(body, "active", access.isActive()));
        if (access.isCanSend() && !access.isCanRead())
            throw new ApiException(400, "can_send requires can_read for a shared inbox account");
        return repository.save(access);
    }

    @Transactional
    public Map<String, Object> rotate(long id) {
        WhatsAppStaffAccess access = get(id);
        String token = newToken();
        access.setTokenHash(sha256(token));
        access = repository.save(access);
        Map<String, Object> result = new LinkedHashMap<>(access.toMap());
        result.put("access_token", token);
        return result;
    }

    private WhatsAppStaffAccess get(long id) {
        return repository.findById(id)
                .orElseThrow(() -> new ApiException(404, "WhatsApp staff access not found: " + id));
    }

    private static String bearerValue(String header) {
        if (header == null || !header.regionMatches(true, 0, "Bearer ", 0, 7)) return null;
        String token = header.substring(7).trim();
        return token.isBlank() ? null : token;
    }

    private static String newToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return "wa_" + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    private static String requiredString(Map<String, Object> body, String key, int min, int max) {
        Object value = body.get(key);
        if (value == null) throw new ApiException(400, "Missing field: " + key);
        String text = String.valueOf(value).trim();
        if (text.length() < min || text.length() > max)
            throw new ApiException(400, key + " must be " + min + ".." + max + " characters");
        return text;
    }

    private static boolean optionalBoolean(Map<String, Object> body, String key, boolean fallback) {
        Object value = body.get(key);
        if (value == null) return fallback;
        if (value instanceof Boolean b) return b;
        String text = String.valueOf(value).trim();
        if ("true".equalsIgnoreCase(text)) return true;
        if ("false".equalsIgnoreCase(text)) return false;
        throw new ApiException(400, key + " must be true or false");
    }
}
