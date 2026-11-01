package com.vobiz.masking.appointment.service;

import com.vobiz.masking.appointment.model.MessageTemplate;
import com.vobiz.masking.appointment.repo.MessageTemplateRepository;

import java.util.Map;

/**
 * Looks up IVR/reminder prompt text in {@code message_templates} by (key, patient language),
 * falling back to English, and substitutes {placeholders}. No prompt string is hard-coded
 * in Java — all copy is editable/translatable from the console without a rebuild.
 */
public class PromptService {

    private final MessageTemplateRepository repo;

    public PromptService(MessageTemplateRepository repo) {
        this.repo = repo;
    }

    public String render(String key, String language, Map<String, String> vars) {
        String lang = (language == null || language.isBlank()) ? "en" : language.trim();
        String text = repo.findByKeyAndLanguageAndActiveTrue(key, lang)
                .or(() -> repo.findByKeyAndLanguageAndActiveTrue(key, "en"))
                .map(MessageTemplate::getText)
                .orElse(key);
        return substitute(text, vars);
    }

    public static String substitute(String text, Map<String, String> vars) {
        if (text == null) return "";
        String out = text;
        if (vars != null) {
            for (Map.Entry<String, String> e : vars.entrySet()) {
                out = out.replace("{" + e.getKey() + "}", e.getValue() == null ? "" : e.getValue());
            }
        }
        return out;
    }

    /** Pure language fallback — unit-testable without a database. */
    public static String pick(String requestedLanguage, Map<String, String> textByLanguage) {
        if (requestedLanguage != null && textByLanguage.containsKey(requestedLanguage)) {
            return textByLanguage.get(requestedLanguage);
        }
        return textByLanguage.getOrDefault("en", "");
    }
}
