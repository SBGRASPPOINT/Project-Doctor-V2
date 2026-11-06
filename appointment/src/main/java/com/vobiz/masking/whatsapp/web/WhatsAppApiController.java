package com.vobiz.masking.whatsapp.web;

import com.vobiz.masking.security.WhatsAppPrincipal;
import com.vobiz.masking.util.ApiException;
import com.vobiz.masking.whatsapp.WhatsAppService;
import com.vobiz.masking.whatsapp.model.WhatsAppConversation;
import com.vobiz.masking.whatsapp.model.WhatsAppMessage;
import com.vobiz.masking.whatsapp.model.WhatsAppStaffAccess;
import com.vobiz.masking.whatsapp.security.WhatsAppAccessService;
import jakarta.servlet.http.HttpServletRequest;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/** Authenticated staff/admin API for the shared WhatsApp inbox. */
@RestController
@RequestMapping("/api/whatsapp")
public class WhatsAppApiController {

    private static final Logger log = LogManager.getLogger(WhatsAppApiController.class);

    private final WhatsAppService whatsapp;
    private final WhatsAppAccessService access;

    public WhatsAppApiController(WhatsAppService whatsapp, WhatsAppAccessService access) {
        this.whatsapp = whatsapp;
        this.access = access;
    }

    @GetMapping("/me")
    public ResponseEntity<?> me(HttpServletRequest request) {
        return execute(() -> {
            Map<String, Object> profile = new LinkedHashMap<>(principal(request).toMap());
            profile.putAll(whatsapp.setupStatus());
            return profile;
        });
    }

    @GetMapping("/conversations")
    public ResponseEntity<?> conversations(@RequestParam(name = "q", required = false) String query,
                                           @RequestParam(name = "limit", defaultValue = "100") int limit,
                                           HttpServletRequest request) {
        return execute(() -> {
            requireRead(principal(request));
            List<Map<String, Object>> items = whatsapp.listConversations(query, limit).stream()
                    .map(WhatsAppConversation::toMap).toList();
            return Map.of("items", items, "count", items.size());
        });
    }

    /** Start a shared thread before the patient has messaged the clinic. */
    @PostMapping("/conversations")
    public ResponseEntity<?> createConversation(@RequestBody(required = false) Map<String, Object> body,
                                                HttpServletRequest request) {
        return execute(() -> {
            requireSend(principal(request));
            Map<String, Object> input = body == null ? Map.of() : body;
            String phone = value(input.get("phone_e164"));
            String name = value(input.get("contact_name"));
            return whatsapp.createConversation(phone, name).toMap();
        }, 201);
    }

    @GetMapping("/conversations/{id}/messages")
    public ResponseEntity<?> messages(@PathVariable long id,
                                      @RequestParam(name = "before_id", required = false) String before,
                                      @RequestParam(name = "limit", defaultValue = "100") int limit,
                                      HttpServletRequest request) {
        return execute(() -> {
            requireRead(principal(request));
            Long beforeId = parseOptionalLong(before, "before_id");
            int pageLimit = Math.min(199, Math.max(1, limit));
            List<WhatsAppMessage> found = whatsapp.listMessages(id, beforeId, pageLimit + 1);
            boolean hasMore = found.size() > pageLimit;
            if (hasMore) found = found.subList(found.size() - pageLimit, found.size());
            List<Map<String, Object>> items = found.stream().map(WhatsAppMessage::toMap).toList();
            return Map.of("items", items, "count", items.size(), "has_more", hasMore);
        });
    }

    @PostMapping("/conversations/{id}/read")
    public ResponseEntity<?> markRead(@PathVariable long id, HttpServletRequest request) {
        return execute(() -> {
            requireRead(principal(request));
            return whatsapp.markRead(id).toMap();
        });
    }

    @PostMapping("/conversations/{id}/messages")
    public ResponseEntity<?> send(@PathVariable long id,
                                  @RequestBody(required = false) Map<String, Object> body,
                                  HttpServletRequest request) {
        return execute(() -> {
            WhatsAppPrincipal sender = principal(request);
            requireSend(sender);
            String text = value(body == null ? null : body.get("text"));
            return whatsapp.sendText(id, text, sender).toMap();
        }, 201);
    }

    @GetMapping("/staff-access")
    public ResponseEntity<?> staffAccess(HttpServletRequest request) {
        return execute(() -> {
            requireAdmin(principal(request));
            List<Map<String, Object>> items = access.list().stream().map(WhatsAppStaffAccess::toMap).toList();
            return Map.of("items", items, "count", items.size());
        });
    }

    @PostMapping("/staff-access")
    public ResponseEntity<?> createStaffAccess(@RequestBody(required = false) Map<String, Object> body,
                                               HttpServletRequest request) {
        return execute(() -> {
            requireAdmin(principal(request));
            return access.create(body == null ? Map.of() : body);
        }, 201);
    }

    @PatchMapping("/staff-access/{id}")
    public ResponseEntity<?> updateStaffAccess(@PathVariable long id,
                                               @RequestBody(required = false) Map<String, Object> body,
                                               HttpServletRequest request) {
        return execute(() -> {
            requireAdmin(principal(request));
            return access.update(id, body == null ? Map.of() : body).toMap();
        });
    }

    @PostMapping("/staff-access/{id}/rotate")
    public ResponseEntity<?> rotateStaffAccess(@PathVariable long id, HttpServletRequest request) {
        return execute(() -> {
            requireAdmin(principal(request));
            return access.rotate(id);
        });
    }

    private static WhatsAppPrincipal principal(HttpServletRequest request) {
        Object value = request.getAttribute(WhatsAppPrincipal.REQUEST_ATTRIBUTE);
        if (value instanceof WhatsAppPrincipal principal) return principal;
        throw new ApiException(401, "WhatsApp access token is required.");
    }

    private static void requireRead(WhatsAppPrincipal principal) {
        if (!principal.admin() && !principal.canRead())
            throw new ApiException(403, "This account does not have permission to view the WhatsApp inbox.");
    }

    private static void requireSend(WhatsAppPrincipal principal) {
        if (!principal.admin() && !principal.canSend())
            throw new ApiException(403, "This account does not have permission to send WhatsApp messages.");
    }

    private static void requireAdmin(WhatsAppPrincipal principal) {
        if (!principal.admin())
            throw new ApiException(403, "Only the administrator can manage staff inbox access.");
    }

    private static String value(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static Long parseOptionalLong(String value, String field) {
        if (value == null || value.isBlank()) return null;
        try {
            long parsed = Long.parseLong(value.trim());
            if (parsed <= 0) throw new NumberFormatException();
            return parsed;
        } catch (NumberFormatException e) {
            throw new ApiException(400, field + " must be a positive integer.");
        }
    }

    private static ResponseEntity<?> execute(Supplier<?> action) {
        return execute(action, 200);
    }

    private static ResponseEntity<?> execute(Supplier<?> action, int successStatus) {
        try {
            return ResponseEntity.status(successStatus).contentType(MediaType.APPLICATION_JSON).body(action.get());
        } catch (ApiException e) {
            return ResponseEntity.status(e.getStatus()).contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            log.error("WhatsApp API request failed", e);
            return ResponseEntity.internalServerError().contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("error", "Internal error"));
        }
    }
}
