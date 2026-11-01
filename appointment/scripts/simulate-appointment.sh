#!/usr/bin/env bash
# End-to-end demo of the CALL-FIRST APPOINTMENT flow against a RUNNING server
# (default http://localhost:8080). Simulates what Vobiz would POST for:
#   1. inbound call on masked M1 -> IVR -> book an appointment (digits 1-1-1-1-1)
#   2. a second call -> reschedule it (2, pick 1, slot 1, confirm 1)
#   3. a third call -> cancel another appointment (3, pick 1, confirm 1)
#   4. pressing 0 -> falls through to the classic masked bridge to agents
#   5. an outbound reminder call (T-24h/T-2h/change) -> keypad confirm (digit 1)
# then prints the resulting appointments / reminders / stats from the REST API.
#
# Usage:  BASE=http://localhost:8080 ./scripts/simulate-appointment.sh
set -euo pipefail
BASE="${BASE:-http://localhost:8080}"
FROM="${FROM:-+919876543210}"          # demo patient seeded by DataSeeder
M1="${M1:-+10000000000}"

say() { echo; echo "== $*"; }
post() { # post <path> <key=value>...
  local path="$1"; shift
  local args=()
  for kv in "$@"; do args+=(--data-urlencode "$kv"); done
  curl -s -X POST "$BASE$path" "${args[@]}"
}

say "1. inbound call on M1: POST /voice/answer (caller $FROM)"
C1="apt-$(date +%s)-$RANDOM"
post /voice/answer "CallUUID=$C1" "From=$FROM" "To=$M1" "CallStatus=ringing"
echo

say "2. main menu, press 1 (book): POST /voice/ivr/menu"
post "/voice/ivr/menu?call_uuid=$C1" "CallUUID=$C1" "Digits=1"; echo

say "3. pick department 1: POST /voice/ivr/department"
post "/voice/ivr/department?call_uuid=$C1" "CallUUID=$C1" "Digits=1"; echo

say "4. pick doctor 1: POST /voice/ivr/doctor"
post "/voice/ivr/doctor?call_uuid=$C1" "CallUUID=$C1" "Digits=1"; echo

say "5. pick first slot: POST /voice/ivr/slot"
post "/voice/ivr/slot?call_uuid=$C1" "CallUUID=$C1" "Digits=1"; echo

say "6. confirm booking (digit 1): POST /voice/ivr/confirm"
post "/voice/ivr/confirm?call_uuid=$C1" "CallUUID=$C1" "Digits=1"; echo

say "7. appointments booked via IVR"
curl -s "$BASE/api/appointments?q=&status=booked" | python3 -m json.tool | head -40
REF=$(curl -s "$BASE/api/appointments?status=booked" | python3 -c "import json,sys; d=json.load(sys.stdin); print(d['items'][0]['reference_code'] if d['items'] else '')")
echo "reference: ${REF:-<none>}"

say "8. second call: reschedule that appointment (menu 2 -> pick 1 -> slot 1 -> confirm 1)"
C2="apt-$(date +%s)-$RANDOM"
post /voice/answer "CallUUID=$C2" "From=$FROM" "To=$M1" "CallStatus=ringing" > /dev/null
post "/voice/ivr/menu?call_uuid=$C2" "CallUUID=$C2" "Digits=2"; echo
post "/voice/ivr/reschedule?call_uuid=$C2" "CallUUID=$C2" "Digits=1"; echo
post "/voice/ivr/slot?call_uuid=$C2" "CallUUID=$C2" "Digits=1"; echo
post "/voice/ivr/confirm?call_uuid=$C2" "CallUUID=$C2" "Digits=1"; echo

say "9. third call: cancel the remaining upcoming appointment (menu 3 -> pick 1 -> confirm 1)"
C3="apt-$(date +%s)-$RANDOM"
post /voice/answer "CallUUID=$C3" "From=$FROM" "To=$M1" "CallStatus=ringing" > /dev/null
post "/voice/ivr/menu?call_uuid=$C3" "CallUUID=$C3" "Digits=3"; echo
post "/voice/ivr/cancel?call_uuid=$C3" "CallUUID=$C3" "Digits=1"; echo
post "/voice/ivr/cancel?call_uuid=$C3" "CallUUID=$C3" "Digits=1"; echo

say "10. press 0 at the main menu -> classic masked bridge to agents (<Dial callerId=M1>)"
C4="apt-$(date +%s)-$RANDOM"
post /voice/answer "CallUUID=$C4" "From=$FROM" "To=$M1" "CallStatus=ringing" > /dev/null
post "/voice/ivr/menu?call_uuid=$C4" "CallUUID=$C4" "Digits=0"; echo

say "11. outbound reminder call: /voice/reminder-answer then keypad confirm (digit 1)"
REM=$(curl -s "$BASE/api/reminders?status=pending" | python3 -c "import json,sys; d=json.load(sys.stdin); it=d['items'][0] if d['items'] else {}; print(it.get('id',''), it.get('appointment_id',''))")
RID=$(echo "$REM" | cut -d' ' -f1); AID=$(echo "$REM" | cut -d' ' -f2)
if [ -n "$RID" ]; then
  C5="rem-$(date +%s)-$RANDOM"
  curl -s "$BASE/voice/reminder-answer?reminder_id=$RID&appointment_id=$AID&call_uuid=$C5"; echo
  post "/voice/reminder-response?reminder_id=$RID&call_uuid=$C5" "CallUUID=$C5" "Digits=1"; echo
else
  echo "(no pending reminder right now — book an appointment >2h out first, or POST /api/reminders/run)"
fi

say "12. appointment stats"
curl -s "$BASE/api/stats/appointments" | python3 -m json.tool

say "13. appointments after the demo"
curl -s "$BASE/api/appointments?limit=10" | python3 -c "
import json,sys
d=json.load(sys.stdin)
for a in d['items']:
    print(f\"{a['reference_code']:>8}  {a['status']:<10} {a['source']:<10} {a['starts_at']}  {a.get('patient_name') or ''} / {a.get('doctor_name') or ''}\")
"

say "14. reminders after the demo"
curl -s "$BASE/api/reminders?limit=10" | python3 -c "
import json,sys
d=json.load(sys.stdin)
for r in d['items']:
    print(f\"#{r['id']:<3} {r['kind']:<6} {r['status']:<22} due {r['scheduled_at']}  digit={r.get('response_digit') or '-'}  apt={r.get('appointment_ref') or r['appointment_id']}\")
"
echo
echo "Done. Open $BASE/ for the ops console (Appointments / Reminders tabs)."
