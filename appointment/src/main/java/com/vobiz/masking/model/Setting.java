package com.vobiz.masking.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Key/value settings edited from the ops console (M1 number, ring timeout, routing mode, texts…).
 * JPA entity — the {@code settings} table is created by Hibernate; the primary key is the key
 * itself, so {@code save()} is an upsert.
 */
@Entity
@Table(name = "settings")
@Getter
@Setter
@NoArgsConstructor
public class Setting {

    @Id
    @Column(name = "key", length = 64)
    private String key;

    @Column(name = "value", nullable = false, columnDefinition = "text")
    private String value;

    public Setting(String key, String value) {
        this.key = key;
        this.value = value;
    }
}
