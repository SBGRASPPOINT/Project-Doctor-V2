package com.vobiz.masking.whatsapp.model;

import com.vobiz.masking.util.Json.Jsonable;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/** One shared inbox thread per WhatsApp contact. */
@Entity
@Table(name = "whatsapp_conversations", indexes = {
        @Index(name = "idx_whatsapp_conversation_last_message", columnList = "last_message_at"),
        @Index(name = "idx_whatsapp_conversation_status", columnList = "status")
})
@Getter
@Setter
@NoArgsConstructor
public class WhatsAppConversation implements Jsonable {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Provider contact ID (digits only), normalized from inbound WhatsApp messages. */
    @Column(name = "wa_id", nullable = false, unique = true, length = 32)
    private String waId;

    @Column(name = "phone_e164", nullable = false, unique = true, length = 32)
    private String phoneE164;

    @Column(name = "contact_name", length = 120)
    private String contactName;

    /** open | closed. Closing is informational; the conversation remains searchable. */
    @Column(nullable = false, length = 16)
    private String status = "open";

    @Column(name = "unread_count", nullable = false)
    private int unreadCount;

    @Column(name = "last_message_preview", length = 500)
    private String lastMessagePreview;

    @Column(name = "last_message_at")
    private Instant lastMessageAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        if (createdAt == null) createdAt = now;
        updatedAt = now;
        if (lastMessageAt == null) lastMessageAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }

    @Override
    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("wa_id", waId);
        m.put("phone_e164", phoneE164);
        m.put("contact_name", contactName);
        m.put("status", status);
        m.put("unread_count", unreadCount);
        m.put("last_message_preview", lastMessagePreview);
        m.put("last_message_at", lastMessageAt == null ? null : lastMessageAt.toString());
        m.put("created_at", createdAt == null ? null : createdAt.toString());
        return m;
    }
}
