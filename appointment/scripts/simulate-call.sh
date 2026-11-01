#!/usr/bin/env bash
# End-to-end demo of the call flows against a RUNNING server (default http://localhost:8080).
# Simulates what Vobiz would POST: inbound call -> no answer -> voicemail -> hangup,
# then a click-to-call callback -> outbound answer -> hangup.
#
# Usage:  BASE=http://localhost:8080 ./scripts/simulate-call.sh
set -euo pipefail
BASE="${BASE:-http://localhost:8080}"
FROM="${FROM:-+919876543210}"
M1="${M1:-+10000000000}"
CALL_UUID="sim-$(date +%s)-$RANDOM"

echo "== 1. POST /voice/answer  (caller $FROM dials $M1)"
curl -s -X POST "$BASE/voice/answer" \
  --data-urlencode "CallUUID=$CALL_UUID" \
  --data-urlencode "From=$FROM" \
  --data-urlencode "To=$M1" \
  --data-urlencode "CallStatus=ringing"
echo; echo

echo "== 2. POST /voice/dial-result  (nobody picked up within the ring timeout)"
curl -s -X POST "$BASE/voice/dial-result" \
  --data-urlencode "CallUUID=$CALL_UUID" \
  --data-urlencode "DialStatus=no-answer" \
  --data-urlencode "DialHangupCause=NO_ANSWER"
echo; echo

echo "== 3. POST /voice/recording-done  (caller left a 12s voicemail)"
curl -s -X POST "$BASE/voice/recording-done" \
  --data-urlencode "CallUUID=$CALL_UUID" \
  --data-urlencode "Event=RecordStop" \
  --data-urlencode "RecordingUrl=https://media.example.com/recordings/$CALL_UUID.mp3" \
  --data-urlencode "RecordingDuration=12"
echo; echo

echo "== 4. POST /voice/hangup"
curl -s -X POST "$BASE/voice/hangup" \
  --data-urlencode "CallUUID=$CALL_UUID" \
  --data-urlencode "CallDuration=45" \
  --data-urlencode "HangupCause=NORMAL_CLEARING"
echo; echo

echo "== 5. pending callbacks"
CB_JSON=$(curl -s "$BASE/api/callbacks?status=pending")
CB_ID=$(printf '%s' "$CB_JSON" | python3 -c "import json,sys; d=json.load(sys.stdin); print(d['items'][0]['id'] if d['items'] else '')")
echo "$CB_JSON" | python3 -m json.tool | head -20

echo "== 6. click-to-call: POST /api/callbacks/$CB_ID/call"
CALL_RES=$(curl -s -X POST "$BASE/api/callbacks/$CB_ID/call" -H 'Content-Type: application/json' -d '{}')
echo "$CALL_RES" | python3 -m json.tool
OUT_UUID=$(printf '%s' "$CALL_RES" | python3 -c "import json,sys; print(json.load(sys.stdin).get('outbound_call_uuid',''))")

echo "== 7. GET /voice/outbound-answer  (agent picked up the outbound leg; bridge to customer)"
curl -s "$BASE/voice/outbound-answer?to=$FROM&callback_id=$CB_ID"
echo; echo

echo "== 8. POST /voice/hangup  (outbound leg ends -> callback marked called_back)"
curl -s -X POST "$BASE/voice/hangup" \
  --data-urlencode "CallUUID=$OUT_UUID" \
  --data-urlencode "CallDuration=63" \
  --data-urlencode "HangupCause=NORMAL_CLEARING"
echo; echo

echo "== 9. final call log"
curl -s "$BASE/api/calls?limit=5" | python3 -m json.tool | head -60
echo "== 10. final callback queue"
curl -s "$BASE/api/callbacks" | python3 -m json.tool | head -30
echo
echo "Done. Open $BASE/ in a browser for the ops console."
