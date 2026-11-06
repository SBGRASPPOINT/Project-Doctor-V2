package com.vobiz.masking.whatsapp;

import com.vobiz.masking.config.AppProperties;
import com.vobiz.masking.config.VobizProperties;
import com.vobiz.masking.service.SettingsService;
import com.vobiz.masking.util.ApiException;
import com.vobiz.masking.whatsapp.model.WhatsAppConversation;
import com.vobiz.masking.whatsapp.model.WhatsAppMessage;
import com.vobiz.masking.whatsapp.repo.WhatsAppConversationRepository;
import com.vobiz.masking.whatsapp.repo.WhatsAppMessageRepository;
import com.vobiz.masking.security.WhatsAppPrincipal;
import org.springframework.data.domain.PageRequest;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/** Persistence, shared inbox behavior and webhook normalization for WhatsApp conversations. */
public class WhatsAppService {

    private static final Pattern E164 = Pattern.compile("^\\+[1-9]\\d{6,14}$");
    private static final int MAX_TEXT_LENGTH = 4096;

    private final WhatsAppConversationRepository conversations;
    private final WhatsAppMessageRepository messages;
    private final WhatsAppTransport transport;
    private final VobizProperties vobiz;
    private final SettingsService settings;
    private final AppProperties app;

    public WhatsAppService(WhatsAppConversationRepository conversations,
                           WhatsAppMessageRepository messages,
                           WhatsAppTransport transport,
                           VobizProperties vobiz,
                           SettingsService settings,
                           AppProperties app) {
        this.conversations = conversations;
        this.messages = messages;
        this.transport = transport;
        this.vobiz = vobiz;
        this.settings = settings;
        this.app = app;
    }

    public Map<String, Object> setupStatus() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("mode", transport.isMock() ? "demo" : (transport.isConfigured() ? "vobiz_whatsapp" : "not_configured"));
        result.put("send_configured", transport.isConfigured());
        result.put("webhook_configured", !blank(vobiz.getWhatsappWebhookSecret())
                && !blank(vobiz.getWhatsappChannelId()) && !blank(vobiz.getWhatsappWabaId())
                && isClinicNumberConfigured());
        result.put("clinic_number", settings.m1(app.getDefaultM1Number()));
        result.put("whatsapp_number", blank(vobiz.getWhatsappNumber()) ? null : vobiz.getWhatsappNumber().trim());
        result.put("same_number_ready", transport.isMock() || isClinicNumberConfigured());
        result.put("same_number_message", transport.isMock() || isClinicNumberConfigured()
                ? null : "Set VOBIZ_WHATSAPP_NUMBER to the same E.164 number as the clinic's M1.");
        return result;
    }

    @Transactional(readOnly = true)
    public List<WhatsAppConversation> listConversations(String query, int requestedLimit) {
        int limit = Math.max(1, Math.min(200, requestedLimit));
        String safeQuery = query == null ? "" : query.trim().substring(0, Math.min(120, query.trim().length()));
        return conversations.search(safeQuery, PageRequest.of(0, limit));
    }

    @Transactional
    public WhatsAppConversation createConversation(String phoneE164, String contactName) {
        String phone = phoneE164 == null ? "" : phoneE164.trim();
        if (!E164.matcher(phone).matches())
            throw new ApiException(400, "phone_e164 must be in E.164 format, e.g. +919876543210");
        String waId = phone.substring(1);
        String name = optionalName(contactName);

        WhatsAppConversation conversation = conversations.findByWaId(waId)
                .or(() -> conversations.findByPhoneE164(phone))
                .orElseGet(WhatsAppConversation::new);
        conversation.setWaId(waId);
        conversation.setPhoneE164(phone);
        if (name != null) conversation.setContactName(name);
        if (conversation.getStatus() == null || conversation.getStatus().isBlank()) conversation.setStatus("open");
        if (conversation.getLastMessageAt() == null) conversation.setLastMessageAt(Instant.now());
        return conversations.save(conversation);
    }

    @Transactional(readOnly = true)
    public List<WhatsAppMessage> listMessages(long conversationId, Long beforeId, int requestedLimit) {
        requireConversation(conversationId);
        int limit = Math.max(1, Math.min(200, requestedLimit));
        PageRequest page = PageRequest.of(0, limit);
        List<WhatsAppMessage> rows = beforeId == null
                ? messages.findByConversationIdOrderByIdDesc(conversationId, page)
                : messages.findByConversationIdAndIdLessThanOrderByIdDesc(conversationId, beforeId, page);
        List<WhatsAppMessage> chronological = new ArrayList<>(rows);
        Collections.reverse(chronological);
        return chronological;
    }

    @Transactional
    public WhatsAppConversation markRead(long conversationId) {
        WhatsAppConversation conversation = requireConversation(conversationId);
        conversation.setUnreadCount(0);
        return conversations.save(conversation);
    }

    /** Persist first, then call Vobiz, so a provider failure remains visible to the shared team. */
    public WhatsAppMessage sendText(long conversationId, String rawText, WhatsAppPrincipal sender) {
        String text = rawText == null ? "" : rawText.trim();
        if (text.isEmpty()) throw new ApiException(400, "Message text cannot be empty.");
        if (text.length() > MAX_TEXT_LENGTH) throw new ApiException(400, "Message text is too long (max 4096 characters).");
        if (!transport.isConfigured()) throw new ApiException(503, "Vobiz WhatsApp is not configured on the server.");
        if (!transport.isMock() && !isClinicNumberConfigured())
            throw new ApiException(503, "Vobiz WhatsApp channel must be configured for the same number as clinic M1.");

        WhatsAppConversation conversation = requireConversation(conversationId);
        Instant now = Instant.now();
        WhatsAppMessage outgoing = new WhatsAppMessage();
        outgoing.setConversationId(conversationId);
        outgoing.setDirection("outbound");
        outgoing.setMessageType("text");
        outgoing.setBody(text);
        outgoing.setStatus("pending");
        outgoing.setSenderName(sender == null ? "Staff" : sender.name());
        outgoing.setSenderStaffId(sender == null ? null : sender.id());
        outgoing.setCreatedAt(now);
        outgoing.setProviderTimestamp(now);
        outgoing = messages.save(outgoing);

        try {
            String providerId = transport.sendText(conversation.getPhoneE164(), text);
            outgoing.setProviderMessageId(providerId);
            outgoing.setStatus(transport.isMock() ? "demo" : "sent");
            outgoing.setErrorCode(null);
            outgoing.setErrorMessage(null);
        } catch (ApiException e) {
            outgoing.setStatus("failed");
            outgoing.setErrorMessage(shortText(e.getMessage(), 500));
            outgoing = messages.save(outgoing);
            updateConversationAfterOutbound(conversation, outgoing, now);
            throw e;
        }
        outgoing = messages.save(outgoing);
        updateConversationAfterOutbound(conversation, outgoing, now);
        return outgoing;
    }

    /** Vobiz sends message.inbound and message-status events; provider IDs deduplicate webhook retries. */
    @Transactional
    public void receiveWebhook(Map<String, Object> payload) {
        if (payload == null) throw new ApiException(400, "Invalid Vobiz WhatsApp webhook payload.");
        String eventName = firstNonBlank(string(payload.get("event")), string(payload.get("event_type")));
        if (blank(eventName) && string(payload.get("type")) != null
                && string(payload.get("type")).toLowerCase(Locale.ROOT).startsWith("message."))
            eventName = string(payload.get("type"));
        Map<String, Object> data = object(payload.get("data"));
        if (data == null) data = payload;
        Map<String, Object> message = object(data.get("message"));
        if (message == null) message = data;
        if (!matchesVobizChannel(payload, data, message)) return;
        String normalizedEvent = eventName == null ? "" : eventName.trim().toLowerCase(Locale.ROOT);
        if ("message.inbound".equals(normalizedEvent)) {
            receiveMessage(message, data);
            return;
        }
        if (normalizedEvent.startsWith("message.")) {
            Map<String, Object> status = new LinkedHashMap<>(payload);
            status.putAll(data);
            status.putAll(message);
            if (blank(string(status.get("id"))))
                status.put("id", firstNonBlank(string(status.get("message_id")), string(data.get("message_id"))));
            if (blank(string(status.get("status"))) && !"message.status".equals(normalizedEvent))
                status.put("status", normalizedEvent.substring("message.".length()));
            receiveStatus(status);
        }
    }

    private void receiveMessage(Map<String, Object> payload, Map<String, Object> envelope) {
        String providerId = firstNonBlank(string(payload.get("id")), string(payload.get("message_id")),
                string(payload.get("provider_message_id")));
        String rawFrom = firstNonBlank(string(payload.get("from")), string(payload.get("from_e164")));
        String waId = normalizeWaId(rawFrom);
        if (blank(providerId) || waId == null || messages.findByProviderMessageId(providerId).isPresent()) return;

        String type = firstNonBlank(string(payload.get("type")), string(payload.get("message_type")), "unknown")
                .toLowerCase(Locale.ROOT);
        Object contentValue = payload.get(type);
        if (contentValue == null) contentValue = payload.get("content");
        if (contentValue == null && "text".equals(type)) {
            Map<String, Object> text = new LinkedHashMap<>();
            text.put("body", firstNonBlank(string(payload.get("body")), string(payload.get("text"))));
            contentValue = text;
        }
        MessageContent content = extractContent(type, contentValue);
        String phone = "+" + waId;
        String configuredNumber = vobiz.getWhatsappNumber();
        String destination = firstNonBlank(string(payload.get("to")), string(envelope.get("to")));
        if (!blank(destination) && !blank(configuredNumber)
                && !destination.replaceAll("[^0-9]", "").equals(configuredNumber.replaceAll("[^0-9]", ""))) return;
        String senderName = firstNonBlank(string(payload.get("contact_name")), string(payload.get("profile_name")));
        Instant timestamp = timestamp(firstNonBlank(string(payload.get("timestamp")), string(envelope.get("timestamp"))));

        WhatsAppConversation conversation = conversations.findByWaId(waId)
                .or(() -> conversations.findByPhoneE164(phone))
                .orElseGet(WhatsAppConversation::new);
        conversation.setWaId(waId);
        conversation.setPhoneE164(phone);
        if (!blank(senderName)) conversation.setContactName(shortText(senderName.trim(), 120));
        if (blank(conversation.getStatus())) conversation.setStatus("open");

        WhatsAppMessage incoming = new WhatsAppMessage();
        incoming.setProviderMessageId(providerId);
        incoming.setDirection("inbound");
        incoming.setMessageType(shortText(type, 32));
        incoming.setBody(content.body());
        incoming.setStatus("received");
        incoming.setMediaId(content.mediaId());
        incoming.setMediaMimeType(shortText(content.mediaMimeType(), 120));
        incoming.setMediaFilename(shortText(content.mediaFilename(), 255));
        incoming.setCreatedAt(timestamp);
        incoming.setProviderTimestamp(timestamp);

        Instant lastAt = conversation.getLastMessageAt();
        if (lastAt == null || !timestamp.isBefore(lastAt)) {
            conversation.setLastMessageAt(timestamp);
            conversation.setLastMessagePreview(preview(content.body()));
        }
        conversation.setUnreadCount(Math.max(0, conversation.getUnreadCount()) + 1);
        conversation.setStatus("open");
        conversation = conversations.save(conversation);
        incoming.setConversationId(conversation.getId());
        messages.save(incoming);
    }

    private void receiveStatus(Map<String, Object> payload) {
        String providerId = firstNonBlank(string(payload.get("id")), string(payload.get("message_id")),
                string(payload.get("provider_message_id")));
        String next = string(payload.get("status"));
        if (blank(providerId) || blank(next)) return;
        next = next.toLowerCase(Locale.ROOT);
        Optional<WhatsAppMessage> found = messages.findByProviderMessageId(providerId);
        if (found.isEmpty()) return; // receipt can arrive before a slow send request commits

        WhatsAppMessage message = found.get();
        String current = message.getStatus() == null ? "pending" : message.getStatus();
        if ("failed".equals(next)) {
            if (!"delivered".equals(current) && !"read".equals(current)) {
                message.setStatus("failed");
                Map<String, Object> error = object(payload.get("error"));
                if (error == null) error = firstObject(payload.get("errors"));
                if (error != null) {
                    if (error.get("code") != null) message.setErrorCode(shortText(string(error.get("code")), 64));
                    String description = firstNonBlank(string(error.get("title")), string(error.get("message")));
                    if (description != null) message.setErrorMessage(shortText(description, 500));
                }
            }
        } else if (statusRank(next) >= 0 && statusRank(next) >= statusRank(current) && !"failed".equals(current)) {
            message.setStatus(next);
            message.setErrorCode(null);
            message.setErrorMessage(null);
        }
        messages.save(message);
    }

    private void updateConversationAfterOutbound(WhatsAppConversation conversation,
                                                 WhatsAppMessage message, Instant timestamp) {
        if (conversation.getLastMessageAt() == null || !timestamp.isBefore(conversation.getLastMessageAt())) {
            conversation.setLastMessageAt(timestamp);
            conversation.setLastMessagePreview(preview(message.getBody()));
        }
        conversation.setStatus("open");
        conversations.save(conversation);
    }

    private WhatsAppConversation requireConversation(long id) {
        return conversations.findById(id)
                .orElseThrow(() -> new ApiException(404, "WhatsApp conversation not found: " + id));
    }

    private boolean matchesVobizChannel(Map<String, Object> envelope,
                                        Map<String, Object> data,
                                        Map<String, Object> message) {
        if (!isClinicNumberConfigured()) return false;
        String expectedChannel = vobiz.getWhatsappChannelId();
        String expectedWaba = vobiz.getWhatsappWabaId();
        String channelId = firstNonBlank(string(data.get("channel_id")), string(envelope.get("channel_id")),
                string(message.get("channel_id")));
        String wabaId = firstNonBlank(string(data.get("waba_id")), string(envelope.get("waba_id")),
                string(message.get("waba_id")));
        return !blank(expectedChannel) && expectedChannel.trim().equals(channelId)
                && !blank(expectedWaba) && (blank(wabaId) || expectedWaba.trim().equals(wabaId));
    }

    private boolean isClinicNumberConfigured() {
        String clinicNumber = settings.m1(app.getDefaultM1Number());
        String senderNumber = vobiz.getWhatsappNumber();
        return E164.matcher(clinicNumber == null ? "" : clinicNumber.trim()).matches()
                && E164.matcher(senderNumber == null ? "" : senderNumber.trim()).matches()
                && clinicNumber.trim().equals(senderNumber.trim());
    }

    static MessageContent extractContent(String type, Object contentValue) {
        Map<String, Object> content = object(contentValue);
        if (content == null && "text".equals(type) && contentValue instanceof String text)
            return new MessageContent(shortText(text, 4000), null, null, null);
        if (content == null) return new MessageContent("[" + type + " message]", null, null, null);
        String body = null;
        String mediaId = null;
        String mime = null;
        String filename = null;
        switch (type) {
            case "text" -> body = string(content.get("body"));
            case "button" -> body = string(content.get("text"));
            case "interactive" -> {
                Map<String, Object> button = object(content.get("button_reply"));
                Map<String, Object> row = object(content.get("list_reply"));
                body = firstNonBlank(button == null ? null : string(button.get("title")),
                        row == null ? null : string(row.get("title")), "Interactive reply");
            }
            case "reaction" -> {
                Map<String, Object> reaction = object(content.get("reaction"));
                body = reaction == null ? "Reaction" : firstNonBlank(string(reaction.get("emoji")), "Reaction");
            }
            case "location" -> {
                String name = string(content.get("name"));
                String address = string(content.get("address"));
                String latitude = string(content.get("latitude"));
                String longitude = string(content.get("longitude"));
                body = firstNonBlank(name, "Location")
                        + (blank(address) ? "" : " — " + address)
                        + (blank(latitude) || blank(longitude) ? "" : " (" + latitude + ", " + longitude + ")");
            }
            case "image", "audio", "video", "document", "sticker" -> {
                mediaId = string(content.get("id"));
                mime = string(content.get("mime_type"));
                filename = string(content.get("filename"));
                body = string(content.get("caption"));
                if (blank(body)) body = "[" + type + " attachment]";
            }
            case "contacts" -> body = contactSummary(content.get("contacts"));
            default -> body = "[" + type + " message]";
        }
        return new MessageContent(shortText(body == null ? "" : body, 4000), mediaId, mime, filename);
    }

    private static String contactSummary(Object value) {
        List<String> parts = new ArrayList<>();
        for (Object contactValue : list(value)) {
            Map<String, Object> contact = object(contactValue);
            if (contact == null) continue;
            Map<String, Object> nameObject = object(contact.get("name"));
            String name = nameObject == null ? null : firstNonBlank(
                    string(nameObject.get("formatted_name")), string(nameObject.get("first_name")));
            List<Object> phones = list(contact.get("phones"));
            String phone = null;
            if (!phones.isEmpty()) {
                Map<String, Object> phoneObject = object(phones.get(0));
                if (phoneObject != null) phone = string(phoneObject.get("phone"));
            }
            parts.add(firstNonBlank(name, "Contact") + (blank(phone) ? "" : " — " + phone));
        }
        return parts.isEmpty() ? "[Contact card]" : String.join("\n", parts);
    }

    private static Instant timestamp(Object value) {
        if (value instanceof Number n) {
            try { return Instant.ofEpochSecond(n.longValue()); }
            catch (Exception ignored) { return Instant.now(); }
        }
        if (value == null) return Instant.now();
        String text = String.valueOf(value).trim();
        try { return Instant.ofEpochSecond(Long.parseLong(text)); }
        catch (NumberFormatException ignored) {
            try { return Instant.parse(text); }
            catch (Exception ignoredAgain) { return Instant.now(); }
        }
    }

    private static int statusRank(String status) {
        return switch (status == null ? "" : status.toLowerCase(Locale.ROOT)) {
            case "pending", "queued", "accepted" -> 0;
            case "sent" -> 1;
            case "delivered" -> 2;
            case "read" -> 3;
            default -> -1;
        };
    }

    private static String normalizeWaId(String raw) {
        if (raw == null) return null;
        String digits = raw.replaceAll("\\D", "");
        return digits.matches("[1-9]\\d{6,14}") ? digits : null;
    }

    private static String optionalName(String value) {
        if (value == null || value.isBlank()) return null;
        String name = value.trim();
        if (name.length() > 120) throw new ApiException(400, "contact_name must be at most 120 characters");
        return name;
    }

    private static String preview(String text) {
        if (text == null) return "";
        return shortText(text.replaceAll("\\s+", " ").trim(), 500);
    }

    private static String shortText(String value, int max) {
        if (value == null) return null;
        return value.length() <= max ? value : value.substring(0, max);
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) if (!blank(value)) return value;
        return null;
    }

    private static String string(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static Map<String, Object> object(Object value) {
        if (!(value instanceof Map<?, ?> map)) return null;
        Map<String, Object> result = new LinkedHashMap<>();
        map.forEach((key, item) -> result.put(String.valueOf(key), item));
        return result;
    }

    private static Map<String, Object> firstObject(Object value) {
        List<Object> list = list(value);
        return list.isEmpty() ? null : object(list.get(0));
    }

    private static List<Object> list(Object value) {
        if (!(value instanceof List<?> values)) return List.of();
        return new ArrayList<>(values);
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    record MessageContent(String body, String mediaId, String mediaMimeType, String mediaFilename) {
    }
}
