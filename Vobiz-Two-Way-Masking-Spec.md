# Two-Way Number Masking on Vobiz — Integration Spec

Status: draft for implementation
Scope: two-way, client-hosted masking (one masking DID per direction, static mapping table, no session store)
Vendor docs: see [Reference](#12-reference) at the end

---

## 1. What you are building

Two people (agent and customer) talk on a real phone call. Neither handset ever sees the
other's real number. The only number either party ever dials or sees is a Vobiz DID you own.

```
Agent  ──dials M1──▶ Vobiz ──POST /answer {From=AGENT, To=M1}──▶ your backend
                                                                 │ resolve(To, From)
                                                                 │  ├ normalise digits
                                                                 │  ├ check From == registered caller
                                                                 │  └ pick destination + callerId
Agent  ◀══ audio bridged ══ Vobiz ◀── <Dial callerId="M2"><Number>CUSTOMER</Number></Dial>
                                    └─ Vobiz dials CUSTOMER, who sees M2
```

The reverse direction is the mirror image. Your backend owns the mapping; Vobiz owns the
DIDs, the inbound termination and the bridge. There is no per-call state to store — every
webhook carries everything needed to decide, so the handler is stateless and idempotent.

**Why two DIDs and not one:** a single number cannot distinguish "connect me to the customer"
from "connect me to the agent" when both parties dial it. The dialled number *is* the
direction. That is the whole design.

---

## 2. Provisioning (one-time, per pairing)

### 2.1 Buy two DIDs

Buy from inventory via the Phone Numbers API, or in the console. Note the E.164 form with the
`+` prefix — you will need it URL-encoded as `%2B...` in API paths.

```
GET  https://api.vobiz.ai/api/v1/Account/{auth_id}/inventory/numbers?country=IN&per_page=50
POST https://api.vobiz.ai/api/v1/Account/{auth_id}/inventory/numbers/{number}   # purchase
```

### 2.2 Create one XML application

Both DIDs point at the **same** application. One app, one Answer URL, two numbers.

```http
POST https://api.vobiz.ai/api/v1/Account/{auth_id}/Application/
X-Auth-ID: {auth_id}
X-Auth-Token: {auth_token}
Content-Type: application/json

{
  "app_name": "masking-twoway",
  "answer_url": "https://api.yourapp.com/vobiz/answer",
  "answer_method": "POST",
  "hangup_url": "https://api.yourapp.com/vobiz/hangup",
  "hangup_method": "POST",
  "fallback_answer_url": "https://api.yourapp.com/vobiz/fallback",
  "fallback_method": "POST"
}
```

Response gives you `app_id`. Notes:

- `app_name` allows letters, digits, `-`, `_` only.
- `hangup_url` defaults to `answer_url` if omitted — set it explicitly, you do not want hangup
  callbacks hitting the answer handler.
- `fallback_answer_url` fires only when `answer_url` is unreachable, times out, or returns
  invalid XML. Set it in production; without it the call drops.

### 2.3 Attach both DIDs to that application

```http
POST https://api.vobiz.ai/api/v1/Account/{auth_id}/numbers/%2B919000000001/application
Content-Type: application/json

{ "application_id": "{app_id}" }
```

Repeat for `%2B919000000002`. Gotchas:

- The number must be URL-encoded (`+` → `%2B`). A raw `+` will not match and returns 404.
- Re-attaching an already-linked number silently overwrites the binding. No detach needed.
- 404 means either the number is not owned by your account or the `app_id` is wrong.

---

## 3. Configuration

Keep all of this in env / secret manager. No real numbers in source, no numbers in the
container image.

| Key | Example | Notes |
|---|---|---|
| `VOBIZ_AUTH_ID` | `MA_XXXXXX` | API auth ID |
| `VOBIZ_AUTH_TOKEN` | — | API auth token |
| `VOBIZ_API_BASE` | `https://api.vobiz.ai/api/v1` | |
| `M1` | `+919000000001` | DID the **agent** dials → reaches the customer |
| `M2` | `+919000000002` | DID the **customer** dials → reaches the agent |
| `AGENT` | `+918888800001` | agent's real number |
| `CUSTOMER` | `+918888800002` | customer's real number |

---

## 4. The mapping model

Keyed by the dialled number (`To`). Caller ID is **cross-mapped**: dial M1 and the customer
sees M2; dial M2 and the agent sees M1. Each party therefore sees one stable number for the
other, which is what makes it feel like a normal contact rather than a rotating unknown.

| `To` (dialled) | `From` must be | Dial (destination) | `callerId` shown |
|---|---|---|---|
| `M1` | `AGENT` | `CUSTOMER` | `M2` |
| `M2` | `CUSTOMER` | `AGENT` | `M1` |

Structure it as one record per row:

```
Leg {
  maskingNumber   // the number that was dialled (== To)
  allowedCaller   // the only real number permitted to use it
  destination     // the real number to bridge to
  callerIdShown   // the masking DID the destination will see
}
```

### 4.1 Number normalisation (do not skip this)

Vobiz may hand you numbers with or without `+`, with or without the country code, and users
dial with spaces or a leading zero. Normalise to digits-only and compare the **last 10**
digits for Indian numbers. This makes `+919000000001`, `919000000001`, `0919000000001` and
`9000000001` all match.

```
normalise(n):
  d = strip every non-digit from n
  return last 10 digits of d        // India; use last N per country otherwise
```

### 4.2 Resolve

```
resolve(to, from) -> Leg | null:
  leg = normalisedIndex[normalise(to)]
  if leg == null:                        return null     // unknown masking number
  if normalise(leg.allowedCaller) != normalise(from): return null   // unauthorised caller
  return leg
```

The caller-authorisation check is the security property that pays for the simplicity of the
static table: knowing the masking number is not enough to reach the protected party, you must
also be dialling from a number the platform already knows.

---

## 5. Webhook contract

### 5.1 Answer URL — request

Vobiz sends `application/x-www-form-urlencoded`, method `POST`.

| Field | Required | Notes |
|---|---|---|
| `From` | yes | caller's real number (the A-leg) |
| `To` | yes | the masking DID that was dialled |
| `CallUUID` | no | use for correlation and logging |
| `Direction` | no | e.g. `inbound` |
| `CallStatus` | no | e.g. `ringing` |

Do not require fields beyond `From` and `To` — some events omit the optional ones.

### 5.2 Answer URL — response

Content-Type `application/xml`. A bridge:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<Response>
  <Dial callerId="919000000002" timeout="30" timeLimit="3600">
    <Number>918888800002</Number>
  </Dial>
</Response>
```

A rejection (unknown masking number, or caller not registered for it):

```xml
<?xml version="1.0" encoding="UTF-8"?>
<Response>
  <Speak>Sorry, this number is not authorised for your account.</Speak>
  <Hangup reason="rejected"/>
</Response>
```

`callerId` **must** be a Vobiz DID owned or authorised by your account. Passing a real number
or a number you do not own is rejected by the carrier as `Unknown Caller ID` (hangup code
`3030`). This is the single most common way masking "works" but leaks — the call connects and
the recipient sees a real number.

If `callerId` is omitted, Vobiz derives it from the A-leg, and B-leg creation may fail if that
number is not authorised for outbound. Always set it explicitly.

### 5.3 Dial attributes worth setting

| Attribute | Use here |
|---|---|
| `callerId` | **the masking DID** — the whole point |
| `timeout` | seconds to wait for the B-leg to answer. Set explicitly, default behaviour is not predictable |
| `timeLimit` | max connected seconds on the B-leg, starts after answer, ringing excluded. Default 14400 |
| `action` | final result callback after Dial finishes; pair with `redirect` to continue the flow |
| `callbackUrl` | real-time B-leg events (answer, connected, digits, hangup) |
| `hangupOnStar` | `true` lets the A-leg press `*` to end the bridge — useful for agents |
| `dialMusic` | ringback for the A-leg while bridging; `real` is the ordinary path |
| `sipHeaders` | `k=v,k=v` metadata, surfaced in webhooks with an `X-VH-` prefix — handy for tagging `pairing_id` |

`timeout` and `timeLimit` are different phases: ringing vs connected. Do not confuse them.
`confirmKey` exists but its use as an acceptance gate is unverified — do not depend on a
keypress to control whether the B-leg connects.

### 5.4 Hangup URL

Form-encoded POST at call end, no XML expected. Expect at minimum `CallUUID`, `Duration`,
`HangupCause`. Use it to close out the pairing record and to alert on abnormal causes.

---

## 6. Handler skeleton

Language-neutral; the HTTP shape is what matters.

```
POST /vobiz/answer
  parse form: From, To, CallUUID
  leg = resolve(To, From)
  if leg == null:
     log(WARN, "masking rejected", callUuid, mask=hash(To))
     return 200, xml(speak("not authorised") + hangup("rejected"))
  log(INFO, "masking bridged", callUuid, pairingId)
  return 200, xml(dial(leg.destination, callerId=leg.callerIdShown, timeout=30, timeLimit=3600))
```

XML builders — keep them as functions, do not hand-concatenate strings:

```
response(...children)              -> "<Response>" + join(children) + "</Response>"
dial(dest, callerId, timeout, ...) -> <Dial callerId=.. timeout=..><Number>dest</Number></Dial>
speak(text)                        -> <Speak>text</Speak>
hangup(reason)                     -> <Hangup reason="reason"/>
```

Escape `text` and attribute values. Numbers must go in E.164 (`+` or digits; the reference
implementation sends digits only, both work).

**Response budget:** answer within about a second; the documented hard ceiling is 10 seconds
before the call times out. Keep the lookup in memory or in a fast cache — no slow DB calls on
the hot path, no external HTTP on the hot path.

---

## 7. Failure modes and what they mean

| Symptom | Cause | Fix |
|---|---|---|
| Call connects but recipient sees a real number | `callerId` is not an owned DID, or the mapping returned the real number | assert `callerId ∈ {M1, M2}` in code, not just in config |
| Hangup code `3030` | `Unknown Caller ID` — `callerId` is not a number you own | use a provisioned DID |
| Hangup code `7011` | Answer URL unreachable or returned non-2xx | check HTTPS reachability, IP allow-list, cert |
| Hangup code `8011` | Answer URL returned invalid XML | validate output; escape text; check content-type |
| Every call rejected as not authorised | `From` does not match the registered caller | check the caller dials from the registered handset; matching is last-10-digits so formatting is tolerant but digits must match |
| Vobiz never hits your webhook | DIDs not attached to the app, or app not enabled / public | re-check attach, check `public_uri`, use the console's Test URL |
| Bridge is slow or drops mid-call | slow answer handler, or `timeLimit` too short | cache the lookup; raise `timeLimit` |

Codes `3030` / `7011` / `8011` are as documented in Vobiz's reference implementation
troubleshooting; confirm against the Hangup Causes page for the live list.

---

## 8. Production hardening

- **Authenticate `/answer`.** The reference samples leave it open; anyone who finds the URL can
  POST to it. Add an IP allow-list for Vobiz egress, a shared secret in the path, mTLS, or a
  signature check. HTTPS only.
- **Treat the mapping as the sensitive asset.** It holds real numbers *and* the relationship
  between them, which is often more sensitive than either number alone. Secret manager, never
  a checked-in file or an image layer. If it leaks, masking protects nobody in it — retire the
  DIDs, issue new ones, re-point the table, and treat it as a personal-data breach under DPDP.
- **Idempotency.** Vobiz retries failed webhooks up to 3 times. The handler is stateless, so
  retries are safe — just do not make the retry path write duplicate rows keyed on something
  non-unique.
- **Logging and PII.** Do not write `From` or the resolved destination in clear text. Store a
  hash or masked form, keep `CallUUID` for correlation, and set a retention window. DPDP
  posture wants minimisation, and the call log is where masking usually leaks first.
- **Real numbers travel in the XML response.** The `<Number>` body is plaintext, so the TLS
  terminator and anything that proxies the webhook is inside your trust boundary. Do not route
  it through a third party you would not hand the mapping table to.
- **Fallback.** Set `fallback_answer_url` to something that plays an apology and hangs up, not
  to the same handler.

---

## 9. Test matrix

Offline, by POSTing to your own handler:

```
# authorised, agent → customer: expect Dial callerId=M2, Number=CUSTOMER
curl -s -X POST localhost:8080/vobiz/answer -d "From=918888800001" -d "To=919000000001"

# unauthorised caller: expect Speak + Hangup reason=rejected
curl -s -X POST localhost:8080/vobiz/answer -d "From=910000000000" -d "To=919000000001"

# unknown masking number: expect Speak + Hangup
curl -s -X POST localhost:8080/vobiz/answer -d "From=918888800001" -d "To=919999999999"

# formatting tolerance: all four must resolve identically
-d "To=919000000001" / -d "To=+919000000001" / -d "To=0919000000001" / -d "To=9000000001"

# missing From or To: must 4xx, not 500
```

Live, in order:

1. Agent dials M1 → customer's phone rings showing M2 → both talk.
2. Customer dials M2 → agent's phone rings showing M1.
3. Third party dials M1 → spoken rejection, no bridge.
4. Agent dials M1, customer does not answer → `timeout` fires, A-leg gets ringback then ends.
5. Hang up from each side → Hangup URL fires with sane `Duration` and `HangupCause`.
6. Pull the app's Answer URL → confirm the fallback URL serves valid XML.

---

## 10. Scaling this design

Two DIDs per pairing is fine for a fixed or slowly growing set of agent↔customer relationships
and it is the simplest thing that works. It does not scale to thousands of pairings — you would
be renting two numbers per customer.

When volume grows, in order of effort:

1. **Keep the two-DID model, key the lookup on `(To, From)`** instead of `To` alone. Reuse a
   pool of DIDs across many pairings and rotate them. Same handler, bigger table.
2. **Move to session-based masking** — a pool sized to peak *concurrent* conversations, one
   number lent per job with a TTL, returned to the pool on completion. Numbers are borrowed
   and returned rather than owned per pair. This is the right model for rides, deliveries and
   other short-lived jobs, and it self-destructs the link by default.
3. **PIN-based single DID** if you would rather have one advertised number and route by code.

---

## 11. Definition of done

- [ ] Two DIDs purchased, both attached to one app, app has answer + hangup + fallback URLs
- [ ] `resolve()` normalises to last 10 digits and rejects unauthorised callers
- [ ] `callerId` is asserted to be one of the masking DIDs before the XML is emitted
- [ ] Handler answers in well under 1s from in-memory/cached data
- [ ] `/answer` is authenticated and HTTPS-only
- [ ] Logs carry `CallUUID` + a hash, never the real numbers
- [ ] All six offline cases and all six live cases pass
- [ ] Fallback URL returns valid XML
- [ ] Retention window defined for call metadata

---

## 12. Reference

Vendor docs:

- Number masking (concept, mapping model, use cases) — `https://www.vobiz.ai/docs/solutions/number-masking`
- Two-way masking walkthrough — `https://www.vobiz.ai/docs/examples/vobiz-two-way-number-masking-python`
- Dial XML element (full attribute list) — `https://docs.vobiz.ai/xml/dial`
- Voice XML overview (webhook → XML cycle) — `https://docs.vobiz.ai/xml/overview`
- Applications API (create / attach / detach) — `https://www.vobiz.ai/docs/applications`
- Create an Application — `https://www.vobiz.ai/docs/applications/create-application`
- Attach Number to Application — `https://www.vobiz.ai/docs/applications/attach-number`
- Call Management API — `https://docs.vobiz.ai/call/overview`
- Phone Numbers API — `https://docs.vobiz.ai/account-phone-number`
- Docs index for agents — `https://vobiz.ai/docs/llms.txt`

Reference implementation (MIT, Python/FastAPI, verified end-to-end on live numbers):

- `https://github.com/vobiz-ai/Vobiz-Two-Way-Number-Masking`

Sibling strategies, same XML helpers and setup script shape, cheap to switch between:

- One-way (destination-based) — `https://github.com/vobiz-ai/Vobiz-One-Way-Number-Masking`
- Session-based two-way — `https://github.com/vobiz-ai/Vobiz-Session-Based-Number-Masking`
- PIN-based, single DID — `https://github.com/vobiz-ai/Vobiz-PIN-Based-Number-Masking`
