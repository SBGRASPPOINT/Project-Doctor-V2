/* Vobiz Call Masking — ops console. Plain JS, talks to the internal /api/* JSON API. */
"use strict";

const state = {
    tab: "dashboard",
    stats: {},
    agents: [],
    calls: [],
    callbacks: [],
    settings: {},
    callFilters: { status: "", from: "", to: "", q: "" },
    callbackFilter: "",
};

const $ = (sel) => document.querySelector(sel);

function esc(s) {
    return String(s ?? "").replace(/[&<>"']/g, (c) => ({
        "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;",
    }[c]));
}

function toast(msg, isErr) {
    const t = $("#toast");
    t.textContent = msg;
    t.className = "toast" + (isErr ? " err" : "");
    clearTimeout(toast._h);
    toast._h = setTimeout(() => t.classList.add("hidden"), 4000);
    t.classList.remove("hidden");
}

async function api(method, path, body) {
    const res = await fetch(path, {
        method,
        headers: body !== undefined ? { "Content-Type": "application/json" } : {},
        body: body !== undefined ? JSON.stringify(body) : undefined,
    });
    const text = await res.text();
    let data = null;
    try { data = text ? JSON.parse(text) : null; } catch { data = { raw: text }; }
    if (!res.ok) throw new Error((data && data.error) || ("HTTP " + res.status));
    return data;
}

function fmtTime(iso) {
    if (!iso) return "—";
    const d = new Date(iso);
    return isNaN(d) ? iso : d.toLocaleString();
}

function fmtDur(sec) {
    if (sec === null || sec === undefined) return "—";
    return sec + "s";
}

function statusBadge(s) {
    return `<span class="status ${esc(s)}">${esc(s)}</span>`;
}

// ---------- data loading ----------

async function loadStats() {
    state.stats = await api("GET", "/api/stats");
}

async function loadAgents() {
    state.agents = (await api("GET", "/api/agents")).items || [];
}

async function loadCalls() {
    const p = new URLSearchParams();
    for (const [k, v] of Object.entries(state.callFilters)) if (v) p.set(k, v);
    const r = await api("GET", "/api/calls?" + p.toString());
    state.calls = r.items || [];
}

async function loadCallbacks() {
    const p = new URLSearchParams();
    if (state.callbackFilter) p.set("status", state.callbackFilter);
    const r = await api("GET", "/api/callbacks?" + p.toString());
    state.callbacks = r.items || [];
}

async function loadSettings() {
    state.settings = (await api("GET", "/api/settings")).settings || {};
}

async function refresh() {
    try {
        await Promise.all([loadStats(), loadAgents()]);
        if (state.tab === "dashboard" || state.tab === "calls") await loadCalls();
        if (state.tab === "callbacks") await loadCallbacks();
        if (state.tab === "settings") await loadSettings();
        $("#conn").textContent = "connected";
        $("#conn").className = "conn ok";
        render();
    } catch (e) {
        $("#conn").textContent = "connection failed: " + e.message;
        $("#conn").className = "conn err";
    }
}

// ---------- rendering ----------

function render() {
    $("#pending-badge").textContent = state.stats.callbacks_pending || 0;
    $("#pending-badge").classList.toggle("hidden", !state.stats.callbacks_pending);
    if (state.tab === "dashboard") renderDashboard();
    if (state.tab === "agents") renderAgents();
    if (state.tab === "calls") renderCalls();
    if (state.tab === "callbacks") renderCallbacks();
    if (state.tab === "settings") renderSettings();
}

function card(num, lbl) {
    return `<div class="card"><div class="num">${esc(num)}</div><div class="lbl">${esc(lbl)}</div></div>`;
}

function callsTable(calls) {
    if (!calls.length) return `<p class="muted">No calls yet.</p>`;
    const rows = calls.map((c) => `<tr>
        <td>${esc(fmtTime(c.created_at))}</td>
        <td>${esc(c.direction)}</td>
        <td>${esc(c.caller_number || "")}</td>
        <td>${esc(c.dialed_number || "")}</td>
        <td>${esc(c.agent_name || (c.agent_id ? "#" + c.agent_id : "—"))}</td>
        <td>${statusBadge(c.status)}</td>
        <td>${esc(fmtDur(c.duration_sec))}</td>
        <td>${esc(c.hangup_cause || "")}</td>
        <td>${c.recording_url ? `<audio controls src="${esc(c.recording_url)}"></audio>` : "—"}</td>
    </tr>`).join("");
    return `<table><thead><tr>
        <th>time</th><th>dir</th><th>caller</th><th>dialed</th><th>agent</th>
        <th>status</th><th>dur</th><th>cause</th><th>recording</th>
    </tr></thead><tbody>${rows}</tbody></table>`;
}

function renderDashboard() {
    const s = state.stats;
    $("#stat-cards").innerHTML =
        card(s.calls_live || 0, "live calls") +
        card(s.calls_today || 0, "calls today") +
        card(s.calls_answered_today || 0, "answered today") +
        card(s.callbacks_pending || 0, "pending callbacks") +
        card(s.agents_active || 0, "active agents");
    const live = state.calls.filter((c) => c.status === "in_progress");
    $("#live-calls").innerHTML = live.length ? callsTable(live) : `<p class="muted">Nothing ringing right now.</p>`;
    $("#recent-calls").innerHTML = callsTable(state.calls.slice(0, 25));
}

function renderAgents() {
    const rows = state.agents.map((a) => `<tr data-id="${a.id}">
        <td><input data-f="name" value="${esc(a.name)}"></td>
        <td><input data-f="phone_e164" value="${esc(a.phone_e164)}"></td>
        <td><input data-f="priority" type="number" value="${a.priority}" style="width:70px"></td>
        <td><select data-f="status">
            ${["available", "busy", "offline"].map((s) => `<option ${s === a.status ? "selected" : ""}>${s}</option>`).join("")}
        </select></td>
        <td><input data-f="active" type="checkbox" ${a.active ? "checked" : ""}></td>
        <td>
            <button data-action="save-agent" data-id="${a.id}">Save</button>
            <button class="danger" data-action="delete-agent" data-id="${a.id}">Remove</button>
        </td>
    </tr>`).join("");
    $("#agents-table").innerHTML = `<table><thead><tr>
        <th>name</th><th>phone (E.164)</th><th>priority</th><th>status</th><th>active</th><th></th>
    </tr></thead><tbody>${rows}</tbody></table>`;
}

function renderCalls() {
    $("#calls-table").innerHTML = callsTable(state.calls);
}

function renderCallbacks() {
    if (!state.callbacks.length) {
        $("#callbacks-list").innerHTML = `<p class="muted">No callback requests.</p>`;
        return;
    }
    const agentOptions = state.agents.filter((a) => a.active).map((a) =>
        `<option value="${a.id}">${esc(a.name)}</option>`).join("");
    $("#callbacks-list").innerHTML = state.callbacks.map((cb) => `<div class="cb-card" data-id="${cb.id}">
        <div class="head">
            <span class="num">${esc(cb.caller_number)}</span>
            ${statusBadge(cb.status)}
            <span class="muted">${esc(fmtTime(cb.created_at))} · ${esc(fmtDur(cb.voicemail_duration_sec))} voicemail</span>
            ${cb.assigned_agent_name ? `<span class="muted">assigned: ${esc(cb.assigned_agent_name)}</span>` : ""}
        </div>
        ${cb.voicemail_url ? `<audio controls src="${esc(cb.voicemail_url)}"></audio>` : `<p class="muted">no recording</p>`}
        <div class="row">
            <select data-f="cb-status">
                ${["pending", "calling", "called_back", "closed"].map((s) => `<option ${s === cb.status ? "selected" : ""}>${s}</option>`).join("")}
            </select>
            <select data-f="cb-agent"><option value="">assign agent…</option>${agentOptions}</select>
            <button data-action="save-callback" data-id="${cb.id}">Save</button>
            <button data-action="call-callback" data-id="${cb.id}" ${cb.status !== "pending" ? "disabled" : ""}>📞 Call back</button>
        </div>
        <textarea data-f="cb-notes" placeholder="notes…">${esc(cb.notes || "")}</textarea>
    </div>`).join("");
}

const SETTING_LABELS = {
    m1_number: "Masked number M1 (caller ID)",
    ring_timeout_sec: "Ring timeout (s)",
    voicemail_max_sec: "Voicemail max length (s)",
    record_silence_timeout_sec: "Recording silence timeout (s)",
    routing_mode: "Routing mode",
    respect_presence: "Only ring 'available' agents",
    announce_recording: "Announce recording (TRAI)",
    announcement_text: "Recording announcement",
    greeting_text: "Voicemail greeting",
    closing_text: "Voicemail closing",
};

function renderSettings() {
    const s = state.settings;
    const rows = Object.keys(SETTING_LABELS).map((k) => {
        const v = s[k] ?? "";
        const lbl = `<label for="set-${k}">${SETTING_LABELS[k]}</label>`;
        if (k === "routing_mode") {
            return `${lbl}<select id="set-${k}" data-k="${k}">
                <option value="simultaneous" ${v === "simultaneous" ? "selected" : ""}>simultaneous (ring all)</option>
                <option value="sequential" ${v === "sequential" ? "selected" : ""}>sequential (priority order)</option>
            </select>`;
        }
        if (k === "respect_presence" || k === "announce_recording") {
            return `${lbl}<select id="set-${k}" data-k="${k}">
                <option value="true" ${v === "true" ? "selected" : ""}>true</option>
                <option value="false" ${v === "false" ? "selected" : ""}>false</option>
            </select>`;
        }
        if (k.endsWith("_text")) {
            return `${lbl}<textarea id="set-${k}" data-k="${k}" rows="2">${esc(v)}</textarea>`;
        }
        return `${lbl}<input id="set-${k}" data-k="${k}" value="${esc(v)}">`;
    }).join("");
    $("#settings-form").innerHTML = rows +
        `<div class="full"><button type="submit">Save settings</button></div>`;
}

// ---------- actions ----------

async function saveAgent(id, tr) {
    const body = {
        name: tr.querySelector('[data-f="name"]').value.trim(),
        phone_e164: tr.querySelector('[data-f="phone_e164"]').value.trim(),
        priority: Number(tr.querySelector('[data-f="priority"]').value || 0),
        status: tr.querySelector('[data-f="status"]').value,
        active: tr.querySelector('[data-f="active"]').checked,
    };
    await api("PATCH", "/api/agents/" + id, body);
    toast("Agent saved");
    await loadAgents();
    render();
}

async function deleteAgent(id) {
    if (!confirm("Remove this agent from rotation? (history is kept)")) return;
    await api("DELETE", "/api/agents/" + id);
    toast("Agent removed from rotation");
    await loadAgents();
    render();
}

async function saveCallback(id, card) {
    const body = {
        status: card.querySelector('[data-f="cb-status"]').value,
        notes: card.querySelector('[data-f="cb-notes"]').value,
    };
    const agentSel = card.querySelector('[data-f="cb-agent"]');
    if (agentSel && agentSel.value) body.assigned_agent_id = Number(agentSel.value);
    await api("PATCH", "/api/callbacks/" + id, body);
    toast("Callback saved");
    await loadCallbacks();
    render();
}

async function callCallback(id, card) {
    const agentSel = card.querySelector('[data-f="cb-agent"]');
    const body = agentSel && agentSel.value ? { agent_id: Number(agentSel.value) } : {};
    const r = await api("POST", `/api/callbacks/${id}/call`, body);
    toast(`Calling back via ${r.agent ? r.agent.name : "agent"}… (outbound ${r.outbound_call_uuid})`);
    await Promise.all([loadCallbacks(), loadStats(), loadCalls()]);
    render();
}

async function saveSettings() {
    const body = {};
    document.querySelectorAll("#settings-form [data-k]").forEach((el) => { body[el.dataset.k] = el.value; });
    await api("PUT", "/api/settings", body);
    toast("Settings saved");
    await loadSettings();
    render();
}

// ---------- wiring ----------

function showTab(tab) {
    state.tab = tab;
    document.querySelectorAll(".tab").forEach((b) => b.classList.toggle("active", b.dataset.tab === tab));
    document.querySelectorAll("main .panel").forEach((p) => p.classList.add("hidden"));
    $("#tab-" + tab).classList.remove("hidden");
    refresh();
}

document.addEventListener("DOMContentLoaded", () => {
    document.querySelectorAll(".tab").forEach((b) => b.addEventListener("click", () => showTab(b.dataset.tab)));

    document.addEventListener("click", async (e) => {
        const el = e.target.closest("[data-action]");
        if (!el) return;
        const id = el.dataset.id;
        try {
            if (el.dataset.action === "save-agent") await saveAgent(id, el.closest("tr"));
            else if (el.dataset.action === "delete-agent") await deleteAgent(id);
            else if (el.dataset.action === "save-callback") await saveCallback(id, el.closest(".cb-card"));
            else if (el.dataset.action === "call-callback") await callCallback(id, el.closest(".cb-card"));
        } catch (err) {
            toast(err.message, true);
        }
    });

    $("#add-agent").addEventListener("submit", async (e) => {
        e.preventDefault();
        const f = e.target;
        try {
            await api("POST", "/api/agents", {
                name: f.name.value.trim(),
                phone_e164: f.phone_e164.value.trim(),
                priority: Number(f.priority.value || 0),
            });
            toast("Agent added");
            f.reset();
            await loadAgents();
            render();
        } catch (err) {
            toast(err.message, true);
        }
    });

    $("#call-filters").addEventListener("submit", async (e) => {
        e.preventDefault();
        const f = e.target;
        state.callFilters = { status: f.status.value, from: f.from.value, to: f.to.value, q: f.q.value };
        await loadCalls();
        render();
    });

    $("#callback-filters").addEventListener("submit", async (e) => {
        e.preventDefault();
        state.callbackFilter = e.target.status.value;
        await loadCallbacks();
        render();
    });

    $("#settings-form").addEventListener("submit", async (e) => {
        e.preventDefault();
        try {
            await saveSettings();
        } catch (err) {
            toast(err.message, true);
        }
    });

    refresh();
    setInterval(refresh, 5000);
});
