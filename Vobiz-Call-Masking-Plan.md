# Call Masking on Vobiz — Plan & Findings

**Prepared:** 31 October 2026
**Scope:** One Vobiz DID in front of two (or more) agents, with availability-based routing, call logging for callbacks, and a recorded voicemail fallback.

---

## 1. Your requirement, restated

You want a small layer of software on top of Vobiz that does this:

1. You own **one Vobiz number** — call it **M1**. This is the only number the outside world ever sees.
2. Your interface lets you register **two real numbers** — **N2** and **N3** (the agents). Both must stay hidden from the caller.
3. Every call to **M1** is bridged to **N2 or N3**, whichever is available.
4. The system **records who called**, so a callback is possible later.
5. If **nobody is available**, the caller hears "someone will call you back" and gets **15–30 seconds** to record their issue, which is logged.
6. Before building anything, check whether **Vobiz already does all this** — and if it does, only build the front end.

That is the correct instinct, so let me answer point 6 first, because it changes the shape of the build.

---

## 2. The verdict: what is native, what is not

Vobiz **natively provides every telephony primitive** you need. It does **not** provide the business logic that decides who to ring. Concretely:

| Your requirement | Native in Vobiz? | How it works |
|---|---|---|
| A number the caller dials, that hides both real numbers | **Yes** | A Vobiz DID + `<Dial callerId="M1">` — the agent's phone shows M1, never the caller's number; the caller only ever dialled M1 |
| Routing a call to a real number based on an incoming call | **Yes** | The DID is attached to a Vobiz **Application** with an **Answer URL**; Vobiz POSTs `From`, `To`, `CallUUID` to your server, and your server replies with XML instructions |
| Ring two numbers and connect whoever picks up | **Yes** | `<Dial>` with multiple `<Number>` children dials them **simultaneously**; the first to answer gets bridged |
| Ring N2 first, then N3 if N2 doesn't answer | **Partly** | Needs two chained `<Dial>` steps via the `action` URL (Vobiz has no built-in "hunt group" flag) |
| Detect that nobody answered / everyone was busy | **Yes** | `<Dial action="...">` receives a final result with the hangup cause; XML after the `Dial` runs as the fallback |
| Play "someone will call you back" and record a message | **Yes** | `<Speak>` + `<Record maxLength="30" finishOnKey="#">`; the recording URL is POSTed to your callback |
| Call logs (who called, when, duration, why it ended) | **Yes** | CDR API: `GET /api/v1/Account/{auth_id}/cdr` with filters, plus a `hangup_url` webhook per call |
| Know whether an agent is *available* | **No** | Vobiz has no concept of agent presence, seats, or a queue. This state has to live in your app. |
| A call-back queue / "who do we owe a call" list | **No** | This is your database. Vobiz has no queue or ticketing object. |
| A ready-made "call masking product" with a UI | **No** | Vobiz publishes masking as a *pattern* with reference repos (one-way, two-way, PIN-based), not as a switchable feature. The docs are explicit that "masking is a real-time application decision" and that you keep the number-mapping logic on your own server. |

**So the honest answer to point 6: the front end alone is not enough.** There is no Vobiz setting or single API call that says "route to whoever is free, else take a voicemail". What Vobiz sells you is the pipe; the routing decision, the availability state, and the callback list are roughly **200–300 lines of backend** on your side. There is no way around that, and it is small.

What this does mean in your favour: **you are not building a telephony stack.** No SIP trunk configuration, no media handling, no codecs, no carrier relationships. You are writing one webhook that returns a few lines of XML, plus a small database.

---

## 3. One design decision that matters more than the rest

Your words were "route to anyone which is available". There are two ways to read that, and they behave very differently:

**Option A — Ring both at once (simultaneous).**
`<Dial timeout="20">` with N2 and N3 as children. Both phones ring; the first to answer takes the call; the other stops ringing. Zero availability tracking needed. This is native Vobiz behaviour and it is what most small teams actually want.

**Option B — True presence (sequential / targeted).**
Each agent marks themselves Available / Busy / Offline in your frontend (or is auto-marked busy while on a call). The router rings only available agents, in a priority order, and skips the rest. This needs your own state store and a "busy" flag flipped by the call events.

My recommendation: **build Option A first** — it is a day's work and it already satisfies "route to whoever is available" in the practical sense. Add Option B later as a routing policy layered on the same webhook; the two are not mutually exclusive, and the code structure below supports both.

There is also a real-world wrinkle worth naming: a phone that rings and is ignored is indistinguishable from a phone in someone's pocket. Only Option B can tell the difference between "not picking up" and "on another call".

---

## 4. Architecture

```
 Caller ──dials M1──▶ Vobiz ──POST /voice/answer {From, To, CallUUID}──▶ Your backend
                        │                                                     │
                        │                                    1. log the caller
                        │                                    2. pick agent(s)
                        │                                    3. return XML
                        │◀──────────── <Response> XML ───────────────────────┘
                        │
                        ├── <Dial callerId="M1"> N2 + N3 </Dial>
                        │        ├─ answered ──▶ bridged; caller sees M1, agent sees M1
                        │        └─ no answer ──▶ POST /voice/dial-result
                        │                              └─▶ <Speak> + <Record maxLength=30>
                        │                                        └─▶ POST /voice/recording-done
                        │                                                  └─ save voicemail URL
                        └── POST /voice/hangup  ──▶ final duration + cause (logging only)
```

Components:

- **Vobiz Application** — one object in the Vobiz console, holding the Answer URL, Hangup URL and Fallback URL. M1 is attached to it. Created once, either in the console or via `POST /api/v1/Account/{auth_id}/Application/`.
- **Your backend** — a small web service (FastAPI or Express) with 4–5 endpoints. This is the "layer of software".
- **Your database** — one table for agents, one for call records, one for voicemail/callback requests. SQLite is fine to start; Postgres if this is going to be shared.
- **Your frontend** — the interface where you register numbers, see the call log, play voicemails and mark callbacks done. **This is the piece I have not built, because you asked me to ask first.**

Credentials never live in code: `VOBIZ_AUTH_ID` and `VOBIZ_AUTH_TOKEN` go in environment variables. All Vobiz REST calls carry them as `X-Auth-ID` / `X-Auth-Token` headers; the XML side needs no auth, which is why the Answer URL should be HTTPS and, ideally, IP- or signature-restricted.

---

## 5. The call flows, step by step

### 5.1 Normal call — someone picks up

1. Caller dials **M1**.
2. Vobiz POSTs to `/voice/answer` with `From` (caller), `To` (M1), `CallUUID`.
3. Backend inserts a row in `calls` with status `in_progress`, caller number, timestamp, CallUUID.
4. Backend selects agents — Option A: all active agents; Option B: available agents in priority order.
5. Backend returns:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<Response>
  <Dial callerId="M1" timeout="20" action="https://yourapp.com/voice/dial-result" method="POST">
    <Number>N2</Number>
    <Number>N3</Number>
  </Dial>
  <Speak>Sorry, none of our team is free right now. Please tell us your issue after the beep,
         and someone will call you back.</Speak>
  <Record maxLength="30" timeout="5" playBeep="true" finishOnKey="#"
          callbackUrl="https://yourapp.com/voice/recording-done" />
  <Speak>Thank you. We will call you back shortly.</Speak>
  <Hangup/>
</Response>
```

6. Vobiz rings N2 and N3 together. First answer wins; audio bridges. Both handsets display **M1** — neither party ever learns the other's real number.
7. When the bridge ends, `action` fires with the outcome, then the Hangup URL fires with duration and cause. Backend updates the row to `answered`, stores which agent took it and for how long.

Note the XML fallback: because `<Dial>`'s result is ignored when `redirect` is left at its default only if you return XML from `action` — the simpler and more reliable pattern is to put the voicemail XML **after** the `Dial` in the same document, as above. If the Dial connects, the call ends before reaching those lines. If it fails, the caller hears the message and records. That removes one round trip and one failure mode.

### 5.2 Nobody available — voicemail

1. Both agents fail to answer within `timeout` (20s is a reasonable default; Vobiz's own default of 120s is far too long for this).
2. Control falls through to the `<Speak>` + `<Record>`.
3. The caller gets a beep and up to 30 seconds, ending early on `#` or after 5 seconds of silence.
4. Vobiz POSTs `Event=RecordStop`, `RecordFile` (or `RecordUrl`), `RecordingDuration` to the callback URL.
5. Backend writes a `callback_requests` row: caller number, recording URL, duration, timestamp, status `pending`.
6. The frontend shows this as a call-back task with a play button.

Two things to set deliberately: `maxLength="30"` is the hard ceiling you asked for, and `timeout="5"` ends the recording after 5 seconds of silence so a caller who hangs up mid-message doesn't occupy a line. `finishOnKey="#"` lets an impatient caller finish early.

### 5.3 Callback — the part your spec implies but doesn't spell out

Here is the tension: the agents **cannot** call the customer back, because they were never shown the customer's number. That is the whole point of masking. So there are two honest ways to do callbacks:

**Option 1 — System-initiated callback (recommended).** The agent clicks "Call back" on the log entry. Your backend uses the Vobiz outbound Call API to dial the agent first, then bridges to the customer with `callerId=M1`. Both sides see M1; the agent never sees the number, and the customer receives a call from the familiar M1. This is the cleanest fit and uses `POST /api/v1/Account/{auth_id}/Call/`.

**Option 2 — Agent dials back through M1.** The agent calls M1 from their registered phone; the backend recognises the `From`, finds the pending callback for that agent, and bridges to the customer. This needs no click-to-call plumbing but gives the agent no choice of *which* customer, so it only works when there is exactly one pending callback per agent.

I would build Option 1. It also gives you the audit trail you actually want: every outbound call is initiated by a person, recorded against a row, and impossible to place by hand from a personal phone.

---

## 6. Data model

Three tables carry the whole system.

**agents**
`id`, `name`, `phone_e164`, `priority` (int, for sequential routing), `status` (`available` / `busy` / `offline`), `active` (bool), `created_at`

**calls**
`id`, `call_uuid` (from Vobiz — the join key to CDRs), `caller_number`, `dialed_number` (M1), `agent_id` (nullable, set on answer), `status` (`in_progress` / `answered` / `no_answer` / `voicemail` / `failed`), `started_at`, `answered_at`, `ended_at`, `duration_sec`, `hangup_cause`, `recording_url` (nullable)

**callback_requests**
`id`, `call_id`, `caller_number`, `voicemail_url`, `voicemail_duration_sec`, `status` (`pending` / `called_back` / `closed`), `assigned_agent_id`, `notes`, `created_at`, `closed_at`

The `call_uuid` is what lets you reconcile your own log against Vobiz's CDR API when billing or a dispute comes up — worth storing from day one.

---

## 7. Build list

| # | Item | Effort | Notes |
|---|---|---|---|
| 1 | Buy one Vobiz DID (M1) + create the Application | 30 min | Needs KYC/eKYC on the Vobiz account |
| 2 | Backend skeleton: `/voice/answer`, `/voice/dial-result`, `/voice/recording-done`, `/voice/hangup`, `/health` | Half a day | FastAPI; returns XML as strings |
| 3 | Database + the three tables | Half a day | SQLite is enough |
| 4 | Availability routing (Option A: ring all; Option B: presence) | A few hours / +1 day | Start with A |
| 5 | Voicemail capture + callback request creation | Half a day | Mostly the `Record` callback handler |
| 6 | Click-to-call callback via the outbound Call API | Half a day | `POST /Account/{auth_id}/Call/` |
| 7 | Internal REST API for the frontend (`/api/agents`, `/api/calls`, `/api/callbacks`) | Half a day | |
| 8 | HTTPS deployment + tunnel for testing | 2 hours | Vobiz needs a public HTTPS Answer URL; a webhook that fails or returns invalid XML drops the call (codes 7011 / 8011) |
| 9 | Frontend | 1–2 days | See section 9 — **awaiting your go-ahead** |

Roughly: **backend is 2–3 working days, frontend another 1–2.**

Operational constraints to respect while building:
- The Answer URL must respond in **1–2 seconds**. No slow database calls on the hot path — cache the agent list in memory.
- Each XML response must be under 100 KB and valid; a malformed response drops the call.
- Vobiz does not hold call-flow state between webhooks. Every handler must work from `CallUUID` alone.

---

## 8. Numbers, cost and compliance

**Numbers you need to buy:** exactly **one** DID for this design. That is a real advantage of your single-number setup — the two-way masking reference design needs two DIDs per relationship, because there the dialled number encodes the direction. Yours doesn't, since the public only ever calls in. You only need a second DID if you later want agents to *dial out* to customers through a separate line, or want the callback direction to be distinguishable.

**Billing:** every bridged call bills two legs (caller→M1, M1→agent). Voicemail recording and speech/transcription are billed separately if you enable them.

**Compliance, which is not optional in India:**
- **Announce recording.** TRAI and two-party-consent norms mean you should play a short "this call may be recorded" line before bridging or before the voicemail beep. Vobiz's own documentation flags this explicitly.
- **DPDP Act.** The caller's number is personal data. Log it for the stated purpose (callback), retain it for a defined period, and don't let it leak into places it isn't needed.
- **Outbound callbacks** are a different regulatory surface from inbound. If callbacks are transactional (returning a call the customer placed), that is straightforward; if any part becomes promotional, DND and DLT rules come into play. Worth a check with whoever handles your telecom compliance before you scale callbacks.

---

## 9. Frontend — what it would contain (not built yet)

You asked me to ask before building it. If you say yes, this is the scope I'd propose — a single-page internal tool, not a customer-facing product:

1. **Numbers** — register/edit N2, N3 and any future agents, with an Available/Busy toggle per agent.
2. **Live calls** — what's ringing, who's on a call right now, how long.
3. **Call log** — table of every inbound call: caller number, time, who answered, duration, outcome (answered / no answer / voicemail). Filterable by date and status.
4. **Callback queue** — pending voicemails with an inline player, the recording length, a "Call back" button, and a notes field.
5. **Settings** — ring timeout, voicemail length, greeting text or audio upload, which agents are in rotation.

Stack: plain HTML/JS or React against the internal REST API from item 7. No customer login, no multi-tenancy — this is your ops console.

---

## 10. Decisions I need from you

1. **Availability: Option A or Option B?** Ring both agents at once and let the fastest answer win, or track real presence so a busy agent isn't rung? (A is a day's work, B adds another.)
2. **Callbacks: system-initiated click-to-call, or agents dialling back through M1?** I recommend click-to-call.
3. **How many agents, realistically?** Two now, or is this the start of a rotating team of six? It changes whether we need priority ordering and shift windows.
4. **Voicemail greeting: text-to-speech or a recorded human voice?** TTS in English, Hindi, or both? Vobiz handles multiple languages, and for Indian callers a Hindi-or-English choice is often worth having.
5. **Do you want the answered calls recorded too**, or only voicemails? Recording every call is a compliance and storage decision, not a technical one.
6. **Where will this be hosted?** The Answer URL must be publicly reachable over HTTPS, so we need a host (or I can set it up to run on a laptop via a tunnel for the first tests).

---

## 11. What I have and haven't verified

Verified against Vobiz's current documentation: the masking pattern and its reference implementations, the Answer/Action/Hangup webhook model, `<Dial>` with multiple numbers as *simultaneous* dialling, `callerId` requiring a Vobiz-owned DID (an unauthorised caller ID fails with hangup code 3030), `<Record>` with `maxLength`/`timeout`/`finishOnKey`, `<Gather>` for DTMF/speech, and the CDR API's filter set and pagination.

Not verified, because it needs your account: whether **simultaneous** dialling is supported on your specific number type and pricing plan, the exact per-minute rate for your destination circles, and whether your account is cleared for outbound calls with M1 as caller ID. Those three are worth confirming with Vobiz support or in the console before we commit to the design.
