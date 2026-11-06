# Call-First Appointment System (`appointment`) — built on the Vobiz call-masking service

A small layer of software on top of **Vobiz** that implements the plan in
[`../Vobiz-Call-Masking-Plan.md`](../Vobiz-Call-Masking-Plan.md):

- You own **one Vobiz DID, M1** — the only number the outside world ever sees.
- You register **two or more real agent numbers** (N2, N3, …) in the ops console; both stay hidden from callers.
- Every call to **M1** is bridged to an available agent — both handsets display **M1** (`callerId="M1"`).
- Every call is **logged** (caller, time, agent, duration, outcome) in PostgreSQL.
- If **nobody answers**, the caller hears a message and can leave a **15–30 s voicemail**, which lands in the **callback queue**.
- Callbacks are **system-initiated click-to-call**: the backend dials the agent first via the Vobiz outbound Call API, then bridges to the customer with `callerId=M1` — the agent never sees the customer's number.

**Stack:** Java 25 (LTS) · Spring Boot 4.1.x (`spring-boot-starter-webmvc`) · Gradle 9.8 · PostgreSQL 17 · Lombok · Log4j2.
No SIP trunks, no media handling — Vobiz is the pipe; this service only returns XML from webhooks.

```
Caller ──dials M1──▶ Vobiz ──POST /voice/answer {From,To,CallUUID}──▶ this service
                       │                                                   │
                       │                            1. log the call (calls table)
                       │                            2. pick eligible agents
                       │                            3. return <Response> XML
                       │◀────────────── XML ─────────────────────────────┘
                       ├── <Dial callerId="M1"> N2 + N3 </Dial>   (simultaneous, first answer wins)
                       │     ├─ answered ──▶ bridged; both sides see M1
                       │     └─ no answer ─▶ <Speak> + <Record maxLength=30> in the same document
                       │                          └─ POST /voice/recording-done ─▶ callback_requests row
                       └── POST /voice/hangup ─▶ duration + cause (logging only)
```

## Project layout

```
appointment/
├── build.gradle.kts / settings.gradle.kts / gradlew   # Gradle 9.8, Java 25 toolchain, Boot 4.1.2
├── Dockerfile                                          # multi-stage JDK 25 → JRE 25, non-root, healthcheck
├── docker-compose.yml                                  # PostgreSQL 17 + the service
├── scripts/simulate-call.sh                            # end-to-end demo of the webhook flows (curl)
└── src/main/
    ├── java/com/vobiz/masking/
    │   ├── MaskingApplication.java                     # @SpringBootApplication
    │   ├── config/            AppProperties, VobizProperties, AppBeans (composition root),
    │   │                      DataSeeder (settings + templates, demo rows only with APP_SEED_DEMO_DATA),
    │   │                      ConfigValidator (fails startup on placeholder production config)
    │   ├── web/               VoiceWebhookController, ApiController (thin Spring adapters)
    │   │                      Handlers, HttpResult (ALL routing logic — framework-free, unit-tested)
    │   ├── service/           CallFlowService (voice webhooks), RoutingService, AgentService,
    │   │                      CallbackService (click-to-call), SettingsService
    │   ├── repo/              AgentRepository, CallRepository, CallbackRepository, SettingRepository
    │   │                      (Spring Data JPA) + AgentDao/CallDao/CallbackDao/SettingsDao adapters
    │   │                      (JPA for single-row CRUD, JdbcTemplate for native log/stats queries)
    │   ├── model/             Agent, CallRecord, CallbackRequest, Setting — JPA @Entity classes
    │   │                      (Lombok); Hibernate creates/updates the tables from them
    │   ├── xml/VobizXml.java  # builds the <Response> XML (Dial/Speak/Record/Hangup)
    │   ├── vobiz/             VobizClient (voice) and VobizWhatsAppClient (Messaging API)
    │   ├── security/          AuthFilter (API/staff access + signed Vobiz callbacks), WebhookSigner
    │   ├── whatsapp/          shared inbox, provider-neutral service, Vobiz webhook controller, staff access
    │   └── util/              Json (parser/writer), Forms (urlencoded), ApiException
    └── resources/
        ├── application.properties                      # env-driven config; DDL_AUTO (default update)
        ├── log4j2-spring.xml                           # Log4j2 console logging, level via LOG_LEVEL
        ├── db/migration/                               # Flyway baseline landing zone (see "Schema and migrations")
        └── static/                                     # ops console + whatsapp.html/css/js shared inbox
```

Design note: the call/appointment dispatcher is framework-free and unit-tested without Spring; the
WhatsApp endpoints are thin Spring adapters over a shared inbox service and Vobiz Messaging API client,
provided through dependency injection. The application does not call Meta APIs directly. The data layer is
JPA/JdbcTemplate + PostgreSQL, with Log4j2 and Lombok. There is **no `schema.sql`**: Hibernate creates
and updates the tables from the entities on boot, and seed rows come from `DataSeeder`. For production deployments using `DDL_AUTO=validate`, include the
WhatsApp tables in the schema migration before releasing this feature; Hibernate's default `update`
mode creates them automatically for local/testing setups.

## Prerequisites

- **JDK 25** (any Temurin/Oracle/etc. build). Gradle will auto-download a 25 toolchain via the
  Foojay resolver if you don't have one (needs internet once).
- **Docker** (for PostgreSQL) — or any reachable PostgreSQL ≥ 14.

## Quick start

```bash
cd appointment

# 1. start PostgreSQL (creates db appointment, user/pass postgres/postgres)
docker compose up -d

# 2. configure (optional for a first local run — defaults work, Vobiz calls are mocked)
cp .env.example .env   # then edit: PUBLIC_BASE_URL, M1_NUMBER, API_TOKEN, WEBHOOK_SECRET, VOBIZ_*
#   or just export env vars; see .env.example. The console prompts for API_TOKEN on first load.
#   Entire stack in containers instead:  docker compose up -d --build

# 3. build & run (Java 25 toolchain, Spring Boot 4.1.2)
./gradlew bootRun
#   production jar: ./gradlew bootJar && java -jar build/libs/appointment-service.jar

# 4. open the ops console and shared WhatsApp inbox
open http://localhost:8080/                 # Dashboard / Appointments / Agents / Call log / Settings
open http://localhost:8080/whatsapp.html    # Shared team inbox

# 5. exercise the whole call flow without a real phone (mock mode logs the outbound call)
./scripts/simulate-call.sh
```

## Configuration (environment variables)

| Var | Default | Meaning |
|---|---|---|
| `PORT` | `8080` | HTTP port |
| `DB_URL` | `jdbc:postgresql://localhost:5432/appointment` | Postgres JDBC URL |
| `DB_USER` / `DB_PASSWORD` | `postgres` / `postgres` | DB credentials |
| `PUBLIC_BASE_URL` | `http://localhost:8080` | Public base URL of this service — **must be HTTPS and reachable by Vobiz in production** (use a tunnel for first tests). All webhook URLs are built from it. |
| `M1_NUMBER` | `+10000000000` | Fallback masked DID; editable later in Settings |
| `VOBIZ_AUTH_ID` / `VOBIZ_AUTH_TOKEN` | empty | Vobiz REST credentials for voice and messaging. Sent as `X-Auth-ID` / `X-Auth-Token`; never returned to the browser. |
| `VOBIZ_API_BASE` | `https://api.vobiz.ai` | Vobiz REST API base URL |
| `VOBIZ_WHATSAPP_CHANNEL_ID` | empty | Channel ID for the WhatsApp channel configured in the Vobiz console |
| `VOBIZ_WHATSAPP_WABA_ID` | empty | WhatsApp Business Account ID associated with that Vobiz channel |
| `VOBIZ_WHATSAPP_NUMBER` | empty | E.164 number attached to the Vobiz channel; must exactly match the clinic's current M1 |
| `VOBIZ_WHATSAPP_WEBHOOK_SECRET` | empty | Shared secret checked on Vobiz inbound/status callbacks |
| `VOBIZ_MOCK` | `true` | `true` = log demo voice/messages without sending traffic to Vobiz |
| `API_TOKEN` | empty | Bearer token for the main `/api/**` API. **Set it before exposing the service.** WhatsApp staff tokens are limited to `/api/whatsapp/**`. |
| `WEBHOOK_SECRET` | empty | Shared secret for `/voice/**`; this service appends `?secret=…` to every URL it hands to Vobiz, and `AuthFilter` rejects unsigned webhooks. **Set it before exposing the service.** |
| `APP_REQUIRE_PRODUCTION_CONFIG` | `false` | `true` = refuse to start while the config still looks local (localhost base URL, placeholder M1, empty tokens…). Also implied once `VOBIZ_MOCK=false` with real credentials. |
| `APP_SEED_DEMO_DATA` | `false` | Seed two fake agents, a demo doctor/schedule and a demo patient. **Never enable against a live database.** |
| `LOG_LEVEL` | `info` | Log4j2 level for `com.vobiz.masking` (`debug` locally). |
| `REMINDER_SCHEDULER_ENABLED` | `true` | Turns the reminder scan on/off (the interval is the `reminder_scan_ms` *setting*). |

## Setting up Vobiz

1. Buy/assign **one DID (M1)** on your Vobiz account (KYC/eKYC as required).
2. Create a **Vobiz Application** with:
   - **Answer URL**: `https://<your-host>/voice/answer` (POST)
   - **Hangup URL**: `https://<your-host>/voice/hangup` (POST)
   - Fallback URL: `https://<your-host>/voice/answer`

   With `WEBHOOK_SECRET` set, append it to the two URLs you type into Vobiz —
   `https://<your-host>/voice/answer?secret=…` and `…/voice/hangup?secret=…` — otherwise `AuthFilter`
   answers 401 and the call is dropped. Every URL the service generates for Vobiz (Dial actions,
   Gather actions, Record callbacks, reminder legs) is signed automatically by `WebhookSigner`.
3. Attach **M1** to that application.
4. The Dial action (`/voice/dial-result`, or `/voice/dial-step` in sequential mode) and the
   Record callback (`/voice/recording-done`) are generated automatically from `PUBLIC_BASE_URL`.
5. For click-to-call, ensure the account is allowed to place **outbound calls with M1 as caller ID**
   (an unauthorised caller ID fails with hangup code 3030), then set `VOBIZ_AUTH_ID`,
   `VOBIZ_AUTH_TOKEN`, `VOBIZ_MOCK=false`.

Operational constraints: the Answer URL must respond in **1–2 s** (agent list is read from Postgres
per call — keep it small, or cache it later); XML responses must be valid and < 100 KB; Vobiz holds
**no state between webhooks**, so every handler works from `CallUUID` alone.

## Shared WhatsApp inbox (Vobiz Messaging API, same clinic number)

The console at **`/whatsapp.html`** uses Vobiz's WhatsApp Messaging API. The application calls Vobiz only;
it does not call Meta endpoints or hold Meta API credentials. The Vobiz transport is a Spring dependency
injected adapter, and inbound messages/statuses are persisted in the shared PostgreSQL inbox for every
staff account granted `can_read` access.

### Connect the clinic DID in Vobiz

A voice DID is not automatically enabled as a WhatsApp channel. In the Vobiz console, connect the clinic's
existing M1 using Vobiz's supported BYON/WhatsApp channel flow, and confirm that this Vobiz channel is linked
to the same number used by the clinic. Vobiz's WhatsApp onboarding may require business-account verification;
complete it in Vobiz rather than configuring a separate Meta API client in this app. Copy the channel ID and
WABA ID shown by Vobiz. Set the channel's inbound **Message/Webhook URL** to the callback below.

Configure these server environment variables (and pass them through Docker Compose):

```dotenv
VOBIZ_AUTH_ID=<Vobiz account auth ID>
VOBIZ_AUTH_TOKEN=<Vobiz account auth token>
VOBIZ_API_BASE=https://api.vobiz.ai
VOBIZ_WHATSAPP_CHANNEL_ID=<channel ID from Vobiz>
VOBIZ_WHATSAPP_WABA_ID=<WABA ID from Vobiz>
VOBIZ_WHATSAPP_NUMBER=+919876543210  # must equal the current M1_NUMBER / Settings value
VOBIZ_WHATSAPP_WEBHOOK_SECRET=<long random callback secret>
VOBIZ_MOCK=false
```

Outbound text uses Vobiz `POST /api/v1/messaging/messages` with `X-Auth-ID` and `X-Auth-Token`, plus
`channel_id`, `waba_id`, `to`, `type: text`, and `text.body`. The server refuses live sends unless the
configured Vobiz channel number exactly matches the clinic's current M1.

Configure Vobiz's WhatsApp Message URL as
**`https://<your-host>/webhooks/vobiz/whatsapp?secret=<VOBIZ_WHATSAPP_WEBHOOK_SECRET>`** (HTTPS required).
The callback checks that shared secret and provisionally handles Vobiz `message.inbound` and message-status
events for the configured channel; it also checks the WABA ID when the callback includes one. The excerpts
supplied so far identify event names but not the payload envelope/field schema or signature header/algorithm.
Verify both against Vobiz's Webhooks page before exposing the callback publicly. Do not treat this query secret
as a substitute if your Vobiz account requires signed callbacks.

### Staff access and messaging behavior

1. Open `/whatsapp.html` and sign in with the server `API_TOKEN` as administrator.
2. In **Manage staff access**, create an individual token for each agent or compounder. The token is shown
   once and stored only as a SHA-256 hash. Admins can revoke or rotate tokens; staff tokens are restricted to
   WhatsApp inbox routes and cannot read appointments, patient records, settings, or call logs.
3. Every active staff account with `can_read` sees the same inbound/outbound history. `can_send` controls
   replies; opening a thread marks it read for the whole team. Authorized staff can start a thread by E.164.

Text send and inbound message/status normalization are included. The Vobiz docs excerpt supplied so far does
not include media-download or template-send endpoint details, so this version displays attachment metadata but
does not fetch attachment bytes or send templates. WhatsApp's customer-service window and template rules still
apply; creating a new thread does not bypass them.

For local UI testing, `VOBIZ_MOCK=true` stores demo outbound rows but **does not deliver messages**. Webhook
callbacks require `VOBIZ_WHATSAPP_WEBHOOK_SECRET`. Keep Vobiz credentials, staff tokens, webhook secrets, and
patient message history private; use HTTPS and grant only the inbox access each staff member needs.

## API reference

Voice webhooks (called by Vobiz, form-encoded, return `text/xml`):

| Endpoint | Purpose |
|---|---|
| `POST /voice/answer` | Inbound call to M1 → log + routing XML |
| `POST /voice/dial-result` | Outcome of the simultaneous Dial (answered / no_answer) |
| `POST /voice/dial-step?call_uuid&step` | Sequential chaining: returns next Dial or voicemail XML |
| `POST /voice/recording-done` | Record callback → creates callback request |
| `POST /voice/hangup` | Final duration/cause; closes outbound callback legs + reminder legs |
| `GET /voice/outbound-answer?to&callback_id` | Agent answered the outbound leg → bridge to customer |
| `POST /voice/ivr/{menu,department,doctor,slot,confirm,reschedule,cancel,no-input,human}` | Keypad IVR steps for booking (gather actions; press **0** anywhere → masked bridge) |
| `GET /voice/reminder-answer?reminder_id&appointment_id&call_uuid` | Outbound reminder call answered → reminder prompt + keypad gather |
| `POST /voice/reminder-response?reminder_id&call_uuid` | Reminder keypad: **1** confirm, **2** reschedule (→ reception), **3** cancel |

Internal REST API (used by the ops console, JSON):

| Endpoint | Purpose |
|---|---|
| `GET /health`, `GET /api/health` | liveness |
| `GET /api/stats` | dashboard counters |
| `GET/POST /api/agents`, `GET/PATCH/DELETE /api/agents/{id}` | register/edit/deactivate agent numbers (E.164 validated) |
| `GET /api/calls?status&from&to&q&limit`, `GET /api/calls/{id}` | call log |
| `GET /api/callbacks?status`, `GET /api/callbacks/{id}` | callback queue |
| `PATCH /api/callbacks/{id}` | status / notes / assigned agent |
| `POST /api/callbacks/{id}/call` `{agent_id?}` | click-to-call (outbound via Vobiz, `callerId=M1`) |
| `GET/PUT /api/settings` | M1, ring timeout, voicemail length, routing mode, texts… |
| `GET /api/whatsapp/me` | Current staff grants and safe integration status (never returns provider secrets) |
| `GET/POST /api/whatsapp/conversations` | List/search shared threads, or start a thread by E.164 number |
| `GET /api/whatsapp/conversations/{id}/messages` | Shared message history (supports `before_id` and `limit`) |
| `POST /api/whatsapp/conversations/{id}/messages` | Send a text reply through the injected Vobiz Messaging API adapter |
| `POST /api/whatsapp/conversations/{id}/read` | Mark the shared thread read for the whole team |
| `GET/POST/PATCH /api/whatsapp/staff-access` | Admin-only provision/list/update personal agent or compounder access tokens |
| `POST /api/whatsapp/staff-access/{id}/rotate` | Admin-only immediate token rotation; clear-text token returned once |
| `POST /webhooks/vobiz/whatsapp` | Vobiz WhatsApp message/status callback; configured with `VOBIZ_WHATSAPP_WEBHOOK_SECRET` |

General `/api/**` routes require `API_TOKEN`. Only `/api/whatsapp/**` also accepts individual WhatsApp staff
bearer tokens, with read/send/admin permissions checked by the controller. Vobiz messaging callbacks use
their own configured shared secret; `/voice/**` keeps the separate `WEBHOOK_SECRET`.

## Call-first appointments (IVR on M1)

With `ivr_enabled=true` (default), `POST /voice/answer` opens a keypad IVR instead of dialling
agents right away:

```
caller ──dials M1──▶ /voice/answer ──▶ <Gather> main menu   press 1 book · 2 reschedule · 3 cancel · 0 reception
                        │ book: 1 → department → 2 → doctor → 3 → slot → 4 → confirm (1 = book) ──▶ booked, ref APT-XXXX
                        │ reschedule: 2 → pick appointment → new slot → confirm ──▶ rescheduled (same ref)
                        │ cancel: 3 → pick appointment → confirm 1 ──▶ cancelled (slot freed)
                        │ 0 / new caller / stuck twice ──▶ classic masked bridge <Dial callerId="M1"> + voicemail fallback
```

- **Known callers** (caller-ID matches `patients.phone_e164`) are greeted by name; unknown callers are
  registered on the fly (`registerUnknownCaller`) so they can still book.
- **Double-booking is impossible**: `appointments.slot_key = "{doctor_id}|{starts_at}"` has a UNIQUE
  constraint; cancelling clears the key so the slot becomes bookable again.
- **Reference codes** are `APT-` + 4 chars from an unambiguous alphabet (no 0/O, 1/I/L).
- **Masking is preserved end-to-end**: the IVR never dials the patient's real number; reminders and
  callbacks go out with `callerId=M1`. Staff see real numbers only in this internal console.
- **Reminder calls** are placed by `ReminderScheduler` (scan every `reminder_scan_ms`) as outbound
  Vobiz calls to the patient with `callerId=M1`; the answer webhook plays the reminder and gathers
  1/2/3. Quiet hours (`reminder_quiet_start/end`, clinic tz) roll reminder times into the allowed
  window; DND patients are suppressed; no-answer retries up to `reminder_retry_count`.
- **Staff-side changes notify the patient**: reschedule/cancel by staff, or a doctor time-off block
  overlapping existing appointments (`needs_rebooking=true`), enqueue a `change` reminder call.
- `appointment.source` ∈ `ivr_call / agent / reminder_ivr / walk_in / web`.
- Set `ivr_enabled=false` → `/voice/answer` returns exactly the classic masking XML.

## Routing modes

- **simultaneous** (default): `<Dial>` with all eligible agents as `<Number>` children — they ring
  at once, first to answer wins. With `redirect="false"`, the voicemail fallback after the Dial runs
  automatically when nobody answers.
- **sequential**: agents are rung one by one in `priority` order via chained `/voice/dial-step` calls;
  the agent who answered is recorded precisely.
- Set **respect_presence=true** to ring only agents marked `available` in the console (Option B
  presence). Agents can also be marked busy/offline manually.

## Security model

Both halves of the HTTP surface are locked down by `security/AuthFilter`:

| Surface | Requirement | Why it matters |
|---|---|---|
| `/api/**` (except `/api/health` and the WhatsApp exception) | `Authorization: Bearer $API_TOKEN` | Protected operations API: patient data, appointments, settings and masked calling. |
| `/api/whatsapp/**` | Admin `API_TOKEN` or a personal WhatsApp staff token | Staff tokens are individually revocable and limited to inbox read/send grants; only the admin token can manage staff access. |
| `/voice/**` | `?secret=$WEBHOOK_SECRET` or `X-Webhook-Secret` | Vobiz webhook caller can book, cancel and feed recording/hangup events. Every generated Vobiz URL is signed. |
| `/webhooks/vobiz/whatsapp` | `?secret=$VOBIZ_WHATSAPP_WEBHOOK_SECRET` or `X-Webhook-Secret` | Only callbacks with the configured Vobiz shared secret are processed; confirm Vobiz's native signature scheme before production. |
| static console, `/health` | none | Static pages contain no secrets; they ask for session tokens and call protected APIs. |

With `API_TOKEN`/`WEBHOOK_SECRET` unset the general API and voice webhook surfaces retain their existing
local-development behavior; the Vobiz messaging callback returns 503 until its own webhook secret is set.
`ConfigValidator` checks the existing production secrets. The general appointment/call console still uses the single admin API token. WhatsApp is the exception:
it supports individual agent/compounder tokens with WhatsApp-only read/send permissions, stored as hashes
and independently revocable. These grants do not authenticate staff to other parts of the appointment API.

## Appointment lifecycle

`AppointmentService.canTransition` is the single source of truth for status changes:

```
requested ─┐
confirmed ─┼─▶ rescheduled ─┐
rescheduled┘                ├─▶ cancelled | completed | no_show   (terminal)
                            └─▶ confirmed
```

`cancelled`, `completed` and `no_show` are terminal, and **every** path that reaches `cancelled`
(`POST /api/appointments/{id}/cancel`, `PATCH {"status":"cancelled"}`, the IVR and the reminder
keypad) goes through the same writer, which clears `slot_key`, suppresses pending reminders and
queues the staff change-notification. Previously the `PATCH` path skipped all of that, leaving the
slot permanently blocked, and could revive a cancelled appointment with a NULL `slot_key`.

Reminder statuses reflect what actually happened: `answered` (call picked up, no keypad input),
`answered_confirmed`, `answered_reschedule_requested` (caller sent to reception, nothing rescheduled
yet), `answered_cancelled`, `no_answer`, `failed`, `suppressed`. A leg long enough to have been
listened to (`CallDuration >= 5s`) is never auto-redialled, and `reminder_retry_count` counts the
*extra* attempts.

## Compliance notes (India)

- **Announce recording** (TRAI): `announce_recording=true` plays a "this call may be recorded" line
  before bridging — keep it on.
- **DPDP Act**: caller numbers and WhatsApp message bodies/attachments are personal data. They are stored
  in PostgreSQL for clinic operations and shown to staff with the corresponding access grant; use HTTPS,
  short-lived operational access where possible, and an approved retention/deletion policy.
- **Outbound callbacks** are transactional (returning a call the customer placed). If any part
  becomes promotional, DND/DLT rules apply — check with your telecom compliance owner before scaling.
- Every bridged call bills **two legs** (caller→M1, M1→agent).

## Testing

```bash
./gradlew test                 # unit tests, no Spring context and no database
                               #   VobizXml (+IVR verbs), Json (+ nesting/escape hardening), Forms,
                               #   RoutingService, AvailabilityService, AppointmentService,
                               #   AppointmentStateMachine, ReminderService, PromptService,
                               #   SettingsService, WebhookSigner, IvrEscape,
                               #   Vobiz WhatsApp callbacks + scoped staff token grants

# end-to-end demos against a running server (mock mode). They must present the same
# credentials the server was started with, because both halves of the surface are protected:
API_TOKEN=dev-token WEBHOOK_SECRET=dev-secret ./scripts/simulate-call.sh
API_TOKEN=dev-token WEBHOOK_SECRET=dev-secret ./scripts/simulate-appointment.sh
```

`.github/workflows/ci.yml` runs `./gradlew build` on every push, boots the app against a real
PostgreSQL service container and probes `/api/health`, and `node --check`s every JavaScript file
in the repo.

## Schema and migrations

Today the schema is generated from the `@Entity` classes (`spring.jpa.hibernate.ddl-auto`, env
`DDL_AUTO`, default `update`). That is fine for development and wrong for production: `update`
never drops, renames or back-fills, so a column change that needs data migrated has to be applied
by hand, and two instances starting together can race.

The migration path is prepared, not guessed: `src/main/resources/db/migration/README.md` and
`scripts/dump-schema.sh` dump the live schema into `V1__baseline.sql`, after which you add Flyway
and set `DDL_AUTO=validate`. No hand-written baseline SQL is committed, because it would drift from
the entities it is supposed to describe.

## Data model (PostgreSQL — tables generated by Hibernate from the JPA entities, no schema.sql)

Masking core:

- `agents(id, name, phone_e164 UNIQUE, priority, status, active, created_at)`
- `calls(id, call_uuid UNIQUE, direction, caller_number, dialed_number, agent_id, dialed_agents JSONB,
  status, started_at, answered_at, ended_at, duration_sec, hangup_cause, recording_url, callback_id,
  reminder_id, created_at)`
- `callback_requests(id, call_id, caller_number, voicemail_url, voicemail_duration_sec, status,
  assigned_agent_id, notes, outbound_call_uuid, created_at, closed_at)`
- `settings(key PK, value)`

Appointments:

- `patients(id, patient_ref UNIQUE, name, phone_e164 UNIQUE, preferred_language, dnd, active, notes, …)`
- `departments(id, name UNIQUE, active, priority, created_at)`
- `doctors(id, doctor_ref, name, specialization, department_id, agent_id, slot_minutes, active, …)`
- `doctor_schedules(id, doctor_id, day_of_week, start_time, end_time, slot_minutes, active, …)`
- `doctor_time_off(id, doctor_id, from_ts, to_ts, full_day, reason, …)`
- `appointments(id, reference_code UNIQUE, patient_id, doctor_id, department_id, starts_at, ends_at,
  status, source, slot_key UNIQUE, needs_rebooking, version, …)` — `slot_key` cleared on cancel
- `appointment_events(id, appointment_id, event_type, from_status, to_status, actor, call_uuid, payload JSONB, …)`
- `reminders(id, appointment_id, kind, UNIQUE(appointment_id,kind), scheduled_at, channel, status,
  attempts, outbound_call_uuid, response_digit, …)` — kinds `t24h` / `t2h` / `change`;
  statuses `pending|placed|answered|answered_confirmed|answered_reschedule_requested|answered_cancelled|no_answer|failed|suppressed`
- `ivr_sessions(call_uuid PK, patient_id, caller_number, state, intent, collected JSONB, hops, …)`
- `message_templates(id, key, language, text, active, UNIQUE(key,language))`

Shared WhatsApp inbox:

- `whatsapp_conversations(id, wa_id UNIQUE, phone_e164 UNIQUE, contact_name, status, unread_count,
  last_message_preview, last_message_at, …)` — one shared thread per patient/contact
- `whatsapp_messages(id, conversation_id, provider_message_id UNIQUE, direction, message_type, body,
  status, sender_staff_id, media_id, created_at, …)` — incoming/outgoing content plus Vobiz delivery receipts
- `whatsapp_staff_access(id, name, role, token_hash UNIQUE, can_read, can_send, active, …)` — per-person
  agent/compounder access; only a one-time clear-text token is returned when an admin creates/rotates it

`call_uuid` joins your log to Vobiz's CDR API (`GET /api/v1/Account/{auth_id}/cdr`) for billing/disputes.

### Table relations (real JPA associations)

Every relation below is modeled with actual JPA annotations in the entities — `@ManyToOne` and
`@OneToMany(mappedBy=…)` — mapped **read-only** (`insertable=false, updatable=false`) onto the
existing `*_id` columns, so Hibernate also generates the foreign keys on boot, while all existing
write paths (plain `Long` setters) keep working unchanged. Doctors carry a single primary
`department_id`; there is **no** doctor↔department join table (`@ManyToMany` exists in neither the
entities nor the schema, whatever earlier versions of this file claimed). All associations are `FetchType.LAZY` and are never touched by the web layer
(JSON goes through `toMap()`), so they cost nothing on the webhook path. List joins for the console
stay explicit SQL in the DAOs (`AppointmentDao`: `LEFT JOIN doctors/departments`; `CallDao`/
`CallbackDao`: `LEFT JOIN agents`). Service-layer checks (`findById` → 404 before any write) plus
`UNIQUE` constraints guard integrity.

```
@ManyToOne / @OneToMany pairs (the mappedBy field on the child side is named):
agents 1───< calls.agent_id                         CallRecord.agent             who was rung
agents 1───< callback_requests.assigned_agent_id   CallbackRequest.assignedAgent
agents 1───< doctors.agent_id                       Doctor.agent                 masked handset link
departments 1───< doctors.department_id             Doctor.department            primary department
departments 1───< appointments.department_id        Appointment.department       display copy
doctors 1───< doctor_schedules.doctor_id           DoctorSchedule.doctor        UNIQUE(doctor_id, day_of_week, start_time)
doctors 1───< doctor_time_off.doctor_id            DoctorTimeOff.doctor
doctors 1───< appointments.doctor_id               Appointment.doctor           idx(doctor_id, starts_at); slot_key UNIQUE
patients 1───< appointments.patient_id             Appointment.patient          idx(patient_id, starts_at)
calls 1───< appointments.created_via_call_id       Appointment.createdViaCall   which call booked it
calls 1───< callback_requests.call_id              CallbackRequest.call
appointments 1───< appointment_events.appointment_id   AppointmentEvent.appointment   audit trail
appointments 1───< reminders.appointment_id        Reminder.appointment         UNIQUE(appointment_id, kind)
reminders 1───< calls.reminder_id                  CallRecord.reminder          outbound reminder leg in the call log
ivr_sessions ──▶ patients/appointments/reminders   IvrSession.patient/.appointment/.reminder
patients.phone_e164 (UNIQUE) = inbound caller-ID "From"  ← caller-ID match on /voice/answer
```

There is **no waitlist table**: `WaitlistEntry` and its repository were removed because nothing
ever created a row (cancelling an appointment used to mark every waiting entry "offered" for a
doctor, for a feature with no UI and no endpoint). A real waitlist needs matching against the
freed slot, notification and offer expiry — build it as its own change.

Runtime links that are not FK columns: `reminders.outbound_call_uuid` ↔ `calls.call_uuid`
(same UUID stored on both sides — `/voice/hangup` resolves the reminder through it), and
`calls.call_uuid` ↔ `ivr_sessions.call_uuid` (IVR state per call, purged after 24 h).

The "shared entry" rules that matter operationally:

- **One slot = one appointment**: `appointments.slot_key = "{doctor_id}|{starts_at}"` is UNIQUE;
  cancelling sets it to NULL, which frees the slot for someone else.
- **One appointment = at most one reminder per kind** (`t24h`, `t2h`, `change`), so re-scheduling
  replaces the row instead of duplicating it (`replaceFor`).
- **One call_uuid = one IVR session = at most one call-log row**; reminder legs are separate outbound
  `calls` rows pointing back via `reminder_id` / `outbound_call_uuid`.
