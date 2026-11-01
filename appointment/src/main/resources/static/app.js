/* Appointment Service — ops console. Plain JS, talks to the internal /api/* JSON API. */
"use strict";

const state = {
    tab: "dashboard",
    stats: {},
    aptStats: {},
    agents: [],
    calls: [],
    callbacks: [],
    settings: {},
    callFilters: { status: "", from: "", to: "", q: "" },
    callbackFilter: "",
    appointments: [],
    aptFilters: { status: "", doctor_id: "", from: "", to: "", q: "" },
    doctors: [],
    departments: [],
    patients: [],
    reminders: [],
    reminderFilters: { status: "", kind: "" },
    templates: [],
    schedules: [],
    timeoff: [],
    availability: [],
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
    state.aptStats = await api("GET", "/api/stats/appointments");
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

async function loadAppointments() {
    const p = new URLSearchParams();
    for (const [k, v] of Object.entries(state.aptFilters)) if (v) p.set(k, v);
    const r = await api("GET", "/api/appointments?" + p.toString());
    state.appointments = r.items || [];
}

async function loadDoctors() {
    state.doctors = (await api("GET", "/api/doctors")).items || [];
}

async function loadDepartments() {
    state.departments = (await api("GET", "/api/departments")).items || [];
}

async function loadPatients(q) {
    const p = new URLSearchParams();
    if (q) p.set("q", q);
    const r = await api("GET", "/api/patients?" + p.toString());
    state.patients = r.items || [];
}

async function loadReminders() {
    const p = new URLSearchParams();
    for (const [k, v] of Object.entries(state.reminderFilters)) if (v) p.set(k, v);
    const r = await api("GET", "/api/reminders?" + p.toString());
    state.reminders = r.items || [];
}

async function loadTemplates() {
    state.templates = (await api("GET", "/api/templates")).items || [];
}

async function loadSchedules(doctorId) {
    if (!doctorId) { state.schedules = []; state.timeoff = []; return; }
    state.schedules = (await api("GET", "/api/schedules?doctor_id=" + doctorId)).items || [];
    state.timeoff = (await api("GET", "/api/time-off?doctor_id=" + doctorId)).items || [];
}

async function refresh() {
    try {
        await Promise.all([loadStats(), loadAgents(), loadDepartments(), loadDoctors()]);
        if (state.tab === "dashboard" || state.tab === "calls") await loadCalls();
        if (state.tab === "callbacks") await loadCallbacks();
        if (state.tab === "settings") await loadSettings();
        if (state.tab === "appointments") await loadAppointments();
        if (state.tab === "doctors") {
            const sel = $("#sched-doctor-select");
            await loadSchedules(sel && sel.value ? Number(sel.value) : (state.doctors[0] && state.doctors[0].id));
        }
        if (state.tab === "patients") await loadPatients(state.patientQuery || "");
        if (state.tab === "reminders") await loadReminders();
        if (state.tab === "templates") await loadTemplates();
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
    if (state.tab === "appointments") renderAppointments();
    if (state.tab === "doctors") renderDoctors();
    if (state.tab === "patients") renderPatients();
    if (state.tab === "reminders") renderReminders();
    if (state.tab === "templates") renderTemplates();
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
        <td>${c.reminder_id ? "🔔 #" + esc(c.reminder_id) : "—"}</td>
        <td>${c.recording_url ? `<audio controls src="${esc(c.recording_url)}"></audio>` : "—"}</td>
    </tr>`).join("");
    return `<table><thead><tr>
        <th>time</th><th>dir</th><th>caller</th><th>dialed</th><th>agent</th>
        <th>status</th><th>dur</th><th>cause</th><th>rem</th><th>recording</th>
    </tr></thead><tbody>${rows}</tbody></table>`;
}

function renderDashboard() {
    const s = state.stats;
    const a = state.aptStats;
    $("#stat-cards").innerHTML =
        card(s.calls_live || 0, "live calls") +
        card(s.calls_today || 0, "calls today") +
        card(s.calls_answered_today || 0, "answered today") +
        card(s.callbacks_pending || 0, "pending callbacks") +
        card(s.agents_active || 0, "active agents");
    $("#apt-cards").innerHTML =
        card(a.appointments_total || 0, "appointments") +
        card(a.appointments_today || 0, "today") +
        card(a.appointments_confirmed || 0, "confirmed") +
        card(a.appointments_cancelled || 0, "cancelled") +
        card(a.appointments_ivr || 0, "booked by phone (IVR)") +
        card(a.reminders_pending || 0, "reminders pending") +
        card(a.reminders_confirmed || 0, "reminders confirmed") +
        card(a.reminders_no_answer || 0, "no answer");
    const live = state.calls.filter((c) => c.status === "in_progress");
    $("#live-calls").innerHTML = live.length ? callsTable(live) : `<p class="muted">Nothing ringing right now.</p>`;
    $("#recent-calls").innerHTML = callsTable(state.calls.slice(0, 25));
}

// ---------- appointments tab ----------

function doctorOptions(selected) {
    return state.doctors.map((d) =>
        `<option value="${d.id}" ${String(d.id) === String(selected) ? "selected" : ""}>${esc(d.name)}${d.active ? "" : " (inactive)"}</option>`).join("");
}

function renderAppointments() {
    $("#apt-doctor-select").innerHTML = doctorOptions();
    $("#avail-doctor-select").innerHTML = doctorOptions();
    const fd = $("#apt-filter-doctor");
    if (fd) fd.innerHTML = `<option value="">all doctors</option>` + doctorOptions(state.aptFilters.doctor_id);
    if (!state.appointments.length) {
        $("#appointments-table").innerHTML = `<p class="muted">No appointments match.</p>`;
    } else {
        const rows = state.appointments.map((a) => `<tr>
            <td><b>${esc(a.reference_code || "")}</b></td>
            <td>${esc(a.patient_name || ("#" + a.patient_id))}<br><span class="muted">${esc(a.patient_phone_e164 || "")}</span></td>
            <td>${esc(a.doctor_name || ("#" + a.doctor_id))}</td>
            <td>${esc(fmtTime(a.starts_at))}</td>
            <td>${esc(a.duration_min || "")}m</td>
            <td>${statusBadge(a.status)}${a.needs_rebooking ? ' <span class="status booked">needs rebooking</span>' : ""}</td>
            <td>${esc(a.source || "")}</td>
            <td>${esc(a.reason || "")}</td>
            <td>
                ${a.status === "booked" || a.status === "confirmed" || a.status === "rescheduled" ? `
                <button data-action="apt-complete" data-id="${a.id}">complete</button>
                <button data-action="apt-noshow" data-id="${a.id}">no-show</button>
                <button data-action="apt-reschedule" data-id="${a.id}">reschedule</button>
                <button class="danger" data-action="apt-cancel" data-id="${a.id}">cancel</button>` : "—"}
            </td>
        </tr>`).join("");
        $("#appointments-table").innerHTML = `<table><thead><tr>
            <th>ref</th><th>patient</th><th>doctor</th><th>starts</th><th>dur</th><th>status</th><th>source</th><th>reason</th><th></th>
        </tr></thead><tbody>${rows}</tbody></table>`;
    }
    if (state.availability.length) {
        $("#availability-list").innerHTML =
            `<p class="muted">${state.availability.length} free slot(s):</p><p>` +
            state.availability.map((s) => `<span class="status available" style="margin:2px">${esc(s.label)}</span>`).join(" ") +
            `</p>`;
    } else {
        $("#availability-list").innerHTML = "";
    }
}

// ---------- doctors tab ----------

function renderDoctors() {
    $("#departments-list").innerHTML = state.departments.length
        ? `<p>${state.departments.map((d) => `<span class="status available" style="margin:2px">${esc(d.name)}</span>`).join(" ")}</p>`
        : `<p class="muted">No departments yet.</p>`;
    $("#doctor-dept-select").innerHTML =
        `<option value="">no department</option>` +
        state.departments.map((d) => `<option value="${d.id}">${esc(d.name)}</option>`).join("");
    $("#doctor-agent-select").innerHTML =
        `<option value="">no linked agent</option>` +
        state.agents.map((a) => `<option value="${a.id}">${esc(a.name)}</option>`).join("");
    const schedSel = $("#sched-doctor-select");
    const timeoffSel = $("#timeoff-doctor-select");
    const keep = schedSel && schedSel.value;
    const opts = doctorOptions(keep);
    if (schedSel) schedSel.innerHTML = opts;
    if (timeoffSel) timeoffSel.innerHTML = opts;

    const rows = state.doctors.map((d) => `<tr data-id="${d.id}">
        <td><input data-f="name" value="${esc(d.name)}"></td>
        <td><input data-f="specialization" value="${esc(d.specialization || "")}"></td>
        <td><select data-f="department_id">
            <option value="">—</option>
            ${state.departments.map((dep) => `<option value="${dep.id}" ${String(dep.id) === String(d.department_id) ? "selected" : ""}>${esc(dep.name)}</option>`).join("")}
        </select></td>
        <td><input data-f="slot_minutes" type="number" value="${d.slot_minutes}" style="width:70px"></td>
        <td><input data-f="active" type="checkbox" ${d.active ? "checked" : ""}></td>
        <td>
            <button data-action="save-doctor" data-id="${d.id}">Save</button>
            <button class="danger" data-action="delete-doctor" data-id="${d.id}">Deactivate</button>
        </td>
    </tr>`).join("");
    $("#doctors-table").innerHTML = state.doctors.length
        ? `<table><thead><tr><th>name</th><th>specialization</th><th>department</th><th>slot min</th><th>active</th><th></th></tr></thead><tbody>${rows}</tbody></table>`
        : `<p class="muted">No doctors yet.</p>`;

    const dow = ["Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat"];
    $("#schedules-table").innerHTML = state.schedules.length
        ? `<table><thead><tr><th>day</th><th>from</th><th>to</th><th>slot</th><th></th></tr></thead><tbody>` +
          state.schedules.map((s) => `<tr>
              <td>${dow[s.day_of_week]}</td><td>${esc(s.start_time)}</td><td>${esc(s.end_time)}</td>
              <td>${s.slot_minutes || "default"}m</td>
              <td><button class="danger" data-action="del-schedule" data-id="${s.id}">remove</button></td>
          </tr>`).join("") + `</tbody></table>`
        : `<p class="muted">Pick a doctor to manage its schedules.</p>`;
    $("#timeoff-table").innerHTML = state.timeoff.length
        ? `<table><thead><tr><th>from</th><th>to</th><th>reason</th><th></th></tr></thead><tbody>` +
          state.timeoff.map((t) => `<tr>
              <td>${esc(fmtTime(t.from_ts))}</td><td>${esc(fmtTime(t.to_ts))}</td><td>${esc(t.reason || "")}</td>
              <td><button class="danger" data-action="del-timeoff" data-id="${t.id}">remove</button></td>
          </tr>`).join("") + `</tbody></table>`
        : `<p class="muted">No time-off blocks.</p>`;
}

// ---------- patients tab ----------

function renderPatients() {
    const rows = state.patients.map((p) => `<tr data-id="${p.id}">
        <td>${esc(p.name)}</td>
        <td><input data-f="phone_e164" value="${esc(p.phone_e164)}"></td>
        <td><select data-f="preferred_language">
            ${["en", "hi", "bn"].map((l) => `<option ${l === p.preferred_language ? "selected" : ""}>${l}</option>`).join("")}
        </select></td>
        <td><input data-f="dnd" type="checkbox" ${p.dnd ? "checked" : ""} title="do not disturb — suppress reminders"></td>
        <td><input data-f="active" type="checkbox" ${p.active ? "checked" : ""}></td>
        <td><button data-action="save-patient" data-id="${p.id}">Save</button></td>
    </tr>`).join("");
    $("#patients-table").innerHTML = state.patients.length
        ? `<table><thead><tr><th>name</th><th>phone (E.164)</th><th>lang</th><th>DND</th><th>active</th><th></th></tr></thead><tbody>${rows}</tbody></table>`
        : `<p class="muted">No patients match.</p>`;
}

// ---------- reminders tab ----------

function renderReminders() {
    if (!state.reminders.length) {
        $("#reminders-table").innerHTML = `<p class="muted">No reminders match.</p>`;
        return;
    }
    const rows = state.reminders.map((r) => `<tr>
        <td>#${r.id}</td>
        <td>${esc(r.appointment_ref || ("apt #" + r.appointment_id))}</td>
        <td>${esc(r.kind)}</td>
        <td>${esc(fmtTime(r.scheduled_at))}</td>
        <td>${statusBadge(r.status)}</td>
        <td>${r.attempts}</td>
        <td>${esc(r.response_digit || "—")}</td>
        <td>${esc(r.error_text || "")}</td>
        <td>
            ${r.status === "pending" ? `
            <button data-action="rem-call" data-id="${r.id}">📞 call now</button>
            <button class="danger" data-action="rem-suppress" data-id="${r.id}">suppress</button>` : "—"}
        </td>
    </tr>`).join("");
    $("#reminders-table").innerHTML = `<table><thead><tr>
        <th>id</th><th>appointment</th><th>kind</th><th>scheduled</th><th>status</th><th>tries</th><th>digit</th><th>error</th><th></th>
    </tr></thead><tbody>${rows}</tbody></table>`;
}

// ---------- templates tab ----------

function renderTemplates() {
    const rows = state.templates.map((t) => `<tr data-id="${t.id}">
        <td><b>${esc(t.key)}</b><br><span class="muted">${esc(t.language)}</span></td>
        <td><textarea data-f="text" rows="2" style="width:100%">${esc(t.text)}</textarea></td>
        <td><input data-f="active" type="checkbox" ${t.active ? "checked" : ""}></td>
        <td><button data-action="save-template" data-id="${t.id}">Save</button></td>
    </tr>`).join("");
    $("#templates-table").innerHTML = state.templates.length
        ? `<table><thead><tr><th>key</th><th>text (TTS)</th><th>active</th><th></th></tr></thead><tbody>${rows}</tbody></table>`
        : `<p class="muted">No templates.</p>`;
}

// ---------- agents / callbacks / settings (unchanged behaviour) ----------

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
    ivr_enabled: "IVR self-service enabled (false = classic masking only)",
    ivr_max_hops: "IVR max menu hops",
    booking_horizon_days: "Booking horizon (days)",
    min_booking_lead_minutes: "Min lead time to book (min)",
    max_slots_offered: "Slots offered per IVR pick",
    slot_granularity_min: "Slot granularity (min)",
    clinic_timezone: "Clinic timezone (IANA)",
    clinic_name: "Clinic name (spoken in prompts)",
    allow_self_booking: "Allow self-booking by phone",
    allow_self_cancel_hours: "Self-cancel cutoff (hours before)",
    reminder_t24h_enabled: "T-24h reminder enabled",
    reminder_t24h_lead_minutes: "T-24h lead (min)",
    reminder_t2h_enabled: "T-2h reminder enabled",
    reminder_t2h_lead_minutes: "T-2h lead (min)",
    reminder_retry_count: "Reminder retry count",
    reminder_retry_gap_minutes: "Reminder retry gap (min)",
    reminder_quiet_start: "Quiet hours start (HH:MM, clinic tz)",
    reminder_quiet_end: "Quiet hours end (HH:MM, clinic tz)",
    reminder_scan_ms: "Reminder scan interval (ms)",
    sms_fallback_enabled: "SMS fallback enabled (seam only)",
    change_notification_enabled: "Notify on staff-side changes",
};

const BOOL_KEYS = new Set([
    "respect_presence", "announce_recording", "ivr_enabled", "allow_self_booking",
    "reminder_t24h_enabled", "reminder_t2h_enabled", "sms_fallback_enabled", "change_notification_enabled",
]);

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
        if (BOOL_KEYS.has(k)) {
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

// ----- appointment actions -----

async function bookAppointment(f) {
    const body = {
        patient_phone_e164: f.patient_phone_e164.value.trim(),
        doctor_id: Number(f.doctor_id.value),
        starts_at: new Date(f.starts_at.value).toISOString(),
        reason: f.reason.value.trim() || null,
        source: f.source.value,
    };
    const a = await api("POST", "/api/appointments", body);
    toast(`Booked ${a.reference_code} — ${fmtTime(a.starts_at)}`);
    f.reset();
    await loadAppointments();
    render();
}

async function showAvailability(f) {
    const p = new URLSearchParams({ doctor_id: f.doctor_id.value });
    if (f.date.value) p.set("date", f.date.value);
    const r = await api("GET", "/api/availability?" + p.toString());
    state.availability = r.items || [];
    renderAppointments();
}

async function aptAction(id, action) {
    if (action === "apt-cancel") {
        const reason = prompt("Cancellation reason (optional):") || "";
        await api("POST", `/api/appointments/${id}/cancel`, { reason });
        toast("Appointment cancelled");
    } else if (action === "apt-reschedule") {
        const v = prompt("New start time (YYYY-MM-DDTHH:MM, clinic local time):");
        if (!v) return;
        await api("POST", `/api/appointments/${id}/reschedule`, { starts_at: new Date(v).toISOString() });
        toast("Appointment rescheduled");
    } else if (action === "apt-complete") {
        await api("POST", `/api/appointments/${id}/complete`, {});
        toast("Marked completed");
    } else if (action === "apt-noshow") {
        await api("POST", `/api/appointments/${id}/no-show`, {});
        toast("Marked no-show");
    }
    await loadAppointments();
    render();
}

// ----- doctor/patient/reminder/template actions -----

async function saveDoctor(id, tr) {
    const body = {
        name: tr.querySelector('[data-f="name"]').value.trim(),
        specialization: tr.querySelector('[data-f="specialization"]').value.trim(),
        department_id: tr.querySelector('[data-f="department_id"]').value || null,
        slot_minutes: Number(tr.querySelector('[data-f="slot_minutes"]').value || 15),
        active: tr.querySelector('[data-f="active"]').checked,
    };
    await api("PATCH", "/api/doctors/" + id, body);
    toast("Doctor saved");
    await loadDoctors();
    render();
}

async function savePatient(id, tr) {
    const body = {
        phone_e164: tr.querySelector('[data-f="phone_e164"]').value.trim(),
        preferred_language: tr.querySelector('[data-f="preferred_language"]').value,
        dnd: tr.querySelector('[data-f="dnd"]').checked,
        active: tr.querySelector('[data-f="active"]').checked,
    };
    await api("PATCH", "/api/patients/" + id, body);
    toast("Patient saved");
    await loadPatients(state.patientQuery || "");
    render();
}

async function saveTemplate(id, tr) {
    const body = {
        text: tr.querySelector('[data-f="text"]').value,
        active: tr.querySelector('[data-f="active"]').checked,
    };
    await api("PUT", "/api/templates/" + id, body);
    toast("Prompt saved");
    await loadTemplates();
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
            else if (el.dataset.action.startsWith("apt-")) await aptAction(id, el.dataset.action);
            else if (el.dataset.action === "save-doctor") await saveDoctor(id, el.closest("tr"));
            else if (el.dataset.action === "delete-doctor") { await api("DELETE", "/api/doctors/" + id); toast("Doctor deactivated"); await loadDoctors(); render(); }
            else if (el.dataset.action === "del-schedule") { await api("DELETE", "/api/schedules/" + id); toast("Schedule removed"); await refresh(); }
            else if (el.dataset.action === "del-timeoff") { await api("DELETE", "/api/time-off/" + id); toast("Time-off removed (affected appointments flagged needs-rebooking)"); await refresh(); }
            else if (el.dataset.action === "save-patient") await savePatient(id, el.closest("tr"));
            else if (el.dataset.action === "rem-call") { await api("POST", `/api/reminders/${id}/call`, {}); toast("Reminder call placed via M1"); await loadReminders(); render(); }
            else if (el.dataset.action === "rem-suppress") { await api("POST", `/api/reminders/${id}/suppress`, {}); toast("Reminder suppressed"); await loadReminders(); render(); }
            else if (el.dataset.action === "save-template") await saveTemplate(id, el.closest("tr"));
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
        } catch (err) { toast(err.message, true); }
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
        try { await saveSettings(); } catch (err) { toast(err.message, true); }
    });

    $("#add-appointment").addEventListener("submit", async (e) => {
        e.preventDefault();
        try { await bookAppointment(e.target); } catch (err) { toast(err.message, true); }
    });

    $("#availability-form").addEventListener("submit", async (e) => {
        e.preventDefault();
        try { await showAvailability(e.target); } catch (err) { toast(err.message, true); }
    });

    $("#apt-filters").addEventListener("submit", async (e) => {
        e.preventDefault();
        const f = e.target;
        state.aptFilters = { status: f.status.value, doctor_id: f.doctor_id.value, from: f.from.value, to: f.to.value, q: f.q.value };
        await loadAppointments();
        render();
    });

    $("#add-doctor").addEventListener("submit", async (e) => {
        e.preventDefault();
        const f = e.target;
        try {
            await api("POST", "/api/doctors", {
                name: f.name.value.trim(),
                specialization: f.specialization.value.trim() || null,
                department_id: f.department_id.value || null,
                slot_minutes: Number(f.slot_minutes.value || 15),
                agent_id: f.agent_id.value || null,
            });
            toast("Doctor added");
            f.reset();
            await loadDoctors();
            render();
        } catch (err) { toast(err.message, true); }
    });

    $("#add-department").addEventListener("submit", async (e) => {
        e.preventDefault();
        try {
            await api("POST", "/api/departments", { name: e.target.name.value.trim() });
            toast("Department added");
            e.target.reset();
            await loadDepartments();
            render();
        } catch (err) { toast(err.message, true); }
    });

    $("#add-schedule").addEventListener("submit", async (e) => {
        e.preventDefault();
        const f = e.target;
        try {
            await api("POST", "/api/schedules", {
                doctor_id: Number(f.doctor_id.value),
                day_of_week: Number(f.day_of_week.value),
                start_time: f.start_time.value,
                end_time: f.end_time.value,
                slot_minutes: Number(f.slot_minutes.value || 0),
            });
            toast("Schedule added");
            await refresh();
        } catch (err) { toast(err.message, true); }
    });

    $("#add-timeoff").addEventListener("submit", async (e) => {
        e.preventDefault();
        const f = e.target;
        try {
            await api("POST", "/api/time-off", {
                doctor_id: Number(f.doctor_id.value),
                from_ts: new Date(f.from_ts.value).toISOString(),
                to_ts: new Date(f.to_ts.value).toISOString(),
                reason: f.reason.value.trim() || null,
            });
            toast("Time off blocked — overlapping appointments flagged needs-rebooking and change reminders queued");
            f.reset();
            await refresh();
        } catch (err) { toast(err.message, true); }
    });

    $("#sched-doctor-select").addEventListener("change", async (e) => {
        await loadSchedules(Number(e.target.value));
        renderDoctors();
    });

    $("#add-patient").addEventListener("submit", async (e) => {
        e.preventDefault();
        const f = e.target;
        try {
            await api("POST", "/api/patients", {
                name: f.name.value.trim(),
                phone_e164: f.phone_e164.value.trim(),
                preferred_language: f.preferred_language.value,
            });
            toast("Patient added");
            f.reset();
            await loadPatients(state.patientQuery || "");
            render();
        } catch (err) { toast(err.message, true); }
    });

    $("#patient-search").addEventListener("submit", async (e) => {
        e.preventDefault();
        state.patientQuery = e.target.q.value;
        await loadPatients(state.patientQuery);
        render();
    });

    $("#reminder-filters").addEventListener("submit", async (e) => {
        e.preventDefault();
        const f = e.target;
        state.reminderFilters = { status: f.status.value, kind: f.kind.value };
        await loadReminders();
        render();
    });

    $("#run-reminders").addEventListener("click", async () => {
        try {
            await api("POST", "/api/reminders/run", {});
            toast("Due reminders scanned and placed");
            await loadReminders();
            render();
        } catch (err) { toast(err.message, true); }
    });

    refresh();
    setInterval(refresh, 5000);
});
