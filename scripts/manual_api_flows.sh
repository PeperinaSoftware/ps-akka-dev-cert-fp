#!/usr/bin/env bash

set -u
set -o pipefail

BASE_URL="${BASE_URL:-http://localhost:9000}"
CURL_BIN="${CURL_BIN:-curl}"
BOOKING_SLEEP_SECONDS="${BOOKING_SLEEP_SECONDS:-20}"
PROJECTION_WAIT_SECONDS="${PROJECTION_WAIT_SECONDS:-10}"
BOOKING_POLL_TOTAL_SECONDS="${BOOKING_POLL_TOTAL_SECONDS:-180}"
BOOKING_POLL_INTERVAL_SECONDS="${BOOKING_POLL_INTERVAL_SECONDS:-1}"
PARTICIPANT_VIEW_POLL_TOTAL_SECONDS="${PARTICIPANT_VIEW_POLL_TOTAL_SECONDS:-120}"
STRICT_MAIN_FLOW="${STRICT_MAIN_FLOW:-0}"
BOOKING_MAX_RETRIES="${BOOKING_MAX_RETRIES:-3}"

if [ -t 1 ]; then
  C_RESET="$(printf '\033[0m')"
  C_BOLD="$(printf '\033[1m')"
  C_RED="$(printf '\033[31m')"
  C_GREEN="$(printf '\033[32m')"
  C_YELLOW="$(printf '\033[33m')"
  C_CYAN="$(printf '\033[36m')"
  C_BLUE="$(printf '\033[34m')"
  C_MAGENTA="$(printf '\033[35m')"
else
  C_RESET=""
  C_BOLD=""
  C_RED=""
  C_GREEN=""
  C_YELLOW=""
  C_CYAN=""
  C_BLUE=""
  C_MAGENTA=""
fi

STEP=1
CHECKS_TOTAL=0
CHECKS_PASSED=0
CHECKS_FAILED=0
CHECKS_WARN=0
LAST_STATUS=""
LAST_BODY=""
LAST_BODY_RAW=""
LAST_HEADERS=""
LOG_HEADERS="${LOG_HEADERS:-0}"

NOW_HOUR="$(date '+%Y-%m-%d-%H')"
# Dedicated slot for confirm-only smoke (must not overlap A/B/C hours used below).
FUTURE_SLOT_CONFIRM="$(date -d '+2 day' '+%Y-%m-%d-14')"
FUTURE_SLOT_A="$(date -d '+3 day' '+%Y-%m-%d-10')"
FUTURE_SLOT_B="$(date -d '+4 day' '+%Y-%m-%d-11')"
FUTURE_SLOT_C="$(date -d '+5 day' '+%Y-%m-%d-12')"
PAST_SLOT="$(date -d '-3 day' '+%Y-%m-%d-09')"
RUN_ID="$(date '+%s')"

STUDENT_ID="student-${RUN_ID}"
AIRCRAFT_ID="aircraft-${RUN_ID}"
INSTRUCTOR_ID="instructor-${RUN_ID}"
BOOKING_CONFIRM="booking-${RUN_ID}-confirm-only"
BOOKING_A="booking-${RUN_ID}-a"
BOOKING_B="booking-${RUN_ID}-b"
BOOKING_C="booking-${RUN_ID}-c"
UNKNOWN_BOOKING="booking-${RUN_ID}-unknown"

declare -A REQ_DESC
declare -A REQ_PASS
declare -A REQ_FAIL

REQ_DESC["REQ-API-01"]="slotId format (YYYY-MM-DD-HH) must validate"
REQ_DESC["REQ-API-02"]="Invalid participant type must be rejected"
REQ_DESC["REQ-API-03"]="Invalid status on /slots must be rejected"
REQ_DESC["REQ-BUS-01"]="Booking only for future slots"
REQ_DESC["REQ-BUS-02"]="Booking requires valid availability"
REQ_DESC["REQ-BUS-03"]="Cancel unknown booking returns 404"
REQ_DESC["REQ-BUS-04"]="Cancel with wrong slotId returns 400"
REQ_DESC["REQ-FLOW-01"]="Main flow: available -> booking -> cancel"
REQ_DESC["REQ-FLOW-03"]="Confirm-only: available -> booking -> CONFIRMED (no cancel in script)"
REQ_DESC["REQ-IDEM-01"]="Idempotent mark/unmark availability"
REQ_DESC["REQ-IDEM-02"]="Idempotent booking/cancel"
REQ_DESC["REQ-API-04"]="Core HTTP endpoints match contract"
REQ_DESC["REQ-FLOW-02"]="Booking/cancel visible for student, instructor, aircraft"

banner() {
  echo
  echo "${C_CYAN}${C_BOLD}============================================================${C_RESET}"
  echo "${C_CYAN}${C_BOLD}$1${C_RESET}"
  echo "${C_CYAN}${C_BOLD}============================================================${C_RESET}"
}

step() {
  echo
  echo "${C_BOLD}${C_BLUE}[STEP $STEP]${C_RESET} $1"
  STEP=$((STEP + 1))
}

info() {
  echo "${C_CYAN}  INFO${C_RESET} - $1"
}

pass() {
  CHECKS_TOTAL=$((CHECKS_TOTAL + 1))
  CHECKS_PASSED=$((CHECKS_PASSED + 1))
  echo "${C_GREEN}  PASS${C_RESET} - $1"
}

fail() {
  CHECKS_TOTAL=$((CHECKS_TOTAL + 1))
  CHECKS_FAILED=$((CHECKS_FAILED + 1))
  echo "${C_RED}  FAIL${C_RESET} - $1"
}

warn() {
  CHECKS_TOTAL=$((CHECKS_TOTAL + 1))
  CHECKS_WARN=$((CHECKS_WARN + 1))
  echo "${C_YELLOW}  WARN${C_RESET} - $1"
}

mark_req_pass() {
  local req_id="$1"
  REQ_PASS["$req_id"]=$(( ${REQ_PASS["$req_id"]:-0} + 1 ))
}

mark_req_fail() {
  local req_id="$1"
  REQ_FAIL["$req_id"]=$(( ${REQ_FAIL["$req_id"]:-0} + 1 ))
}

assert_status() {
  local expected="$1"
  local label="$2"
  local req_id="${3:-}"
  local matched="false"
  IFS='|' read -ra codes <<< "$expected"
  for code in "${codes[@]}"; do
    if [ "$LAST_STATUS" = "$code" ]; then
      matched="true"
      break
    fi
  done
  if [ "$matched" = "true" ]; then
    pass "${label} (expected: ${expected}, got: ${LAST_STATUS})"
    if [ -n "$req_id" ]; then mark_req_pass "$req_id"; fi
  else
    fail "${label} (expected: ${expected}, got: ${LAST_STATUS})"
    if [ -n "$req_id" ]; then mark_req_fail "$req_id"; fi
  fi
}

assert_body_contains() {
  local expected_fragment="$1"
  local label="$2"
  local req_id="${3:-}"
  if [[ "$LAST_BODY_RAW" == *"$expected_fragment"* ]]; then
    pass "${label} (contains: ${expected_fragment})"
    if [ -n "$req_id" ]; then mark_req_pass "$req_id"; fi
  else
    fail "${label} (does not contain: ${expected_fragment})"
    if [ -n "$req_id" ]; then mark_req_fail "$req_id"; fi
  fi
}

assert_body_not_contains() {
  local unexpected_fragment="$1"
  local label="$2"
  local req_id="${3:-}"
  if [[ "$LAST_BODY_RAW" == *"$unexpected_fragment"* ]]; then
    fail "${label} (unexpected fragment: ${unexpected_fragment})"
    if [ -n "$req_id" ]; then mark_req_fail "$req_id"; fi
  else
    pass "${label} (does not contain: ${unexpected_fragment})"
    if [ -n "$req_id" ]; then mark_req_pass "$req_id"; fi
  fi
}

has_cmd() {
  command -v "$1" >/dev/null 2>&1
}

pretty_json() {
  local input="$1"
  if [ -z "$input" ]; then
    echo "<empty>"
    return 0
  fi
  if has_cmd jq; then
    if printf '%s' "$input" | jq . 2>/dev/null; then
      return 0
    fi
  fi
  if has_cmd python3; then
    if printf '%s' "$input" | python3 -m json.tool 2>/dev/null; then
      return 0
    fi
  fi
  printf '%s\n' "$input"
}

status_label() {
  local code="$1"
  case "$code" in
    2*) echo "SUCCESS" ;;
    4*) echo "CLIENT_ERROR" ;;
    5*) echo "SERVER_ERROR" ;;
    *) echo "OTHER" ;;
  esac
}

request() {
  local method="$1"
  local path="$2"
  local body="${3:-}"
  local url="${BASE_URL}${path}"

  local headers_file
  local body_file
  headers_file="$(mktemp)"
  body_file="$(mktemp)"

  step "${method} ${path}"
  echo "${C_MAGENTA}  REQUEST${C_RESET} ${method} ${url}"
  if [ -n "$body" ]; then
    echo "${C_MAGENTA}  REQUEST JSON${C_RESET}"
    pretty_json "$body" | sed 's/^/    /'
    LAST_STATUS="$("$CURL_BIN" -sS \
      -X "$method" "$url" \
      -H "Content-Type: application/json" \
      -d "$body" \
      -D "$headers_file" \
      -o "$body_file" \
      -w "%{http_code}")"
  else
    LAST_STATUS="$("$CURL_BIN" -sS \
      -X "$method" "$url" \
      -D "$headers_file" \
      -o "$body_file" \
      -w "%{http_code}")"
  fi

  LAST_HEADERS="$(sed 's/^/  /' "$headers_file")"
  if [ -s "$body_file" ]; then
    LAST_BODY_RAW="$(<"$body_file")"
    LAST_BODY="$(pretty_json "$LAST_BODY_RAW" | sed 's/^/    /')"
  else
    LAST_BODY_RAW=""
    LAST_BODY="    <empty>"
  fi

  echo "${C_BOLD}  RESPONSE STATUS${C_RESET} ${LAST_STATUS} ($(status_label "$LAST_STATUS"))"
  if [ "$LOG_HEADERS" = "1" ]; then
    echo "${C_BOLD}  RESPONSE HEADERS${C_RESET}"
    echo "$LAST_HEADERS"
  fi
  echo "${C_BOLD}  RESPONSE BODY${C_RESET}"
  echo "$LAST_BODY"

  rm -f "$headers_file" "$body_file"
}

sleep_before_next_booking() {
  echo
  info "Waiting ${BOOKING_SLEEP_SECONDS}s before next booking (rate-limit spacing)..."
  sleep "${BOOKING_SLEEP_SECONDS}"
}

wait_projection() {
  sleep "${PROJECTION_WAIT_SECONDS}"
}

# GET without incrementing STEP; sets LAST_STATUS / LAST_BODY_RAW / LAST_BODY for downstream asserts.
silent_get() {
  local path="$1"
  local url="${BASE_URL}${path}"
  local body_file hdr_file
  body_file="$(mktemp)"
  hdr_file="$(mktemp)"
  LAST_STATUS="$("$CURL_BIN" -sS \
    -X GET "$url" \
    -D "$hdr_file" \
    -o "$body_file" \
    -w "%{http_code}")"
  rm -f "$hdr_file"
  if [ -s "$body_file" ]; then
    LAST_BODY_RAW="$(<"$body_file")"
    LAST_BODY="$(pretty_json "$LAST_BODY_RAW" | sed 's/^/    /')"
  else
    LAST_BODY_RAW=""
    LAST_BODY="    <empty>"
  fi
  rm -f "$body_file"
}

# ParticipantSlots view is eventually consistent — poll until body contains fragment or timeout.
poll_slots_until_body_contains_needle() {
  local participant_id="$1"
  local slot_status="$2"
  local needle="$3"
  local end=$(( $(date +%s) + PARTICIPANT_VIEW_POLL_TOTAL_SECONDS ))
  echo
  info "Waiting for projection: /flight/slots/${participant_id}/${slot_status} must contain fragment (${PARTICIPANT_VIEW_POLL_TOTAL_SECONDS}s max)."
  while [ "$(date +%s)" -lt "$end" ]; do
    silent_get "/flight/slots/${participant_id}/${slot_status}"
    if [ "$LAST_STATUS" = "200" ] && [[ "$LAST_BODY_RAW" == *"$needle"* ]]; then
      info "Projection ready for ${participant_id}/${slot_status}"
      return 0
    fi
    sleep "${BOOKING_POLL_INTERVAL_SECONDS}"
  done
  return 1
}

# Polls GET /flight/bookings/{id} until terminal. 0=CONFIRMED, 1=CANCELED, 2=timeout, 3=GET error.
poll_booking_workflow_terminal() {
  local booking_id="$1"
  local end=$(( $(date +%s) + BOOKING_POLL_TOTAL_SECONDS ))
  while [ "$(date +%s)" -lt "$end" ]; do
    request "GET" "/flight/bookings/${booking_id}" ""
    if [ "$LAST_STATUS" != "200" ]; then
      sleep "${BOOKING_POLL_INTERVAL_SECONDS}"
      continue
    fi
    if echo "$LAST_BODY_RAW" | grep -Fq '"workflowStatus":"CONFIRMED"'; then
      LAST_BOOKING_TERMINAL_OUTCOME="CONFIRMED"
      return 0
    fi
    if echo "$LAST_BODY_RAW" | grep -Fq '"workflowStatus":"CANCELED"'; then
      LAST_BOOKING_TERMINAL_OUTCOME="CANCELED"
      LAST_BOOKING_TERMINAL_BODY="$LAST_BODY_RAW"
      return 1
    fi
    sleep "${BOOKING_POLL_INTERVAL_SECONDS}"
  done
  LAST_BOOKING_TERMINAL_OUTCOME="TIMEOUT"
  return 2
}

attempt_booking_with_retries() {
  local slot_id="$1"
  local booking_body="$2"
  local label="$3"
  local req_id="$4"
  local booking_id="$5"
  local attempt=1

  while [ "$attempt" -le "$BOOKING_MAX_RETRIES" ]; do
    request "POST" "/flight/bookings/${slot_id}" "${booking_body}"

    if [ "$LAST_STATUS" = "429" ]; then
      warn "${label}: attempt ${attempt}/${BOOKING_MAX_RETRIES} hit rate-limit (429), retrying..."
      if [ "$attempt" -lt "$BOOKING_MAX_RETRIES" ]; then
        sleep_before_next_booking
      fi
      attempt=$((attempt + 1))
      continue
    fi

    if ! { [ "$LAST_STATUS" = "202" ] || [ "$LAST_STATUS" = "200" ]; }; then
      assert_status "202|200" "${label} POST submission (attempt ${attempt})" "${req_id}"
      [ -n "$req_id" ] && mark_req_fail "${req_id}"
      return 1
    fi

    if [ "$LAST_STATUS" = "200" ] && echo "$LAST_BODY_RAW" | grep -Fq '"alreadyConfirmed":true'; then
      LAST_BOOKING_TERMINAL_OUTCOME="CONFIRMED"
      [ -n "$req_id" ] && mark_req_pass "${req_id}"
      return 0
    fi

    info "${label}: POST ${LAST_STATUS}; polling GET /flight/bookings/${booking_id} (up to ${BOOKING_POLL_TOTAL_SECONDS}s)..."
    if poll_booking_workflow_terminal "${booking_id}"; then
      [ -n "$req_id" ] && mark_req_pass "${req_id}"
      return 0
    fi

    if [ "$LAST_BOOKING_TERMINAL_OUTCOME" = "CANCELED" ]; then
      fail "${label}: workflow ended in CANCELED unexpectedly (attempt ${attempt})"
      [ -n "$req_id" ] && mark_req_fail "${req_id}"
      return 1
    fi

    warn "${label}: workflow poll timeout (attempt ${attempt}); retrying POST if attempts remain..."
    if [ "$attempt" -lt "$BOOKING_MAX_RETRIES" ]; then
      sleep_before_next_booking
    fi
    attempt=$((attempt + 1))
  done

  fail "${label}: no CONFIRM after ${BOOKING_MAX_RETRIES} attempts (${LAST_BOOKING_TERMINAL_OUTCOME:-unknown})"
  [ -n "$req_id" ] && mark_req_fail "${req_id}"
  return 1
}

banner "Flight service manual flow runner (requirements)"
echo "BASE_URL: ${BASE_URL}"
echo "RUN_ID: ${RUN_ID}"
echo "SLOTS:"
echo "  FUTURE_SLOT_CONFIRM: ${FUTURE_SLOT_CONFIRM}"
echo "  FUTURE_SLOT_A: ${FUTURE_SLOT_A}"
echo "  FUTURE_SLOT_B: ${FUTURE_SLOT_B}"
echo "  FUTURE_SLOT_C: ${FUTURE_SLOT_C}"
echo "  PAST_SLOT:     ${PAST_SLOT}"
echo "  NOW_HOUR:      ${NOW_HOUR}"
echo "ENTITIES:"
echo "  student:    ${STUDENT_ID}"
echo "  aircraft:   ${AIRCRAFT_ID}"
echo "  instructor: ${INSTRUCTOR_ID}"
echo "BOOKINGS:"
echo "  CONFIRM-only: ${BOOKING_CONFIRM}"
echo "  A: ${BOOKING_A}"
echo "  B: ${BOOKING_B}"
echo "  C: ${BOOKING_C}"
echo "  unknown: ${UNKNOWN_BOOKING}"
echo "FLAGS:"
echo "  BOOKING_SLEEP_SECONDS: ${BOOKING_SLEEP_SECONDS}"
echo "  PROJECTION_WAIT_SECONDS: ${PROJECTION_WAIT_SECONDS}"
echo "  BOOKING_POLL_TOTAL_SECONDS: ${BOOKING_POLL_TOTAL_SECONDS}"
echo "  BOOKING_POLL_INTERVAL_SECONDS: ${BOOKING_POLL_INTERVAL_SECONDS}"
echo "  PARTICIPANT_VIEW_POLL_TOTAL_SECONDS: ${PARTICIPANT_VIEW_POLL_TOTAL_SECONDS}"
echo "  STRICT_MAIN_FLOW: ${STRICT_MAIN_FLOW}"
echo "  BOOKING_MAX_RETRIES: ${BOOKING_MAX_RETRIES}"
if [ "${PROJECTION_WAIT_SECONDS}" -lt 5 ] 2>/dev/null; then
  info "PROJECTION_WAIT_SECONDS=${PROJECTION_WAIT_SECONDS} is very low; main flow polls /flight/slots/available, but prefer >= 5 (default 10) if you still see flakes."
fi

banner "Pre-check: service available"
request "GET" "/flight/availability/${FUTURE_SLOT_A}" ""
assert_status "200|404" "Health API: GET availability (200 or 404 for unknown slot)"
if [ "$LAST_STATUS" != "200" ] && [ "$LAST_STATUS" != "404" ]; then
  banner "Service unavailable"
  echo "Could not reach ${BASE_URL}. HTTP status: ${LAST_STATUS}"
  exit 2
fi

banner "Confirm-only: availability -> booking -> CONFIRMED (no DELETE here)"
request "POST" "/flight/availability/${FUTURE_SLOT_CONFIRM}" "{\"participantId\":\"${STUDENT_ID}\",\"participantType\":\"student\"}"
assert_status "200" "Confirm-only: mark student available" "REQ-FLOW-03"
request "POST" "/flight/availability/${FUTURE_SLOT_CONFIRM}" "{\"participantId\":\"${AIRCRAFT_ID}\",\"participantType\":\"aircraft\"}"
assert_status "200" "Confirm-only: mark aircraft available" "REQ-FLOW-03"
request "POST" "/flight/availability/${FUTURE_SLOT_CONFIRM}" "{\"participantId\":\"${INSTRUCTOR_ID}\",\"participantType\":\"instructor\"}"
assert_status "200" "Confirm-only: mark instructor available" "REQ-FLOW-03"

wait_projection
SLOT_CONFIRM_NEEDLE="\"slotId\":\"${FUTURE_SLOT_CONFIRM}\""
CONFIRM_ONLY_VIEWS_OK="1"
if ! poll_slots_until_body_contains_needle "${STUDENT_ID}" "available" "${SLOT_CONFIRM_NEEDLE}"; then
  fail "Confirm-only: timeout waiting for student /flight/slots/.../available (raise PARTICIPANT_VIEW_POLL_TOTAL_SECONDS or PROJECTION_WAIT_SECONDS)"
  mark_req_fail "REQ-FLOW-03"
  CONFIRM_ONLY_VIEWS_OK="0"
else
  request "GET" "/flight/slots/${STUDENT_ID}/available" ""
  assert_status "200" "Confirm-only: student sees slot as available" "REQ-FLOW-03"
  assert_body_contains "${SLOT_CONFIRM_NEEDLE}" "Confirm-only: student available list contains slot" "REQ-FLOW-03"
fi

if [ "$CONFIRM_ONLY_VIEWS_OK" = "1" ]; then
  sleep_before_next_booking
  attempt_booking_with_retries \
    "${FUTURE_SLOT_CONFIRM}" \
    "{\"studentId\":\"${STUDENT_ID}\",\"aircraftId\":\"${AIRCRAFT_ID}\",\"instructorId\":\"${INSTRUCTOR_ID}\",\"bookingId\":\"${BOOKING_CONFIRM}\"}" \
    "Confirm-only booking (poll to CONFIRMED)" \
    "REQ-FLOW-03" \
    "${BOOKING_CONFIRM}"

  if [ "${LAST_BOOKING_TERMINAL_OUTCOME:-}" = "CONFIRMED" ]; then
    request "GET" "/flight/bookings/${BOOKING_CONFIRM}" ""
    assert_status "200" "Confirm-only: GET workflow after poll" "REQ-FLOW-03"
    assert_body_contains '"workflowStatus":"CONFIRMED"' "Confirm-only: workflow terminal is CONFIRMED" "REQ-FLOW-03"
    assert_body_contains '"terminal":true' "Confirm-only: workflow marked terminal" "REQ-FLOW-03"
    assert_body_contains '"confirmed":true' "Confirm-only: confirmed flag in status payload" "REQ-FLOW-03"
    wait_projection
    request "GET" "/flight/slots/${STUDENT_ID}/booked" ""
    assert_status "200" "Confirm-only: student booked view" "REQ-FLOW-03"
    assert_body_contains "\"bookingId\":\"${BOOKING_CONFIRM}\"" "Confirm-only: student sees booking as booked" "REQ-FLOW-03"
    info "REQ-FLOW-03: leaving booking ${BOOKING_CONFIRM} on slot ${FUTURE_SLOT_CONFIRM} in CONFIRMED (no cancel in this block)."
  else
    fail "Confirm-only: expected CONFIRMED (got ${LAST_BOOKING_TERMINAL_OUTCOME:-unknown})"
    mark_req_fail "REQ-FLOW-03"
  fi
fi

banner "Main flow (reference image style): availability -> booking -> cancel"
request "POST" "/flight/availability/${FUTURE_SLOT_A}" "{\"participantId\":\"${STUDENT_ID}\",\"participantType\":\"student\"}"
assert_status "200" "Mark available student" "REQ-FLOW-01"
request "POST" "/flight/availability/${FUTURE_SLOT_A}" "{\"participantId\":\"${AIRCRAFT_ID}\",\"participantType\":\"aircraft\"}"
assert_status "200" "Mark available aircraft" "REQ-FLOW-01"
request "POST" "/flight/availability/${FUTURE_SLOT_A}" "{\"participantId\":\"${INSTRUCTOR_ID}\",\"participantType\":\"instructor\"}"
assert_status "200" "Mark available instructor" "REQ-FLOW-01"

request "GET" "/flight/availability/${FUTURE_SLOT_A}" ""
assert_status "200" "GET slot A aggregate state" "REQ-FLOW-01"
assert_body_contains "\"id\":\"${STUDENT_ID}\"" "Slot A lists available student" "REQ-FLOW-01"
assert_body_contains "\"participantType\":\"STUDENT\"" "Slot A exposes STUDENT type" "REQ-FLOW-01"
assert_body_contains "\"id\":\"${AIRCRAFT_ID}\"" "Slot A lists available aircraft" "REQ-FLOW-01"
assert_body_contains "\"id\":\"${INSTRUCTOR_ID}\"" "Slot A lists available instructor" "REQ-FLOW-01"

wait_projection
SLOT_A_NEEDLE="\"slotId\":\"${FUTURE_SLOT_A}\""
MAIN_FLOW_VIEWS_OK="1"
if ! poll_slots_until_body_contains_needle "${STUDENT_ID}" "available" "${SLOT_A_NEEDLE}"; then
  fail "Main flow: timeout waiting for student /flight/slots/.../available (raise PARTICIPANT_VIEW_POLL_TOTAL_SECONDS or PROJECTION_WAIT_SECONDS)"
  mark_req_fail "REQ-FLOW-01"
  MAIN_FLOW_VIEWS_OK="0"
fi
if [ "$MAIN_FLOW_VIEWS_OK" = "1" ] && ! poll_slots_until_body_contains_needle "${AIRCRAFT_ID}" "available" "${SLOT_A_NEEDLE}"; then
  fail "Main flow: timeout waiting for aircraft /flight/slots/.../available"
  mark_req_fail "REQ-FLOW-01"
  MAIN_FLOW_VIEWS_OK="0"
fi
if [ "$MAIN_FLOW_VIEWS_OK" = "1" ]; then
  request "GET" "/flight/slots/${STUDENT_ID}/available" ""
  assert_status "200" "GET participant available slots view" "REQ-FLOW-01"
  assert_body_contains "${SLOT_A_NEEDLE}" "Student sees slot A as available" "REQ-FLOW-01"
  request "GET" "/flight/slots/${AIRCRAFT_ID}/available" ""
  assert_status "200" "GET aircraft available slots view" "REQ-FLOW-01"
  assert_body_contains "${SLOT_A_NEEDLE}" "Aircraft sees slot A as available" "REQ-FLOW-01"
  mark_req_pass "REQ-API-04"
fi

sleep_before_next_booking
attempt_booking_with_retries \
  "${FUTURE_SLOT_A}" \
  "{\"studentId\":\"${STUDENT_ID}\",\"aircraftId\":\"${AIRCRAFT_ID}\",\"instructorId\":\"${INSTRUCTOR_ID}\",\"bookingId\":\"${BOOKING_A}\"}" \
  "Create booking A" \
  "REQ-FLOW-01" \
  "${BOOKING_A}"

BOOKING_FLOW_A_OK="0"
if [ "$LAST_BOOKING_TERMINAL_OUTCOME" = "CONFIRMED" ]; then
  BOOKING_FLOW_A_OK="1"
fi

if [ "$BOOKING_FLOW_A_OK" = "1" ]; then
  mark_req_pass "REQ-BUS-02"
  wait_projection
  request "GET" "/flight/slots/${STUDENT_ID}/booked" ""
  assert_status "200" "GET participant booked slots view" "REQ-FLOW-01"
  assert_body_contains "\"bookingId\":\"${BOOKING_A}\"" "Student sees booking A as booked" "REQ-FLOW-01"
  request "GET" "/flight/slots/${INSTRUCTOR_ID}/booked" ""
  assert_status "200" "Instructor sees booked slots view" "REQ-FLOW-02"
  assert_body_contains "\"bookingId\":\"${BOOKING_A}\"" "Instructor sees booking A as booked" "REQ-FLOW-02"
  request "GET" "/flight/slots/${AIRCRAFT_ID}/booked" ""
  assert_status "200" "Aircraft sees booked slots view" "REQ-FLOW-02"
  assert_body_contains "\"bookingId\":\"${BOOKING_A}\"" "Aircraft sees booking A as booked" "REQ-FLOW-02"
  request "DELETE" "/flight/bookings/${FUTURE_SLOT_A}/${BOOKING_A}" ""
  assert_status "200" "Cancel booking A" "REQ-FLOW-01"
  wait_projection
  SLOT_CANCEL_NEEDLE="\"bookingId\":\"${BOOKING_A}\""
  STUDENT_CANCELED_VIEW_OK="1"
  if ! poll_slots_until_body_contains_needle "${STUDENT_ID}" "canceled" "${SLOT_CANCEL_NEEDLE}"; then
    fail "Timeout waiting for canceled row in student view (REQ-FLOW-01); increase PARTICIPANT_VIEW_POLL_TOTAL_SECONDS or PROJECTION_WAIT_SECONDS"
    mark_req_fail "REQ-FLOW-01"
    STUDENT_CANCELED_VIEW_OK="0"
  fi
  if [ "$STUDENT_CANCELED_VIEW_OK" = "1" ]; then
    request "GET" "/flight/slots/${STUDENT_ID}/canceled" ""
    assert_status "200" "GET participant canceled slots view" "REQ-FLOW-01"
    assert_body_contains "${SLOT_CANCEL_NEEDLE}" "Student sees booking A as canceled" "REQ-FLOW-01"
  fi
  request "GET" "/flight/slots/${INSTRUCTOR_ID}/canceled" ""
  assert_status "200" "Instructor sees canceled slots view" "REQ-FLOW-02"
  assert_body_contains "\"bookingId\":\"${BOOKING_A}\"" "Instructor sees booking A as canceled" "REQ-FLOW-02"
  request "GET" "/flight/slots/${AIRCRAFT_ID}/canceled" ""
  assert_status "200" "Aircraft sees canceled slots view" "REQ-FLOW-02"
  assert_body_contains "\"bookingId\":\"${BOOKING_A}\"" "Aircraft sees booking A as canceled" "REQ-FLOW-02"
  request "GET" "/flight/availability/${FUTURE_SLOT_A}" ""
  assert_status "200" "Slot A state after cancel" "REQ-FLOW-01"
  assert_body_not_contains "\"bookingId\":\"${BOOKING_A}\"" "Slot A aggregate has no rows for canceled booking A" "REQ-FLOW-01"
  assert_body_contains "\"available\":[]" "Slot A has no availability after cancel" "REQ-FLOW-01"
else
  if [ "$STRICT_MAIN_FLOW" = "1" ]; then
    fail "STRICT_MAIN_FLOW=1 requires booking CONFIRMED (via poll) in main flow"
    mark_req_fail "REQ-FLOW-01"
  else
    warn "Booking A not confirmed (status ${LAST_STATUS}); skipping booked/canceled checks for A."
  fi
fi

banner "Idempotency (reference image style): repeated mark/unmark + booking/cancel"
request "POST" "/flight/availability/${FUTURE_SLOT_B}" "{\"participantId\":\"${STUDENT_ID}\",\"participantType\":\"student\"}"
assert_status "200" "Mark student available on slot B (first)" "REQ-IDEM-01"
request "POST" "/flight/availability/${FUTURE_SLOT_B}" "{\"participantId\":\"${STUDENT_ID}\",\"participantType\":\"student\"}"
assert_status "200" "Mark student available on slot B again (idempotent)" "REQ-IDEM-01"

request "DELETE" "/flight/availability/${FUTURE_SLOT_B}" "{\"participantId\":\"${STUDENT_ID}\",\"participantType\":\"student\"}"
assert_status "200" "Unmark student on slot B (first)" "REQ-IDEM-01"
request "DELETE" "/flight/availability/${FUTURE_SLOT_B}" "{\"participantId\":\"${STUDENT_ID}\",\"participantType\":\"student\"}"
assert_status "200" "Unmark student on slot B again (idempotent)" "REQ-IDEM-01"

request "POST" "/flight/availability/${FUTURE_SLOT_B}" "{\"participantId\":\"${STUDENT_ID}\",\"participantType\":\"student\"}"
assert_status "200" "Re-mark student on slot B" "REQ-IDEM-01"
request "POST" "/flight/availability/${FUTURE_SLOT_B}" "{\"participantId\":\"${AIRCRAFT_ID}\",\"participantType\":\"aircraft\"}"
assert_status "200" "Mark aircraft on slot B" "REQ-IDEM-01"
request "POST" "/flight/availability/${FUTURE_SLOT_B}" "{\"participantId\":\"${INSTRUCTOR_ID}\",\"participantType\":\"instructor\"}"
assert_status "200" "Mark instructor on slot B" "REQ-IDEM-01"

sleep_before_next_booking
attempt_booking_with_retries \
  "${FUTURE_SLOT_B}" \
  "{\"studentId\":\"${STUDENT_ID}\",\"aircraftId\":\"${AIRCRAFT_ID}\",\"instructorId\":\"${INSTRUCTOR_ID}\",\"bookingId\":\"${BOOKING_B}\"}" \
  "Booking B (first submit + poll)" \
  "REQ-IDEM-02" \
  "${BOOKING_B}"

sleep_before_next_booking
request "POST" "/flight/bookings/${FUTURE_SLOT_B}" "{\"studentId\":\"${STUDENT_ID}\",\"aircraftId\":\"${AIRCRAFT_ID}\",\"instructorId\":\"${INSTRUCTOR_ID}\",\"bookingId\":\"${BOOKING_B}\"}"
assert_status "202|200|429" "Booking B POST (second idempotent call)" "REQ-IDEM-02"

if ! { [ "$LAST_STATUS" = "429" ]; }; then
  if [ "$LAST_STATUS" = "200" ] && echo "$LAST_BODY_RAW" | grep -Fq '"alreadyConfirmed":true'; then
    :
  elif [ "$LAST_STATUS" = "202" ]; then
    poll_booking_workflow_terminal "${BOOKING_B}" || true
  fi
fi

if [ "${LAST_BOOKING_TERMINAL_OUTCOME:-}" = "CONFIRMED" ] ||
   ([ "$LAST_STATUS" = "200" ] && echo "$LAST_BODY_RAW" | grep -Fq '"alreadyConfirmed":true'); then
  request "DELETE" "/flight/bookings/${FUTURE_SLOT_B}/${BOOKING_B}" ""
  assert_status "200" "Cancel booking B (first)" "REQ-IDEM-02"
  request "DELETE" "/flight/bookings/${FUTURE_SLOT_B}/${BOOKING_B}" ""
  assert_status "200" "Cancel booking B again (idempotent)" "REQ-IDEM-02"
else
  warn "Booking B not confirmed; skipping double-cancel checks for B."
fi

banner "Error paths and business rules"
request "POST" "/flight/availability/not-a-slot" "{\"participantId\":\"bad\",\"participantType\":\"student\"}"
assert_status "400" "Reject invalid slotId" "REQ-API-01"
assert_body_contains "slotId must follow format" "Error body mentions invalid slotId format" "REQ-API-01"

request "POST" "/flight/availability/${FUTURE_SLOT_A}" "{\"participantId\":\"bad\",\"participantType\":\"captain\"}"
assert_status "400" "Reject invalid participantType" "REQ-API-02"
assert_body_contains "invalid participant type" "Error body mentions invalid participant type" "REQ-API-02"

request "POST" "/flight/availability/${FUTURE_SLOT_A}" "{\"participantId\":\"\",\"participantType\":\"student\"}"
assert_status "400" "Reject empty participantId" "REQ-API-02"

request "GET" "/flight/slots/${STUDENT_ID}/unknown-status" ""
assert_status "400" "Reject invalid status on /slots" "REQ-API-03"
assert_body_contains "invalid status" "Error body mentions invalid status" "REQ-API-03"

request "POST" "/flight/bookings/${PAST_SLOT}" "{\"studentId\":\"${STUDENT_ID}\",\"aircraftId\":\"${AIRCRAFT_ID}\",\"instructorId\":\"${INSTRUCTOR_ID}\",\"bookingId\":\"booking-past-${RUN_ID}\"}"
assert_status "400" "Reject booking on past slot" "REQ-BUS-01"
assert_body_contains "slot must be in the future" "Error body mentions future slot requirement" "REQ-BUS-01"

sleep_before_next_booking
NO_AVAIL_BID="booking-no-availability-${RUN_ID}"
request "POST" "/flight/bookings/${FUTURE_SLOT_C}" "{\"studentId\":\"missing-student-${RUN_ID}\",\"aircraftId\":\"missing-aircraft-${RUN_ID}\",\"instructorId\":\"missing-instructor-${RUN_ID}\",\"bookingId\":\"${NO_AVAIL_BID}\"}"
assert_status "202|429" "Submit booking with no availability (async)" "REQ-BUS-02"
NO_AVAIL_OK="0"
if [ "$LAST_STATUS" = "202" ]; then
  if poll_booking_workflow_terminal "${NO_AVAIL_BID}"; then
    fail "booking without availability must not reach CONFIRMED" ""
    mark_req_fail "REQ-BUS-02"
  else
    if [ "${LAST_BOOKING_TERMINAL_OUTCOME}" = "CANCELED" ]; then
      NO_AVAIL_OK="1"
    fi
  fi
elif [ "$LAST_STATUS" = "429" ]; then
  warn "REQ-BUS-02 skipped due to 429"
fi
if [ "${NO_AVAIL_OK}" = "1" ]; then
  LAST_BODY_RAW="${LAST_BOOKING_TERMINAL_BODY}"
  assert_body_contains '"workflowStatus":"CANCELED"' "Workflow canceled without availability" "REQ-BUS-02"
elif [ "$LAST_STATUS" = "429" ]; then
  :
else
  fail "booking without availability: expected CANCELED (got ${LAST_BOOKING_TERMINAL_OUTCOME:-na})"
  mark_req_fail "REQ-BUS-02"
fi

request "DELETE" "/flight/bookings/${FUTURE_SLOT_A}/${UNKNOWN_BOOKING}" ""
assert_status "404" "Cancel unknown booking" "REQ-BUS-03"
assert_body_contains "Reservation not found" "Cancel unknown booking error body" "REQ-BUS-03"

request "POST" "/flight/availability/${FUTURE_SLOT_C}" "{\"participantId\":\"${STUDENT_ID}\",\"participantType\":\"student\"}"
assert_status "200" "Setup slot mismatch: mark student on C"
request "POST" "/flight/availability/${FUTURE_SLOT_C}" "{\"participantId\":\"${AIRCRAFT_ID}\",\"participantType\":\"aircraft\"}"
assert_status "200" "Setup slot mismatch: mark aircraft on C"
request "POST" "/flight/availability/${FUTURE_SLOT_C}" "{\"participantId\":\"${INSTRUCTOR_ID}\",\"participantType\":\"instructor\"}"
assert_status "200" "Setup slot mismatch: mark instructor on C"

sleep_before_next_booking
attempt_booking_with_retries \
  "${FUTURE_SLOT_C}" \
  "{\"studentId\":\"${STUDENT_ID}\",\"aircraftId\":\"${AIRCRAFT_ID}\",\"instructorId\":\"${INSTRUCTOR_ID}\",\"bookingId\":\"${BOOKING_C}\"}" \
  "Create booking C (async + poll)" \
  "" \
  "${BOOKING_C}"

if [ "${LAST_BOOKING_TERMINAL_OUTCOME:-}" = "CONFIRMED" ]; then
  request "DELETE" "/flight/bookings/${FUTURE_SLOT_B}/${BOOKING_C}" ""
  assert_status "400" "Cancel with slotId that does not match bookingId" "REQ-BUS-04"
  assert_body_contains "does not belong" "Slot mismatch error body" "REQ-BUS-04"
  request "DELETE" "/flight/bookings/${FUTURE_SLOT_C}/${BOOKING_C}" ""
  assert_status "200" "Cleanup booking C"
else
  warn "Booking C not CONFIRMED (${LAST_BOOKING_TERMINAL_OUTCOME:-na}); skipping slot mismatch check."
fi

banner "Minimum HTTP endpoint coverage"
request "GET" "/flight/bookings/booking-smoke-absent-${RUN_ID}" ""
assert_status "404" "GET /flight/bookings/{bookingId} missing workflow" "REQ-API-04"

request "POST" "/flight/availability/${FUTURE_SLOT_B}" "{\"participantId\":\"${INSTRUCTOR_ID}\",\"participantType\":\"instructor\"}"
assert_status "200" "POST /flight/availability/{slotId}" "REQ-API-04"
request "GET" "/flight/availability/${FUTURE_SLOT_B}" ""
assert_status "200" "GET /flight/availability/{slotId}" "REQ-API-04"
request "DELETE" "/flight/availability/${FUTURE_SLOT_B}" "{\"participantId\":\"${INSTRUCTOR_ID}\",\"participantType\":\"instructor\"}"
assert_status "200" "DELETE /flight/availability/{slotId}" "REQ-API-04"
request "GET" "/flight/slots/${INSTRUCTOR_ID}/available" ""
assert_status "200" "GET /flight/slots/{participantId}/{status}" "REQ-API-04"
assert_body_not_contains "\"slotId\":\"${FUTURE_SLOT_B}\"" "Unmarked slot B must not appear available for instructor" "REQ-API-04"

banner "Requirement compliance summary"
for req_id in "${!REQ_DESC[@]}"; do
  p="${REQ_PASS[$req_id]:-0}"
  f="${REQ_FAIL[$req_id]:-0}"
  if [ "$f" -gt 0 ]; then
    echo "${C_RED}${req_id}${C_RESET} - FAIL - ${REQ_DESC[$req_id]} (pass=${p}, fail=${f})"
  elif [ "$p" -gt 0 ]; then
    echo "${C_GREEN}${req_id}${C_RESET} - PASS - ${REQ_DESC[$req_id]} (pass=${p})"
  else
    echo "${C_YELLOW}${req_id}${C_RESET} - NOT EXECUTED - ${REQ_DESC[$req_id]}"
  fi
done

banner "Run summary"
echo "Total checks : ${CHECKS_TOTAL}"
echo "Passed       : ${CHECKS_PASSED}"
echo "Failed       : ${CHECKS_FAILED}"
echo "Warnings     : ${CHECKS_WARN}"

if [ "$CHECKS_FAILED" -eq 0 ]; then
  echo "${C_GREEN}${C_BOLD}Final result: PASS${C_RESET}"
  exit 0
else
  echo "${C_RED}${C_BOLD}Final result: FAIL${C_RESET}"
  exit 1
fi
