package com.vobiz.masking.repo;

import com.vobiz.masking.model.Setting;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Key/value settings store (ops-console editable), backed by Spring Data JPA
 * ({@link SettingRepository}). Since the entity id is the key, {@code save()} upserts.
 */
public class SettingsDao {

    private final SettingRepository repo;

    public SettingsDao(SettingRepository repo) {
        this.repo = repo;
    }

    public Map<String, String> getAll() {
        Map<String, String> m = new LinkedHashMap<>();
        for (Setting s : repo.findAllByOrderByKeyAsc()) m.put(s.getKey(), s.getValue());
        return m;
    }

    public String get(String key, String def) {
        return repo.findById(key).map(Setting::getValue).orElse(def);
    }

    public int getInt(String key, int def) {
        try {
            return Integer.parseInt(get(key, String.valueOf(def)).trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    public boolean getBool(String key, boolean def) {
        return Boolean.parseBoolean(get(key, String.valueOf(def)));
    }

    @Transactional
    public void put(String key, String value) {
        repo.save(new Setting(key, value));
    }
}
