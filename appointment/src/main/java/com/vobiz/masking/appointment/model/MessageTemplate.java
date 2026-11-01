package com.vobiz.masking.appointment.model;

import com.vobiz.masking.util.Json.Jsonable;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Multi-language TTS prompt text. All IVR/reminder copy lives here (never hard-coded in Java)
 * so it can be edited and translated without a rebuild. Look up (key, patient.preferred_language),
 * fall back to 'en'.
 */
@Entity
@Table(name = "message_templates", uniqueConstraints = {
        @UniqueConstraint(name = "uq_template_key_lang", columnNames = {"key", "language"})
})
@Getter
@Setter
@NoArgsConstructor
public class MessageTemplate implements Jsonable {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** ivr_welcome_known | ivr_welcome_unknown | ivr_main_menu | ivr_no_slots | ivr_confirm_booking | ivr_cancel_done | reminder_t24h | reminder_change | ivr_no_input | … */
    @Column(name = "key", nullable = false, length = 64)
    private String key;

    @Column(nullable = false, length = 8)
    private String language = "en";

    @Column(name = "text", nullable = false, columnDefinition = "text")
    private String text;

    @Column(nullable = false)
    private boolean active = true;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    void onCreate() {
        if (updatedAt == null) updatedAt = Instant.now();
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }

    @Override
    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("key", key);
        m.put("language", language);
        m.put("text", text);
        m.put("active", active);
        m.put("updated_at", updatedAt == null ? null : updatedAt.toString());
        return m;
    }
}
