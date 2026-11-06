/* Shared clinic WhatsApp inbox. Plain JS; every request is scoped to a WhatsApp-only staff token or API_TOKEN. */
"use strict";

const WA_TOKEN_KEY = "appointment-whatsapp-access-token";
const waState = {
    token: "",
    profile: null,
    conversations: [],
    messages: [],
    hasMoreMessages: false,
    olderHistoryComplete: false,
    loadingOlder: false,
    selectedId: null,
    search: "",
    staff: [],
    polling: false,
    sending: false,
    pollTimer: null,
    searchTimer: null,
    toastTimer: null,
};

const wa$ = (selector) => document.querySelector(selector);

function waEsc(value) {
    return String(value ?? "").replace(/[&<>"']/g, (character) => ({
        "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;",
    }[character]));
}

function waToast(message, isError = false) {
    const toast = wa$("#wa-toast");
    toast.textContent = message;
    toast.className = "wa-toast" + (isError ? " err" : "");
    clearTimeout(waState.toastTimer);
    waState.toastTimer = setTimeout(() => toast.classList.add("hidden"), 4200);
}

function setConnection(label, mode) {
    const element = wa$("#wa-connection");
    element.textContent = label;
    element.className = "wa-connection" + (mode ? " " + mode : "");
}

async function waApi(method, path, body) {
    const headers = {};
    if (waState.token) headers.Authorization = "Bearer " + waState.token;
    if (body !== undefined) headers["Content-Type"] = "application/json";
    const response = await fetch(path, {
        method,
        headers,
        body: body === undefined ? undefined : JSON.stringify(body),
    });
    const text = await response.text();
    let data = null;
    try { data = text ? JSON.parse(text) : null; } catch { data = { error: text || "Unexpected server response" }; }
    if (!response.ok) {
        if (response.status === 401) {
            disconnect("Your access token is invalid or has been revoked. Sign in again.");
        }
        throw new Error((data && data.error) || ("Request failed (HTTP " + response.status + ")"));
    }
    return data;
}

function formatTime(value, withDate = false) {
    if (!value) return "";
    const date = new Date(value);
    if (Number.isNaN(date.getTime())) return String(value);
    return new Intl.DateTimeFormat(undefined, withDate
        ? { day: "numeric", month: "short", hour: "numeric", minute: "2-digit" }
        : { hour: "numeric", minute: "2-digit" }).format(date);
}

function formatDate(value) {
    if (!value) return "";
    const date = new Date(value);
    if (Number.isNaN(date.getTime())) return "";
    const today = new Date();
    if (date.toDateString() === today.toDateString()) return "Today";
    const yesterday = new Date(today);
    yesterday.setDate(today.getDate() - 1);
    if (date.toDateString() === yesterday.toDateString()) return "Yesterday";
    return new Intl.DateTimeFormat(undefined, { day: "numeric", month: "short", year: "numeric" }).format(date);
}

function selectedConversation() {
    return waState.conversations.find((conversation) => String(conversation.id) === String(waState.selectedId)) || null;
}

function configureComposer() {
    const profile = waState.profile || {};
    const canSend = Boolean(profile.admin || profile.can_send);
    const ready = Boolean(profile.send_configured && profile.same_number_ready);
    const enabled = canSend && ready && Boolean(waState.selectedId) && !waState.sending;
    wa$("#wa-send").disabled = !enabled;
    wa$("#wa-message").disabled = !enabled;
    wa$("#wa-new-phone").disabled = !canSend || !ready;
    wa$("#wa-new-name").disabled = !canSend || !ready;
    const newChatButton = document.querySelector("#wa-new-chat-form button[type=submit]");
    if (newChatButton) newChatButton.disabled = !canSend || !ready;

    const warning = wa$("#wa-send-warning");
    let message = "";
    if (profile.mode === "demo") {
        message = "Demo mode is on: outbound messages are saved to the shared inbox but are not delivered to WhatsApp.";
    } else if (!profile.send_configured) {
        message = "Vobiz WhatsApp is not configured. Add the Vobiz Messaging channel ID, WABA ID, and account credentials on the server.";
    } else if (!profile.same_number_ready) {
        message = profile.same_number_message || "The WhatsApp sender must match the clinic's M1 number before sending is enabled.";
    } else if (!profile.webhook_configured) {
        message = "Vobiz's WhatsApp Message URL and VOBIZ_WHATSAPP_WEBHOOK_SECRET are not configured, so inbound messages will not reach this inbox yet.";
    }
    warning.textContent = message;
    warning.classList.toggle("hidden", !message);

    if (!canSend) {
        wa$("#wa-compose-hint").textContent = "This account can view the team inbox but cannot send messages.";
    } else if (!ready) {
        wa$("#wa-compose-hint").textContent = "Sending is disabled until the WhatsApp sender is configured.";
    } else {
        wa$("#wa-compose-hint").textContent = "Replies are visible to every staff member with inbox access.";
    }
}

async function connect(token) {
    waState.token = token.trim();
    wa$("#wa-connect-error").classList.add("hidden");
    setConnection("Connecting…", "");
    try {
        const profile = await waApi("GET", "/api/whatsapp/me");
        if (!profile.admin && !profile.can_read) {
            throw new Error("This access token does not have permission to view the inbox.");
        }
        waState.profile = profile;
        const clinicNumber = profile.clinic_number || "M1 not set";
        const whatsAppNumber = profile.mode === "demo" ? "demo mode (not delivered)" : (profile.whatsapp_number || "sender not configured");
        wa$("#wa-number-label").textContent = `M1 ${clinicNumber} · WhatsApp ${whatsAppNumber}`;
        sessionStorage.setItem(WA_TOKEN_KEY, waState.token);
        wa$("#wa-connect").classList.add("hidden");
        wa$("#wa-inbox").classList.remove("hidden");
        wa$("#wa-logout").classList.remove("hidden");
        setConnection(profile.admin ? "Administrator" : profile.name + " · " + profile.role, "ok");
        configureComposer();
        if (profile.admin) {
            wa$("#wa-access-admin").classList.remove("hidden");
            try { await loadStaffAccess(); }
            catch (error) { waToast("Could not load staff access: " + error.message, true); }
        } else {
            wa$("#wa-access-admin").classList.add("hidden");
        }
        await refreshInbox();
        clearInterval(waState.pollTimer);
        waState.pollTimer = setInterval(() => {
            if (!document.hidden) refreshInbox();
        }, 5000);
    } catch (error) {
        setConnection("Not connected", "err");
        if (waState.token) sessionStorage.removeItem(WA_TOKEN_KEY);
        waState.token = "";
        wa$("#wa-connect-error").textContent = error.message;
        wa$("#wa-connect-error").classList.remove("hidden");
        throw error;
    }
}

function disconnect(message) {
    clearInterval(waState.pollTimer);
    waState.pollTimer = null;
    waState.token = "";
    waState.profile = null;
    waState.selectedId = null;
    waState.messages = [];
    waState.hasMoreMessages = false;
    waState.olderHistoryComplete = false;
    waState.loadingOlder = false;
    sessionStorage.removeItem(WA_TOKEN_KEY);
    wa$("#wa-inbox").classList.add("hidden");
    wa$("#wa-connect").classList.remove("hidden");
    wa$("#wa-logout").classList.add("hidden");
    wa$("#wa-thread-view").classList.add("hidden");
    wa$("#wa-thread-empty").classList.remove("hidden");
    wa$("#wa-connect-error").textContent = message || "";
    wa$("#wa-connect-error").classList.toggle("hidden", !message);
    wa$("#wa-number-label").textContent = "";
    wa$("#wa-inbox").classList.remove("has-selection");
    setConnection("Not connected", "");
}

async function refreshInbox() {
    if (!waState.profile || waState.polling) return;
    waState.polling = true;
    try {
        const params = new URLSearchParams({ limit: "100" });
        if (waState.search.trim()) params.set("q", waState.search.trim());
        const payload = await waApi("GET", "/api/whatsapp/conversations?" + params.toString());
        waState.conversations = payload.items || [];
        wa$("#wa-conversation-count").textContent = payload.count ?? waState.conversations.length;
        renderConversations();
        if (waState.selectedId && waState.conversations.some((item) => String(item.id) === String(waState.selectedId))) {
            await loadMessages(false);
            const selected = waState.conversations.find((item) => String(item.id) === String(waState.selectedId));
            if (selected && Number(selected.unread_count) > 0) {
                await waApi("POST", `/api/whatsapp/conversations/${encodeURIComponent(waState.selectedId)}/read`, {});
                selected.unread_count = 0;
                renderConversations();
            }
        } else if (waState.selectedId && !waState.conversations.some((item) => String(item.id) === String(waState.selectedId))) {
            waState.selectedId = null;
            waState.messages = [];
            showEmptyThread();
        }
        setConnection(waState.profile.admin ? "Administrator · live inbox" : waState.profile.name + " · live inbox", "ok");
    } catch (error) {
        if (waState.token) {
            setConnection("Inbox refresh failed", "err");
            waToast(error.message, true);
        }
    } finally {
        waState.polling = false;
    }
}

function renderConversations() {
    const container = wa$("#wa-conversation-list");
    if (!waState.conversations.length) {
        container.innerHTML = `<p class="wa-empty-list">${waState.search ? "No matching conversations." : "No conversations yet. Incoming WhatsApp messages will appear here."}</p>`;
        return;
    }
    container.innerHTML = waState.conversations.map((conversation) => {
        const title = conversation.contact_name || conversation.phone_e164 || "Unknown contact";
        const preview = conversation.last_message_preview || "No messages yet";
        const unread = Number(conversation.unread_count) || 0;
        return `<button type="button" class="wa-conversation ${String(conversation.id) === String(waState.selectedId) ? "active" : ""}" data-conversation-id="${waEsc(conversation.id)}" role="listitem">
            <span class="wa-conversation-avatar">${waEsc(initials(title))}</span>
            <span class="wa-conversation-name">${waEsc(title)}</span>
            <span class="wa-conversation-time">${waEsc(formatTime(conversation.last_message_at, true))}</span>
            <span class="wa-conversation-preview">${waEsc(preview)}</span>
            ${unread ? `<span class="wa-unread" aria-label="${unread} unread">${unread > 99 ? "99+" : unread}</span>` : `<span class="wa-status-mini">${waEsc(conversation.status || "open")}</span>`}
        </button>`;
    }).join("");
}

function initials(value) {
    const text = String(value || "?").trim();
    return Array.from(text)[0] || "?";
}

async function openConversation(id) {
    waState.selectedId = String(id);
    waState.messages = [];
    waState.hasMoreMessages = false;
    waState.olderHistoryComplete = false;
    waState.loadingOlder = false;
    wa$("#wa-inbox").classList.add("has-selection");
    renderConversations();
    renderThreadHeader();
    wa$("#wa-thread-empty").classList.add("hidden");
    wa$("#wa-thread-view").classList.remove("hidden");
    try {
        await waApi("POST", `/api/whatsapp/conversations/${encodeURIComponent(id)}/read`, {});
        await loadMessages(true);
        await refreshInbox();
    } catch (error) {
        waToast(error.message, true);
    }
}

function showEmptyThread() {
    waState.selectedId = null;
    waState.messages = [];
    waState.hasMoreMessages = false;
    waState.olderHistoryComplete = false;
    waState.loadingOlder = false;
    wa$("#wa-thread-view").classList.add("hidden");
    wa$("#wa-thread-empty").classList.remove("hidden");
    wa$("#wa-inbox").classList.remove("has-selection");
    renderConversations();
}

function renderThreadHeader() {
    const conversation = selectedConversation();
    if (!conversation) return;
    const title = conversation.contact_name || conversation.phone_e164 || "Unknown contact";
    wa$("#wa-contact-name").textContent = title;
    wa$("#wa-contact-phone").textContent = conversation.phone_e164 || "";
    wa$("#wa-contact-avatar").textContent = initials(title);
    configureComposer();
}

async function loadMessages(scrollToBottom) {
    const id = waState.selectedId;
    if (!id || !waState.profile) return;
    const list = wa$("#wa-messages");
    const nearBottom = list.scrollHeight - list.scrollTop - list.clientHeight < 90;
    const payload = await waApi("GET", `/api/whatsapp/conversations/${encodeURIComponent(id)}/messages?limit=100`);
    if (String(waState.selectedId) !== String(id)) return;
    const latest = payload.items || [];
    if (scrollToBottom || !waState.messages.length || !latest.length) {
        waState.messages = latest;
        if (scrollToBottom || !waState.messages.length) waState.olderHistoryComplete = !payload.has_more;
    } else {
        const firstLatestId = Number(latest[0].id);
        const older = waState.messages.filter((message) => Number(message.id) < firstLatestId);
        waState.messages = [...older, ...latest];
    }
    waState.hasMoreMessages = !waState.olderHistoryComplete && Boolean(payload.has_more);
    renderMessages();
    if (scrollToBottom || nearBottom) list.scrollTop = list.scrollHeight;
}

async function loadOlderMessages() {
    if (waState.loadingOlder || !waState.hasMoreMessages || !waState.selectedId || !waState.messages.length) return;
    const id = waState.selectedId;
    const firstId = waState.messages[0].id;
    const list = wa$("#wa-messages");
    const oldHeight = list.scrollHeight;
    const oldTop = list.scrollTop;
    waState.loadingOlder = true;
    renderMessages();
    try {
        const params = new URLSearchParams({ before_id: String(firstId), limit: "100" });
        const payload = await waApi("GET", `/api/whatsapp/conversations/${encodeURIComponent(id)}/messages?${params.toString()}`);
        if (String(waState.selectedId) !== String(id)) return;
        const currentIds = new Set(waState.messages.map((message) => String(message.id)));
        const older = (payload.items || []).filter((message) => !currentIds.has(String(message.id)));
        waState.messages = [...older, ...waState.messages];
        waState.hasMoreMessages = Boolean(payload.has_more);
        waState.olderHistoryComplete = !payload.has_more;
    } finally {
        waState.loadingOlder = false;
        if (String(waState.selectedId) === String(id)) {
            renderMessages();
            list.scrollTop = oldTop + (list.scrollHeight - oldHeight);
        }
    }
}

function renderMessages() {
    const container = wa$("#wa-messages");
    if (!waState.messages.length) {
        container.innerHTML = `<p class="wa-no-messages">No messages yet. Send the first message below.</p>`;
        return;
    }
    let previousDay = "";
    const content = [];
    for (const message of waState.messages) {
        const day = formatDate(message.created_at);
        if (day && day !== previousDay) {
            content.push(`<div class="wa-day-divider">${waEsc(day)}</div>`);
            previousDay = day;
        }
        const direction = message.direction === "outbound" ? "outbound" : "inbound";
        const isAttachmentLabel = message.media_id && /^\[[^\]]+ attachment\]$/.test(message.body || "");
        const text = isAttachmentLabel ? "" : message.body;
        const sender = direction === "outbound" && message.sender_name
            ? `<span class="wa-message-sender">${waEsc(message.sender_name)}</span>` : "";
        const media = message.media_id ? mediaMarkup(message) : "";
        const meta = [formatTime(message.created_at), direction === "outbound" ? message.status : ""].filter(Boolean).join(" · ");
        const error = message.status === "failed" && message.error_message
            ? `<span class="wa-message-error">${waEsc(message.error_message)}</span>` : "";
        content.push(`<article class="wa-message ${direction}" data-message-id="${waEsc(message.id)}">
            ${sender}
            ${text ? `<p class="wa-message-text">${waEsc(text)}</p>` : ""}
            ${media}
            ${error}
            <span class="wa-message-meta">${waEsc(meta)}</span>
        </article>`);
    }
    const olderButton = waState.hasMoreMessages
        ? `<button class="wa-load-older" type="button" data-action="load-older" ${waState.loadingOlder ? "disabled" : ""}>${waState.loadingOlder ? "Loading older messages…" : "Load older messages"}</button>`
        : "";
    container.innerHTML = olderButton + content.join("");
}

function mediaMarkup(message) {
    const label = message.media_filename || message.type || "attachment";
    const mime = message.media_mime_type ? ` · ${waEsc(message.media_mime_type)}` : "";
    return `<div class="wa-attachment-note">Attachment received: ${waEsc(label)}${mime}. Vobiz media preview is not configured yet.</div>`;
}

async function createConversation(form) {
    if (!waState.profile || !(waState.profile.admin || waState.profile.can_send))
        throw new Error("This account cannot start a conversation.");
    const formData = new FormData(form);
    const created = await waApi("POST", "/api/whatsapp/conversations", {
        phone_e164: String(formData.get("phone_e164") || "").trim(),
        contact_name: String(formData.get("contact_name") || "").trim() || null,
    });
    form.reset();
    form.closest("details").open = false;
    waState.search = "";
    wa$("#wa-search").value = "";
    await refreshInbox();
    await openConversation(created.id);
    waToast("Conversation is ready for the team.");
}

async function sendMessage(form) {
    const text = wa$("#wa-message").value.trim();
    if (!text || waState.sending) return;
    const id = waState.selectedId;
    if (!id) throw new Error("Choose a conversation first.");
    waState.sending = true;
    configureComposer();
    try {
        await waApi("POST", `/api/whatsapp/conversations/${encodeURIComponent(id)}/messages`, { text });
        form.reset();
        updateCharacterCount();
        await refreshInbox();
        wa$("#wa-messages").scrollTop = wa$("#wa-messages").scrollHeight;
    } catch (error) {
        await refreshInbox(); // A rejected provider send is retained as a failed message for the team.
        throw error;
    } finally {
        waState.sending = false;
        configureComposer();
    }
}

function updateCharacterCount() {
    wa$("#wa-char-count").textContent = wa$("#wa-message").value.length + " / 4096";
}

async function loadStaffAccess() {
    const payload = await waApi("GET", "/api/whatsapp/staff-access");
    waState.staff = payload.items || [];
    renderStaffAccess();
}

function renderStaffAccess() {
    const container = wa$("#wa-staff-list");
    if (!waState.staff.length) {
        container.innerHTML = `<p class="wa-help">No individual staff tokens yet. Create one for each agent or compounder who should have inbox access.</p>`;
        return;
    }
    container.innerHTML = waState.staff.map((member) => `<article class="wa-staff-card">
        <div class="wa-staff-card-head"><strong>${waEsc(member.name)}</strong><span>${waEsc(member.role)} · ${member.active ? "active" : "revoked"}</span></div>
        <p>${member.can_read ? "Can view" : "Cannot view"} · ${member.can_send ? "Can send" : "Cannot send"}</p>
        ${member.active ? `<div class="wa-staff-card-actions">
            <button class="secondary" type="button" data-action="rotate-staff" data-id="${waEsc(member.id)}">Rotate token</button>
            <button class="danger" type="button" data-action="revoke-staff" data-id="${waEsc(member.id)}">Revoke</button>
        </div>` : ""}
    </article>`).join("");
}

function showIssuedToken(result) {
    wa$("#wa-issued-token-value").textContent = result.access_token;
    wa$("#wa-issued-token").classList.remove("hidden");
    wa$("#wa-issued-token").scrollIntoView({ block: "nearest", behavior: "smooth" });
}

async function handleStaffAction(action, id) {
    if (action === "revoke-staff") {
        if (!window.confirm("Revoke this person's WhatsApp inbox access? Their current token will stop working.")) return;
        await waApi("PATCH", `/api/whatsapp/staff-access/${encodeURIComponent(id)}`, { active: false });
        waToast("Staff access revoked.");
    } else if (action === "rotate-staff") {
        if (!window.confirm("Rotate this person's token? Their previous token will stop working immediately.")) return;
        const result = await waApi("POST", `/api/whatsapp/staff-access/${encodeURIComponent(id)}/rotate`, {});
        showIssuedToken(result);
        waToast("New token created. Copy it now.");
    }
    await loadStaffAccess();
}

document.addEventListener("DOMContentLoaded", () => {
    wa$("#wa-connect-form").addEventListener("submit", async (event) => {
        event.preventDefault();
        const token = wa$("#wa-token").value;
        try { await connect(token); }
        catch { /* the form displays the specific error */ }
    });

    wa$("#wa-logout").addEventListener("click", () => disconnect(""));
    wa$("#wa-refresh").addEventListener("click", refreshInbox);
    wa$("#wa-search").addEventListener("input", (event) => {
        waState.search = event.target.value;
        clearTimeout(waState.searchTimer);
        waState.searchTimer = setTimeout(refreshInbox, 180);
    });

    wa$("#wa-new-chat-form").addEventListener("submit", async (event) => {
        event.preventDefault();
        try { await createConversation(event.target); }
        catch (error) { waToast(error.message, true); }
    });

    wa$("#wa-compose").addEventListener("submit", async (event) => {
        event.preventDefault();
        try { await sendMessage(event.target); }
        catch (error) { waToast(error.message, true); }
    });
    wa$("#wa-message").addEventListener("input", updateCharacterCount);
    wa$("#wa-message").addEventListener("keydown", (event) => {
        if (event.key === "Enter" && !event.shiftKey && !event.isComposing) {
            event.preventDefault();
            wa$("#wa-compose").requestSubmit();
        }
    });

    const staffCanRead = wa$("#wa-staff-form [name=can_read]");
    const staffCanSend = wa$("#wa-staff-form [name=can_send]");
    staffCanRead.addEventListener("change", () => {
        staffCanSend.disabled = !staffCanRead.checked;
        if (!staffCanRead.checked) staffCanSend.checked = false;
    });

    wa$("#wa-staff-form").addEventListener("submit", async (event) => {
        event.preventDefault();
        const form = event.target;
        const formData = new FormData(form);
        try {
            const result = await waApi("POST", "/api/whatsapp/staff-access", {
                name: String(formData.get("name") || "").trim(),
                role: String(formData.get("role") || "agent"),
                can_read: formData.has("can_read"),
                can_send: formData.has("can_send"),
            });
            showIssuedToken(result);
            form.reset();
            form.querySelector('[name="can_read"]').checked = true;
            form.querySelector('[name="can_send"]').disabled = false;
            form.querySelector('[name="can_send"]').checked = true;
            await loadStaffAccess();
            waToast("Staff access created. Copy and deliver the token securely.");
        } catch (error) { waToast(error.message, true); }
    });

    wa$("#wa-copy-token").addEventListener("click", async () => {
        const token = wa$("#wa-issued-token-value").textContent;
        try {
            await navigator.clipboard.writeText(token);
            waToast("Token copied to clipboard.");
        } catch {
            waToast("Clipboard unavailable. Select and copy the token manually.", true);
        }
    });

    wa$("#wa-conversation-list").addEventListener("click", (event) => {
        const button = event.target.closest("[data-conversation-id]");
        if (button) openConversation(button.dataset.conversationId);
    });

    document.addEventListener("click", async (event) => {
        const action = event.target.closest("[data-action]");
        if (!action) return;
        try {
            if (action.dataset.action === "load-older") await loadOlderMessages();
            else if (action.dataset.action === "revoke-staff" || action.dataset.action === "rotate-staff")
                await handleStaffAction(action.dataset.action, action.dataset.id);
        } catch (error) { waToast(error.message, true); }
    });

    // Mobile back control is a real button for keyboard and screen-reader users.
    const back = document.createElement("button");
    back.type = "button";
    back.className = "wa-mobile-back secondary";
    back.textContent = "‹";
    back.setAttribute("aria-label", "Back to conversations");
    back.addEventListener("click", showEmptyThread);
    wa$(".wa-thread-heading").prepend(back);

    document.addEventListener("visibilitychange", () => {
        if (!document.hidden) refreshInbox();
    });

    const savedToken = sessionStorage.getItem(WA_TOKEN_KEY);
    if (savedToken) {
        wa$("#wa-token").value = savedToken;
        connect(savedToken).catch(() => {});
    }
});
