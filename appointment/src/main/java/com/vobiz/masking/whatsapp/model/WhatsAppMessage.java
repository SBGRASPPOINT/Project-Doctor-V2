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

/** A durable inbound/outbound message or provider delivery-status update in the shared WhatsApp inbox. */
@Entity
@Table(name = "whatsapp_messages", indexes = {
        @Index(name = "idx_whatsapp_message_conversation", columnList = "conversation_id, id"),
        @Index(name = "idx_whatsapp_message_provider_id", columnList = "provider_message_id"),
        @Index(name = "idx_whatsapp_message_media_id", columnList = "media_id")
})
@Getter
@Setter
@NoArgsConstructor
public class WhatsAppMessage implements Jsonable {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "conversation_id", nullable = false)
    private Long conversationId;

    /** Provider message ID (often a wamid). Null for a local pending row; used to deduplicate callbacks. */
    @Column(name = "provider_message_id", unique = true, length = 180)
    private String providerMessageId;

    /** inbound | outbound */
    @Column(nullable = false, length = 12)
    private String direction;

    /** text | image | audio | video | document | location | interactive | ... */
    @Column(name = "message_type", nullable = false, length = 32)
    private String messageType = "text";

    @Column(name = "body", columnDefinition = "text")
    private String body;

    /** pending | received | sent | delivered | read | failed */
    @Column(nullable = false, length = 16)
    private String status;

    @Column(name = "sender_name", length = 120)
    private String senderName;

    @Column(name = "sender_staff_id")
    private Long senderStaffId;

    @Column(name = "media_id", length = 180)
    private String mediaId;

    @Column(name = "media_mime_type", length = 120)
    private String mediaMimeType;

    @Column(name = "media_filename", length = 255)
    private String mediaFilename;

    @Column(name = "error_code", length = 64)
    private String errorCode;

    @Column(name = "error_message", length = 500)
    private String errorMessage;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "provider_timestamp")
    private Instant providerTimestamp;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        if (createdAt == null) createdAt = now;
        if (updatedAt == null) updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }

    @Override
    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("conversation_id", conversationId);
        m.put("provider_message_id", providerMessageId);
        m.put("direction", direction);
        m.put("type", messageType);
        m.put("body", body);
        m.put("status", status);
        m.put("sender_name", senderName);
        m.put("sender_staff_id", senderStaffId);
        m.put("media_id", mediaId);
        m.put("media_mime_type", mediaMimeType);
        m.put("media_filename", mediaFilename);
        m.put("error_code", errorCode);
        m.put("error_message", errorMessage);
        m.put("created_at", createdAt == null ? null : createdAt.toString());
        m.put("provider_timestamp", providerTimestamp == null ? null : providerTimestamp.toString());
        return m;
    }
}
