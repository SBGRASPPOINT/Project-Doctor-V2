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

/** Individually revocable WhatsApp-only access for an agent or compounder. */
@Entity
@Table(name = "whatsapp_staff_access", indexes = {
        @Index(name = "idx_whatsapp_staff_role_active", columnList = "role, active")
})
@Getter
@Setter
@NoArgsConstructor
public class WhatsAppStaffAccess implements Jsonable {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 120)
    private String name;

    /** agent | compounder */
    @Column(nullable = false, length = 24)
    private String role;

    /** SHA-256 of a high-entropy, one-time displayed bearer token. */
    @Column(name = "token_hash", nullable = false, unique = true, length = 64)
    private String tokenHash;

    @Column(name = "can_read", nullable = false)
    private boolean canRead = true;

    @Column(name = "can_send", nullable = false)
    private boolean canSend = true;

    @Column(nullable = false)
    private boolean active = true;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        if (createdAt == null) createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }

    /** Never serialize tokenHash or the clear-text access token. */
    @Override
    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("name", name);
        m.put("role", role);
        m.put("can_read", canRead);
        m.put("can_send", canSend);
        m.put("active", active);
        m.put("created_at", createdAt == null ? null : createdAt.toString());
        m.put("updated_at", updatedAt == null ? null : updatedAt.toString());
        return m;
    }
}
