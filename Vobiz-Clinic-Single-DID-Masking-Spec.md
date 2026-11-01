# Clinic Call Masking on Vobiz — One DID per Clinic, Up to 3 Executives

Status: design spec
Date: 2 November 2026
Supersedes: an earlier two-DID masking draft, now removed from this repo. The two-DID pattern
is still the right approach when two specific people must reach each other in both directions;
this document is the clinic shape: one public number, up to three executives, patients outside.

---

## 1. The model

One **clinic DID** per clinic. It is the only number the outside world ever sees or dials.
Behind it sit up to **three communication executives** (CE), each on their own mobile.

```
                         ┌──────────────────────────────────┐
   Patient ──dials M──▶  │  Vobiz                           │
                         │  DID M  (one per clinic)         │
   Patient ◀──sees M──   └───────────┬──────────────────────┘
                                     │  POST /vobiz/inbound
                                     ▼
                          ┌────────────────────────┐
                          │  your backend          │
                          │  To = M → clinic       │
                          │  pick available CEs    │
                          └───────┬────────────────┘
                                  │  <Dial callerId="M"> CE1 CE2 CE3
                                  ▼
                     CE1 ── CE2 ── CE3   (all ring, first answer wins)
```

**Inbound:** patient dials the clinic DID, backend rings the available executives, first to
answer takes the call. The executive sees the clinic number (or the patient's number, per
clinic policy — see §4), the patient sees nothing but the clinic DID.

**Outbound:** the executive does not dial the patient from their handset. They press **Call**
in the clinic app; the backend originates the call through Vobiz, the executive's phone rings
first, and the patient's phone then rings showing the clinic DID.

**Both legs are masked by construction:** the patient never learns any executive's mobile
number, and — if the clinic is in `mask` mode — the executive never stores the patient's number
on a personal handset either.

### 1.1 Why the outbound leg must be API-initiated

With a single DID you cannot tell direction from the dialled number, which is exactly what the
two-DID pattern buys you. Here direction is established by **how the call starts**:

| Direction | How it starts | How you know |
|---|---|---|
| Patient → clinic | Patient dials M | an inbound webhook arrives with `To = M` |
| Executive → patient | Executive presses Call; your backend hits the Call API | **you** made the API call, so you already know |

So there is no ambiguity to resolve at webhook time. The `To = M` webhook is always an inbound
patient call; anything outbound was created by your own backend and carries a token.

Do not try to let executives "dial M to call out" — that collides with inbound routing and
needs a PIN flow. It is listed as a fallback in §10, not part of this build.

**But the executive's handset has no such information** — that is a separate problem, and it is
solved in §4.2, not here. Read that section before you build the outbound flow.

---

## 2. Glossary and configuration

| Term | Meaning |
|---|---|
| **M** | the clinic DID (E.164), one per clinic |
| **CE** | communication executive — a real mobile number behind M, max 3 per clinic |
| **caller-ID mode** | per-clinic policy: `mask` (CE sees M) or `reveal` (CE sees patient number) |
| **M_CB** | optional second *internal* DID, used only as the caller ID on callbacks to the executive (§4.2) |

| Key | Example | Notes |
|---|---|---|
| `VOBIZ_AUTH_ID` | `MA_XXXXXX` | |
| `VOBIZ_AUTH_TOKEN` | — | secret manager only |
| `VOBIZ_API_BASE` | `https://api.vobiz.ai/api/v1` | |
| `PUBLIC_BASE_URL` | `https://api.yourclinic.app` | must be HTTPS, reachable by Vobiz |
| `MAX_EXECUTIVES_PER_CLINIC` | `3` | enforce in app, not just UI |
| `BRIDGE_TOKEN_TTL_S` | `300` | expiry on click-to-call tokens |

---

## 3. Data model

```
clinics
  id, name
  did_e164              UNIQUE          -- M; one row per clinic, one number per clinic
  callback_did_e164     NULL            -- M_CB, optional, internal only (§4.2)
  ring_timeout_s        default 25      -- how long to ring the group
  caller_id_mode        mask | reveal
  recording_enabled     bool
  timezone              e.g. Asia/Kolkata

executives
  id, clinic_id FK
  name
  mobile_e164                           -- never leaves the server
  priority              int             -- tie-break order for sequential dialling
  status                available | busy | offline
  active                bool            -- soft delete
  UNIQUE(clinic_id, mobile_e164)
  CHECK: count(active) per clinic <= 3

calls
  id
  call_uuid             -- Vobiz CallUUID, the correlation key
  clinic_id FK
  direction             inbound | outbound
  patient_e164          -- encrypted or hashed; see §8
  executive_id FK NULL  -- who answered, filled at dial-result
  started_at, answered_at, ended_at, duration_s
  outcome               answered | no_answer | busy | voicemail | failed
  hangup_cause
  recording_url

callback_queue
  id, clinic_id FK, patient_e164, recording_url, notes,
  created_at, status    pending | called | closed
```

Everything is keyed on the clinic, which is what makes this multi-tenant: the same backend
serves every clinic, and `To = M` selects the tenant.

---

## 4. Caller-ID policy — and the constraint that dictates how you implement it

You want both behaviours available. Make it a per-clinic column (`caller_id_mode`), optionally
overridable per call.

| Mode | Executive's handset shows | How you implement it |
|---|---|---|
| `mask` | the clinic DID, **M** | set `callerId="M"` on the `Dial` |
| `reveal` | the patient's real number | **omit** the `callerId` attribute entirely |

This distinction is not stylistic — it is forced by the platform. `callerId` on a `Dial` must be
a Vobiz number your account owns or is authorised to use. Setting it to the patient's number
will be rejected as `Unknown Caller ID` (hangup code `3030`). The documented way to pass the
originating number through is to **leave `callerId` off**, in which case Vobiz derives it from
the A-leg.

So:

```xml
<!-- mask mode: executive sees the clinic number -->
<Dial callerId="919000000001" timeout="25" action="/vobiz/dial-result">
  <Number>918888800001</Number>
  <Number>918888800002</Number>
  <Number>918888800003</Number>
</Dial>

<!-- reveal mode: no callerId attribute at all; Vobiz passes the A-leg number through -->
<Dial timeout="25" action="/vobiz/dial-result">
  <Number>918888800001</Number>
  <Number>918888800002</Number>
  <Number>918888800003</Number>
</Dial>
```

### 4.1 The cost of `mask` mode, and how to pay it

In `mask` mode the executive's phone rings showing an unfamiliar clinic number, so they cannot
tell a new patient from a follow-up, or from a wrong number. Three mitigations, best first:

1. **Whisper on answer.** `Dial`'s `confirmSound` points at a URL that Vobiz requests *after the
   B-leg answers*; it must return XML containing only `Play`, `Speak`, and/or `Gather`. Have it
   say "Call from Ravi Sharma, second call this week" before the bridge settles. This is the
   cleanest answer and costs one extra webhook.
   Caveat: `confirmKey` — the digit intended to gate acceptance — is documented as unverified as
   an acceptance gate. Play the whisper, do not depend on a keypress to connect the call.
2. **`callerName` on the `Dial`.** Pass the patient's name (max ~50 chars). Whether the handset
   displays it is up to the destination carrier and device, so treat it as best-effort garnish,
   not a guarantee.
3. **App screen-pop.** Subscribe to `Dial`'s `callbackUrl`, and on the `answered` event push the
   patient record to the executive's app. Requires a realtime channel to the app and arrives a
   beat after the call is answered.

Recommendation: build (1) and (3). Ship `mask` as the default for new clinics, since it is the
setting that keeps patient numbers off personal handsets — the DPDP-friendlier posture.

### 4.2 Direction signalling — how the executive knows inbound from outbound

One DID means the executive's handset presents the same number in both directions, so the phone
alone cannot tell them "a patient is calling the clinic" from "this is the callback I just
placed". That is a genuine usability gap of the single-DID design, and it has to be closed
deliberately rather than hoped away.

First, note where the problem actually bites: in **`reveal` mode it mostly disappears** — an
inbound call shows the patient's number, while an outbound callback rings the executive from the
clinic DID. The ambiguity is specific to **`mask` mode**, where both legs present M.

Four signals, in order of reliability. Build signal 1 regardless; layer the others on top.

| # | Signal | Reliability | Cost |
|---|---|---|---|
| 1 | A different spoken announcement per direction, on answer | works on any handset, any carrier, including a desk phone | one extra webhook (inbound), one extra `Speak` (outbound) |
| 2 | A different caller ID per direction — second, internal DID for callbacks | the phone's own screen shows a different number | one extra DID per clinic, never published to patients |
| 3 | `callerName` set to a direction label | best-effort: carrier and handset decide whether to display it | free |
| 4 | App screen-pop / push stating direction and patient | needs the app open and a realtime channel | your app |

**Signal 1 — the announcement (build this first).** Both directions already contain a moment
where you speak privately to the executive before the patient is connected. Make the two
sentences different.

Inbound: the executive answers, and `Dial`'s `confirmSound` fires on the B-leg before the bridge
settles.

```xml
<Dial callerId="919000000001"
      timeout="25"
      confirmSound="https://api.yourclinic.app/vobiz/whisper?call={call_id}"
      confirmTimeout="10"
      action="/vobiz/dial-result?call={call_id}">
  <Number>918888800001</Number>
  <Number>918888800002</Number>
  <Number>918888800003</Number>
</Dial>
```

`/vobiz/whisper` returns Speak-only XML — "Incoming patient call, Ravi Sharma, second call
today." — and then the bridge completes. Caveat as in §4.1: play the whisper, do not gate on a
keypress.

Outbound: the executive answers first, so the click-to-call `answer_url` runs before the patient
is dialled. Say which way the call is going, by name:

```xml
<Response>
  <Speak language="en-IN">Calling Ravi Sharma now. Please wait.</Speak>
  <Dial callerId="919000000001" timeout="30" timeLimit="3600">
    <Number>91XXXXXXXXXX</Number>
  </Dial>
</Response>
```

"Incoming patient call…" versus "Calling Ravi Sharma now…" — two different sentences, one per
direction. It costs nothing, needs no extra infrastructure, and it is the only signal that
survives a desk phone, a dead battery on the app, or a handset with no data connection. This is
the requirement, so treat it as non-negotiable in the build.

**Signal 2 — a second internal DID (the glanceable option).** If you want the phone's own screen
to say it, give callbacks a distinct caller ID: a second DID used *only* on the leg to the
executive and never published to patients. The public number stays one.

```
inbound   →  <Dial callerId="M">     →  executive's phone shows M      ("Clinic")
outbound  →  Call API from = M_CB    →  executive's phone shows M_CB   ("Clinic Callback")
             then <Dial callerId="M"> →  patient still sees M
```

Patients never see `M_CB`. It exists so the executive's handset can display a different number,
and if the clinic saves both as contacts, the incoming-call screen names them correctly. The
honest cost: the account now holds two numbers per clinic even though only one is public, so it
is not free — but it is the most glanceable option and the only one that works without the
executive hearing anything.

**Signal 3 — `callerName`.** Set it per direction: the patient's name on the inbound `Dial`,
and something like "Callback" in the outbound `Call` API's `caller_name` field. Whether the
handset displays it is up to the carrier and device. Free, so set it, but never rely on it.

**Signal 4 — the app.** Push a notification when the outbound leg is answered, and show a live
state ("Outbound to Ravi Sharma — ringing patient"). This is the same screen-pop as §4.1, so it
costs nothing extra once the app exists.

**One property that already helps, and one rule that follows from it:** an outbound callback
rings **only the executive who pressed Call** — never the group — while an inbound call rings
every available executive. So the shape of the ring is a weak hint (one phone rings: probably
your callback; three ring: definitely inbound). Do not rely on it, but it is the reason the
outbound flow must never fan out to the group.

Recommendation: signal 1 in the first build, signal 2 if the clinic will carry the extra
internal DID, signal 4 when the app ships, signal 3 always.

---

## 5. Flow 1 — Patient calls the clinic

```
1. Patient dials M.
2. Vobiz POSTs /vobiz/inbound, form-encoded:
     From=<patient>, To=<M>, CallUUID=<uuid>, Direction=inbound, CallStatus=ringing
3. Backend:
     clinic = clinics.byDid(To)              -- unknown DID → reject, hang up
     log a `calls` row (direction=inbound, patient_e164=From)
     execs  = executives.where(clinic, status=available).orderBy(priority)
     if execs is empty → voicemail flow (§7)
4. Backend returns XML: <Dial> with one <Number> per available executive.
5. Vobiz rings them all concurrently; the first to answer is bridged to the patient.
6. Vobiz POSTs the Dial `action` URL with the final result → fill answered_at, executive_id,
   outcome.
7. Call ends → Vobiz POSTs /vobiz/hangup → duration, HangupCause, recording URL.
```

Response shape:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<Response>
  <Dial callerId="919000000001"
        timeout="25"
        timeLimit="3600"
        confirmSound="https://api.yourclinic.app/vobiz/whisper?call={call_id}"
        confirmTimeout="10"
        action="https://api.yourclinic.app/vobiz/dial-result?call={call_id}"
        method="POST"
        callbackUrl="https://api.yourclinic.app/vobiz/dial-events?call={call_id}"
        callbackMethod="POST">
    <Number>918888800001</Number>
    <Number>918888800002</Number>
    <Number>918888800003</Number>
  </Dial>
</Response>
```

Notes:

- **Simultaneous dialling is native** — multiple `<Number>` children ring together, the first
  answer wins, the rest stop. With three executives there is no reason to build anything clever.
- `timeout` is the ringing phase (seconds to wait for an answer). `timeLimit` is the connected
  phase (default 14400). Do not confuse them.
- `confirmSound` is what tells the answering executive this is an inbound patient call (§4.2).
  Without it, `mask` mode is genuinely ambiguous on the handset.
- Use `action` to learn *which* executive answered and what the outcome was. Without it you have
  a call log with no owner.
- Put your own `call_id` in the URL rather than the patient's number — see §8.
- Only ring executives whose `status = available`. If you skip availability tracking entirely,
  all three ring every time and call-waiting decides; that is acceptable for a 3-person clinic
  but it means an executive mid-call gets a second incoming call.

### 5.1 Sequential ring (optional, not the default)

Vobiz has no hunt-group flag. To ring CE1, then CE2, then CE3, chain `Dial` steps through
`action`:

```
<Dial action="/vobiz/next?step=2&call=..."><Number>CE1</Number></Dial>
   → action fires with the DialResult; if it was no-answer/busy, return another Dial for CE2
   → and so on; after the last step, fall through to voicemail
```

Only build this if the clinic genuinely wants priority order (senior executive first). For three
people, simultaneous is better — patients get answered faster and there is less code.

---

## 6. Flow 2 — Executive calls the patient (click-to-call)

The executive never dials the patient's number. The app asks the backend to place the call.

```
1. Executive taps "Call" on a patient in the clinic app.
   POST /api/calls { patient_id, executive_id }        (authenticated as that executive)
2. Backend authorises: patient belongs to this clinic, executive is active and available.
3. Backend mints a single-use token, stores {clinic, patient_e164, executive_id, exp}
   for BRIDGE_TOKEN_TTL_S. No PII in the URL.
4. Backend calls Vobiz:
     POST {VOBIZ_API_BASE}/Account/{auth_id}/Call/
     {
       "from": "<M_CB or M>",                  // caller ID the executive's phone shows
       "to": "<executive mobile>",             // Vobiz rings the executive FIRST
       "answer_url": "https://api.yourclinic.app/vobiz/bridge/{token}",
       "answer_method": "POST",
       "ring_timeout": "30",
       "time_limit": "3600",
       "caller_name": "Callback",              // best-effort direction label (§4.2)
       "hangup_url": "https://api.yourclinic.app/vobiz/hangup",
       "hangup_method": "POST"
     }
5. Vobiz rings the executive. They answer.
6. Vobiz POSTs /vobiz/bridge/{token}. Backend resolves the token, checks it is unused and
   unexpired, and returns:
       <Response>
         <Speak language="en-IN">Calling Ravi Sharma now. Please wait.</Speak>
         <Dial callerId="919000000001" timeout="30" timeLimit="3600"
               action="/vobiz/bridge-result?token=...">
           <Number>91XXXXXXXXXX</Number>       <!-- the patient -->
         </Dial>
       </Response>
7. The patient's phone rings showing M. Both legs bridge.
```

Why the executive is dialled first: they initiate, so they should be the one who has to pick up.
It also means a failed/ignored call costs nothing on the patient's side, and you get a clean
"executive unavailable" outcome instead of a patient hearing silence.

Requirements this flow imposes:

- **`from` must be a DID you own** — M, or M_CB if you are using the second-DID signal. Vobiz
  uses it as the caller ID for the leg to the executive, and you re-assert `callerId="M"` on the
  `Dial` to the patient. The patient always sees M, whichever you choose.
- **The announcement is not optional.** The `Speak` before `Dial` is what tells the executive
  this is their outbound call and who it is to (§4.2). Do not reduce it to a generic "connecting".
- **The token must be single-use.** Anyone who guesses the bridge URL can make your account dial
  a number. Bind it to the `CallUUID` Vobiz sends, and consume it on first use.
- **Validate the patient against the clinic** before minting the token. Otherwise an executive at
  clinic A can dial a patient of clinic B — a cross-tenant data leak.
- **Never dial an executive's own number as the patient.** Reject `patient == executive.mobile`.
- **Never fan the outbound leg out to the group.** It rings the initiating executive only; that
  is part of how direction is signalled.
- The `Call` API returns `api_id` / `request_uuid` immediately with a `200`; that means *queued*,
  not answered. Track the real state through `answer_url` and `hangup_url`.

---

## 7. Flow 3 — Nobody available

When no executive is `available`, or the simultaneous `Dial` returns no-answer/busy for all
three, take the message instead of losing the patient:

```xml
<Response>
  <Speak language="en-IN">
    All our executives are on another call. Please describe your issue after the beep
    and we will call you back.
  </Speak>
  <Record maxLength="30"
          timeout="5"
          finishOnKey="#"
          playBeep="true"
          action="https://api.yourclinic.app/vobiz/voicemail-done?call={call_id}"
          method="POST" />
  <Speak>Thank you. We will call you back shortly.</Speak>
</Response>
```

On the `Record` action webhook you get `RecordingURL` and the duration: write a `callback_queue`
row, mark the call `outcome = voicemail`, and show it in the clinic app with an inline player.
The callback itself uses Flow 2 — the executive presses Call on the queue item.

Language: Vobiz TTS supports multiple Indian languages. For a clinic, having the greeting in the
patient's likely language (or a bilingual greeting) is worth the extra config.

---

## 8. Security, PII and DPDP

- **Patient numbers are the sensitive payload.** In `mask` mode they exist only server-side. Store
  them encrypted at rest, or store a hash plus a separate encrypted column if you need lookups.
  Never in a URL, never in a log line, never in a third-party error tracker.
- **Tokens, not numbers, in URLs.** Every `answer_url` / `action` / `callbackUrl` should carry an
  opaque token or your internal `call_id`. Vobiz logs these URLs; so do your proxies.
- **Do not put patient names in a whisper URL.** The whisper text comes from your own lookup at
  request time, not from the query string — the URL carries only `call_id`.
- **Authenticate the webhooks.** `/vobiz/*` endpoints are reachable by anyone who learns the URL.
  Restrict to Vobiz egress IPs, or put a shared secret in the path, or verify a signature. HTTPS
  only.
- **Authorise the click-to-call.** The API must verify the caller is an active executive of the
  clinic that owns the patient. This is the one endpoint where a mistake dials a stranger.
- **Call recording is a consent decision, not a technical one.** If `recording_enabled`, the
  greeting should announce recording, and retention should be a stated window. Under DPDP the
  patient has to know.
- **Audit the mapping.** `executives.mobile_e164` is personal data of your staff too. Same
  handling: encrypted, access-controlled, and a soft delete rather than a hard one so historical
  call logs still resolve.
- **Retention.** Define a window for `calls` and `callback_queue` (e.g. 90 days for audio, 12
  months for metadata) and enforce it with a scheduled job.

---

## 9. Failure modes

| Symptom | Cause | Fix |
|---|---|---|
| Executive cannot tell an inbound patient call from their own callback | both directions present the same caller ID (M) in `mask` mode | directional announcement per §4.2; optionally a second internal DID for callbacks |
| Patient connects but the executive sees a real number in `mask` mode | `callerId` missing or wrong | assert `callerId == clinic.did_e164` before emitting XML |
| Hangup `3030` `Unknown Caller ID` | `callerId` set to something you do not own — commonly the patient's number in `reveal` mode | in `reveal` mode **omit** the attribute; never set it to a third party's number |
| Hangup `7011` | Answer URL unreachable or non-2xx | HTTPS reachability, IP allow-list, certificate |
| Hangup `8011` | Invalid XML returned | validate and escape; check `Content-Type: application/xml` |
| Nothing rings, Vobiz never calls your webhook | DID not attached to the application, or app disabled | re-check attach, `public_uri`, use the console's Test URL |
| Every call goes to voicemail | no executive marked `available`, or availability never resets | flip status back on the hangup webhook, not on a timer alone |
| Executive rings forever after a call ends | `busy` flag set on answer and never cleared | clear on `hangup_url` and on `Dial` action, both |
| Whisper never plays | `confirmSound` URL unreachable, or returns XML with elements other than `Play`/`Speak`/`Gather` | verify the endpoint; strip everything else from the response |
| Click-to-call dials the wrong patient | token reused or not bound to `CallUUID` | single-use tokens, consume on first use, short TTL |
| Two patients get bridged together | token collision or a mapping keyed on the wrong field | key tokens on a random id; never on the patient number |
| Bridge drops mid-call | `timeLimit` too short, or slow webhook | raise `timeLimit`; keep the handler sub-second |

---

## 10. Deliberately out of scope (and the fallback if you need it)

**Executive dials M to call out, with a PIN.** This is the PIN-based single-DID pattern: the
executive calls the clinic number, an IVR collects their PIN and the patient's number or a speed
dial code, and the call is bridged out with `callerId = M`. It works with no app at all, which is
its appeal, but it collides with inbound routing — every inbound call has to be classified as
"patient or executive?" before it can be routed, and a mistyped PIN is a silent misroute. You
chose click-to-call, which is cleaner. Keep this in the back pocket for executives who are
travelling or whose app is broken.

Worth noting it would also solve direction signalling for free — an executive dialling in knows
they initiated — which is the one argument in its favour.

**Also out of scope for now:** per-executive DIDs (you chose one shared public DID), call
transfer between executives, and WhatsApp/SMS notifications to the callback queue.

---

## 11. Test matrix

Offline, POSTing to your own handlers:

```
# inbound, DID known, executives available -> expect Dial with 3 Numbers, callerId = M,
# and a confirmSound attribute pointing at the whisper endpoint
curl -s -X POST localhost:8080/vobiz/inbound \
  -d "From=919812345678" -d "To=919000000001" -d "CallUUID=test-1"

# whisper endpoint -> expect Speak-only XML, mentioning inbound and the patient
curl -s -X POST "localhost:8080/vobiz/whisper?call=123"

# inbound, unknown DID -> expect Speak + Hangup, no Dial
curl -s -X POST localhost:8080/vobiz/inbound -d "From=919812345678" -d "To=919999999999"

# inbound, no executives available -> expect the Record flow
curl -s -X POST localhost:8080/vobiz/inbound -d "From=919812345678" -d "To=919000000001"

# reveal-mode clinic -> expect a Dial with NO callerId attribute
curl -s -X POST localhost:8080/vobiz/inbound -d "From=919812345678" -d "To=919000000002"

# bridge endpoint, valid token -> expect Speak announcing the patient, then Dial callerId=M
curl -s -X POST localhost:8080/vobiz/bridge/<token> -d "CallUUID=test-2" -d "To=918888800001"

# bridge endpoint, token reused -> expect rejection, no Dial
curl -s -X POST localhost:8080/vobiz/bridge/<token> -d "CallUUID=test-3"

# bridge endpoint, expired token -> expect rejection
# missing From or To on inbound -> 4xx, not 500
```

Live, in order:

1. Patient dials M → all three executive phones ring → first to answer is bridged.
2. **Direction check, inbound:** in `mask` mode the executive answers and hears "Incoming patient
   call…" before the patient is connected.
3. **Direction check, outbound:** the executive presses Call, answers their own phone, and hears
   "Calling Ravi Sharma now…" — unmistakably different from step 2.
4. Same inbound call, in `reveal` mode → executive's screen shows the patient's real number.
5. Same call with the second-DID signal enabled → the callback rings showing M_CB, the inbound
   rings showing M, and the patient sees M in both cases.
6. Executive presses Call → patient's phone rings showing M → bridged → hang up → `calls` row
   complete with duration and owner.
7. No executive available → greeting + 30s recording → row appears in `callback_queue` → play it
   back → press Call → bridged.
8. Executive mid-call, second patient calls → availability logic skips them (or call-waiting
   catches it, if you skipped availability).
9. Pull the app's Answer URL in the console → confirm the fallback serves valid XML.

---

## 12. Definition of done

- [ ] One DID per clinic, attached to one Vobiz application, answer + hangup + fallback URLs set
- [ ] `To = M` resolves to exactly one clinic; unknown DIDs are rejected, not defaulted
- [ ] Executive count per clinic enforced at 3 in the API, not just the UI
- [ ] `caller_id_mode` honoured — `mask` sets `callerId=M`, `reveal` omits the attribute
- [ ] **The executive can tell direction from the call itself**: a distinct inbound whisper and a
      distinct outbound announcement (§4.2), verified by ear on a real handset in both modes
- [ ] Simultaneous `<Dial>` rings all available executives; `action` records who answered
- [ ] Click-to-call rings only the initiating executive, never the group
- [ ] Click-to-call uses single-use, short-TTL tokens bound to `CallUUID`, with no PII in the URL
- [ ] Patient is authorised against the executive's clinic before any outbound dial
- [ ] Availability flips to busy on answer and back on hangup, from both webhooks
- [ ] Voicemail writes to `callback_queue` and the recording is playable in the app
- [ ] Webhooks authenticated, HTTPS only, patient numbers encrypted at rest
- [ ] Retention window defined and enforced for audio and metadata
- [ ] All offline cases and all nine live cases pass

---

## 13. Open decisions

1. **Default `caller_id_mode` for new clinics** — recommend `mask`.
2. **Direction signalling** — is one spoken announcement per direction enough, or will the clinic
   pay for a second internal DID so a callback visibly rings from a different number? (§4.2)
3. **Recording** — on for all calls, voicemail only, or off? Consent wording needed if on.
4. **Greeting language** — English, Hindi, or bilingual?
5. **Ring timeout** — 25s default; longer means fewer missed patients but slower voicemail.
6. **Availability** — do executives self-mark, or is busy inferred purely from live calls?

---

## 14. Reference

Vendor docs:

- Number masking (concept, mapping model) — `https://www.vobiz.ai/docs/solutions/number-masking`
- Dial XML element — `https://docs.vobiz.ai/xml/dial`
- Voice XML overview — `https://docs.vobiz.ai/xml/overview`
- Make an outbound call — `https://vobiz.ai/docs/call/make-call`
- Applications API — `https://www.vobiz.ai/docs/applications`
- Create an Application — `https://www.vobiz.ai/docs/applications/create-application`
- Attach Number to Application — `https://www.vobiz.ai/docs/applications/attach-number`
- Call Management API — `https://docs.vobiz.ai/call/overview`
- Phone Numbers API — `https://docs.vobiz.ai/account-phone-number`

Reference implementations (MIT, Python/FastAPI):

- Two-way (two DIDs) — `https://github.com/vobiz-ai/Vobiz-Two-Way-Number-Masking`
- PIN-based single DID — `https://github.com/vobiz-ai/Vobiz-PIN-Based-Number-Masking`
- One-way (destination-based) — `https://github.com/vobiz-ai/Vobiz-One-Way-Number-Masking`
- Session-based — `https://github.com/vobiz-ai/Vobiz-Session-Based-Number-Masking`
