#!/usr/bin/env bash
#
# Demo controller for the Billing Reconciliation Temporal app.
#
#   ./scripts/demo.sh              # interactive menu
#   ./scripts/demo.sh up           # start Postgres + app (Temporal must already be reachable)
#   ./scripts/demo.sh data         # load ROWS dummy transactions
#   ./scripts/demo.sh run          # trigger a reconciliation run
#   ./scripts/demo.sh status       # stack + current-run status
#   ./scripts/demo.sh resolve      # interactive discrepancy resolution (whole batch or single id)
#   ./scripts/demo.sh restart      # restart the app (worker + API); workflows resume durably
#   ./scripts/demo.sh stop         # stop app + Postgres
#   ./scripts/demo.sh down         # stop + wipe Postgres volume
#   ./scripts/demo.sh all          # up + data + run  (one shot)
#
# Flags (--up, --status, …) are accepted too.
#
# Config via env (defaults are production-like 1.2M / 100K / 12 workflows):
#   ROWS=1200000  BATCH_SIZE=100000  MAX_PARALLEL=12  APP_PORT=8081
#   Local Temporal (default):  TEMPORAL_TARGET=127.0.0.1:7233  TEMPORAL_NAMESPACE=default
#   Temporal Cloud:            TEMPORAL_TARGET=<ns>.<acct>.tmprl.cloud:7233 \
#                              TEMPORAL_NAMESPACE=<ns>.<acct>  TEMPORAL_API_KEY=<key>
#   Quick demo:                ROWS=24000 BATCH_SIZE=2000 ./scripts/demo.sh all
#
set -uo pipefail

# ---- config (override via env) ------------------------------------------------
APP_PORT="${APP_PORT:-8081}"
ROWS="${ROWS:-1200000}"
BATCH_SIZE="${BATCH_SIZE:-100000}"
MAX_PARALLEL="${MAX_PARALLEL:-12}"
TEMPORAL_TARGET="${TEMPORAL_TARGET:-127.0.0.1:7233}"
TEMPORAL_NAMESPACE="${TEMPORAL_NAMESPACE:-default}"
TEMPORAL_API_KEY="${TEMPORAL_API_KEY:-}"
TEMPORAL_ENABLE_HTTPS="${TEMPORAL_ENABLE_HTTPS:-false}"
TEMPORAL_IDENTITY="${TEMPORAL_IDENTITY:-}"
TEMPORAL_UI="${TEMPORAL_UI:-http://localhost:8080}"

ROOT="$(cd "$(dirname "$0")/.." && pwd)"; cd "$ROOT"
BASE="http://localhost:${APP_PORT}/api/reconciliation"
JAR="target/billing-reconciliation-1.0.0-SNAPSHOT.jar"
STATE_DIR="${TMPDIR:-/tmp}/billing-demo"; mkdir -p "$STATE_DIR"
WF_FILE="$STATE_DIR/wf"; PID_FILE="$STATE_DIR/app.pid"; APP_LOG="$STATE_DIR/app.log"

# ---- pretty helpers -----------------------------------------------------------
if [ -t 1 ]; then B="\033[1m"; G="\033[32m"; Y="\033[33m"; C="\033[36m"; R="\033[31m"; X="\033[0m"; else B=""; G=""; Y=""; C=""; R=""; X=""; fi
say()  { echo -e "${C}▶ $*${X}"; }
ok()   { echo -e "${G}✓ $*${X}"; }
warn() { echo -e "${Y}! $*${X}"; }
err()  { echo -e "${R}✗ $*${X}"; }
hr()   { echo -e "${B}────────────────────────────────────────────────────────────${X}"; }
pp()   { python3 -m json.tool 2>/dev/null || cat; }
jget() { python3 -c "import sys,json;d=json.load(sys.stdin);print(d$1)" 2>/dev/null; }
call() {
    local m="$1" p="$2" d="${3:-}"
    if [ -n "$d" ]; then echo -e "${B}\$ curl -X ${m} ${BASE}${p} -d '${d}'${X}"
        curl -s -X "$m" "${BASE}${p}" -H 'Content-Type: application/json' -d "$d" | pp
    else echo -e "${B}\$ curl -X ${m} ${BASE}${p}${X}"; curl -s -X "$m" "${BASE}${p}" | pp; fi
}
num_batches() { echo $(( (ROWS + BATCH_SIZE - 1) / BATCH_SIZE )); }
app_up()  { curl -sf "http://localhost:${APP_PORT}/actuator/health" >/dev/null 2>&1; }
need_app() { app_up || { err "app not running on :${APP_PORT} — run 'up' first"; return 1; }; }
get_wf()  { [ -f "$WF_FILE" ] && cat "$WF_FILE" || echo ""; }

WAITING=()
scan_children() {
    WAITING=(); local wf="$1" total; total=$(num_batches)
    local n step ids
    for n in $(seq 1 "$total"); do
        step=$(curl -s "${BASE}/batches/${wf}-batch-${n}/step" | jget "['currentStep']"); [ -z "$step" ] && step="(pending)"
        if [ "$step" = "WAITING_FOR_SIGNAL" ]; then
            ids=$(curl -s "${BASE}/batches/${wf}-batch-${n}/problems" | jget "['txnIds']")
            printf "  batch-%-4s %bWAITING%b  ids=%s\n" "$n" "$Y" "$X" "$ids"; WAITING+=("$n")
        else
            printf "  batch-%-4s %s\n" "$n" "$step"
        fi
    done
}

# ids of one batch, newline-separated (relies on $wf from the caller's scope)
batch_ids() { curl -s "${BASE}/batches/${wf}-batch-$1/problems" | python3 -c "import sys,json;[print(x) for x in json.load(sys.stdin).get('txnIds',[])]" 2>/dev/null; }

# prompt for a waiting batch number; echoes the choice to stdout (prompts go to stderr)
pick_batch() {
    [ ${#WAITING[@]} -eq 1 ] && { echo "${WAITING[0]}"; return; }
    echo "  waiting batches: ${WAITING[*]}" >&2
    local _b; read -rp "  batch #: " _b
    local w; for w in "${WAITING[@]}"; do [ "$w" = "$_b" ] && { echo "$_b"; return; }; done
    warn "not a waiting batch" >&2; echo ""
}

# list a batch's discrepancy ids and prompt for one; echoes the id to stdout
pick_id() {
    local arr sel i=1 x; mapfile -t arr < <(batch_ids "$1")
    [ ${#arr[@]} -eq 0 ] && { warn "no ids in batch $1" >&2; echo ""; return; }
    for x in "${arr[@]}"; do echo "    $i) $x" >&2; i=$((i+1)); done
    read -rp "  id #: " sel
    if [[ "$sel" =~ ^[0-9]+$ ]] && [ "$sel" -ge 1 ] && [ "$sel" -le ${#arr[@]} ]; then echo "${arr[$((sel-1))]}"; else warn "bad selection" >&2; echo ""; fi
}

# ---- commands -----------------------------------------------------------------
# The app is the Temporal worker AND the REST API in one JVM.
start_app() {
    if app_up; then ok "app already running on :${APP_PORT}"; return 0; fi
    [ -f "$JAR" ] || { say "building jar…"; mvn -q -DskipTests package || { err "build failed"; return 1; }; }
    say "starting app (worker + API) on :${APP_PORT} (log → ${APP_LOG})"
    SERVER_PORT="$APP_PORT" DB_HOST=localhost DB_PORT=5432 DB_NAME=billing DB_USER=billing DB_PASSWORD=billing \
      TEMPORAL_TARGET="$TEMPORAL_TARGET" TEMPORAL_NAMESPACE="$TEMPORAL_NAMESPACE" \
      TEMPORAL_API_KEY="$TEMPORAL_API_KEY" TEMPORAL_ENABLE_HTTPS="$TEMPORAL_ENABLE_HTTPS" TEMPORAL_IDENTITY="$TEMPORAL_IDENTITY" \
      VENDOR_API_URL="http://localhost:${APP_PORT}" CUSTOMER_API_URL="http://localhost:${APP_PORT}" \
      BILLING_BATCH_SIZE="$BATCH_SIZE" BILLING_MAX_PARALLEL_BATCHES="$MAX_PARALLEL" \
      nohup java -jar "$JAR" > "$APP_LOG" 2>&1 &
    echo $! > "$PID_FILE"
    for _ in $(seq 1 60); do app_up && break; sleep 1; done
    app_up && ok "app UP (pid $(cat "$PID_FILE"))" || { err "app failed to start; tail ${APP_LOG}"; return 1; }
}

stop_app() {
    if [ -f "$PID_FILE" ] && kill "$(cat "$PID_FILE")" 2>/dev/null; then ok "app stopped (pid $(cat "$PID_FILE"))"; rm -f "$PID_FILE"
    elif pkill -f "$JAR" 2>/dev/null; then ok "app stopped (by jar match)"
    else warn "no app process found"; fi
}

cmd_up() {
    say "Preflight"
    command -v docker >/dev/null || { err "docker not found"; return 1; }
    if (exec 3<>"/dev/tcp/${TEMPORAL_TARGET%%:*}/${TEMPORAL_TARGET##*:}") 2>/dev/null; then
        ok "Temporal reachable at ${TEMPORAL_TARGET}"; exec 3>&- 2>/dev/null
    else err "Temporal not reachable at ${TEMPORAL_TARGET} — start your Temporal stack first"; return 1; fi
    [ -n "$TEMPORAL_API_KEY" ] && ok "Temporal Cloud mode (API key set, TLS auto-on)"

    say "Billing Postgres"
    docker compose up -d postgresql >/dev/null 2>&1
    until docker exec billing-postgres pg_isready -U postgres >/dev/null 2>&1; do sleep 1; done
    ok "billing-postgres ready"
    start_app
}

# Restart the app (worker + API) without touching Postgres or Temporal.
# Durable workflows resume on the new worker; a parked run keeps waiting for its signal.
cmd_restart() {
    say "Restarting app (worker + API)"
    stop_app; sleep 2; start_app
}

cmd_data() {
    say "Loading ${ROWS} transactions (batch-size ${BATCH_SIZE} → $(num_batches) workflows)"
    docker exec billing-postgres pg_isready -U postgres >/dev/null 2>&1 || { err "Postgres not up — run 'up' first"; return 1; }
    ./scripts/insert-dummy-data.sh "$ROWS" 2>&1 | tail -8
}

cmd_run() {
    need_app || return 1
    say "Trigger reconciliation (manual equivalent of the 8:00 AM schedule)"
    local resp wf; resp=$(curl -s -X POST "${BASE}/start"); echo "$resp" | pp
    wf=$(echo "$resp" | jget "['workflowId']")
    [ -z "$wf" ] && { err "no workflowId (empty table? run 'data')"; return 1; }
    echo "$wf" > "$WF_FILE"; ok "parent workflow: ${wf}"
    echo -e "  ${C}UI:${X} ${TEMPORAL_UI}/namespaces/${TEMPORAL_NAMESPACE}/workflows?query=WorkflowId%20STARTS_WITH%20%22${wf}%22"
    say "waiting for children to reach validate→match…"; sleep 10; scan_children "$wf"
    echo; ok "waiting children: ${WAITING[*]:-none}"
}

cmd_status() {
    hr; say "Stack"
    app_up && ok "app UP on :${APP_PORT} (pid $( [ -f "$PID_FILE" ] && cat "$PID_FILE" || echo '?'))" || warn "app DOWN"
    docker inspect -f '{{.State.Status}}' billing-postgres >/dev/null 2>&1 \
        && ok "billing-postgres: $(docker inspect -f '{{.State.Status}}' billing-postgres)" || warn "billing-postgres: not running"
    echo "  temporal: ${TEMPORAL_TARGET} (ns ${TEMPORAL_NAMESPACE})${TEMPORAL_API_KEY:+  [cloud/api-key]}"
    local wf; wf=$(get_wf)
    [ -z "$wf" ] && { warn "no run started yet"; hr; return 0; }
    hr; say "Run ${wf}"; app_up || return 0
    call GET "/${wf}/progress"; echo; say "Children"; scan_children "$wf"
}

cmd_resolve() {
    need_app || return 1
    local wf; wf=$(get_wf); [ -z "$wf" ] && { err "no run — run 'run' first"; return 1; }
    while :; do
        hr; scan_children "$wf" >/dev/null
        say "Resolve  (workflow ${wf}; waiting: ${WAITING[*]:-none})"
        cat <<MENU
  1) COMPENSATE a whole batch        (workflow auto-aligns all ids to GL → completes)
  2) CONTINUE a whole batch          (re-check DB after you fixed it; unfixed ids stay waiting)
  3) Resolve a SINGLE id             (choose COMPENSATE or CONTINUE)
  4) Correct a txn in DB + CONTINUE  (app fixes one id via /correct, then re-check)
  5) Parent fan-out to all waiting   (choose COMPENSATE or CONTINUE)
  6) Show children + progress
  b) Back
MENU
        read -rp "  > " c; echo
        case "$c" in
          1) [ ${#WAITING[@]} -eq 0 ] && { warn "none waiting"; continue; }
             local b1; b1=$(pick_batch); [ -z "$b1" ] && continue
             call POST "/batches/${wf}-batch-${b1}/resolve" '{"decision":"COMPENSATE"}'; sleep 5 ;;
          2) [ ${#WAITING[@]} -eq 0 ] && { warn "none waiting"; continue; }
             local b2; b2=$(pick_batch); [ -z "$b2" ] && continue
             warn "fix the data in the DB first, then this re-checks the whole batch"
             call POST "/batches/${wf}-batch-${b2}/resolve" '{"decision":"CONTINUE"}'; sleep 5 ;;
          3) [ ${#WAITING[@]} -eq 0 ] && { warn "none waiting"; continue; }
             local b3 id3 dec3; b3=$(pick_batch); [ -z "$b3" ] && continue
             id3=$(pick_id "$b3"); [ -z "$id3" ] && continue
             read -rp "  decision [COMPENSATE/continue]: " dec3; [ "${dec3^^}" = "CONTINUE" ] && dec3=CONTINUE || dec3=COMPENSATE
             call POST "/batches/${wf}-batch-${b3}/resolve" "{\"txnId\":\"${id3}\",\"decision\":\"${dec3}\"}"; sleep 5 ;;
          4) [ ${#WAITING[@]} -eq 0 ] && { warn "none waiting"; continue; }
             local b4 id4; b4=$(pick_batch); [ -z "$b4" ] && continue
             id4=$(pick_id "$b4"); [ -z "$id4" ] && continue
             call GET "/txns/${id4}"
             call POST "/txns/${id4}/correct" '{}'
             call POST "/batches/${wf}-batch-${b4}/resolve" "{\"txnId\":\"${id4}\",\"decision\":\"CONTINUE\"}"; sleep 5 ;;
          5) local dec5; read -rp "  fan-out decision [COMPENSATE/continue]: " dec5
             [ "${dec5^^}" = "CONTINUE" ] && dec5=CONTINUE || dec5=COMPENSATE
             call POST "/${wf}/resolve" "{\"decision\":\"${dec5}\"}"; sleep 6 ;;
          6) scan_children "$wf"; echo; call GET "/${wf}/progress" ;;
          b|B) break ;;
          *) warn "?" ;;
        esac
    done
}

cmd_stop() {
    say "Stopping app + Postgres"
    stop_app
    docker compose stop postgresql >/dev/null 2>&1 && ok "billing-postgres stopped" || warn "postgres not running"
    warn "schedule 'daily-billing-reconciliation' stays on Temporal — remove from UI if unwanted"
}

cmd_down() {
    read -rp "$(echo -e "${Y}This wipes the billing Postgres volume. Continue? [y/N] ${X}")" a
    [ "$a" = "y" ] || { warn "cancelled"; return 0; }
    cmd_stop; docker compose down -v >/dev/null 2>&1 && ok "stack down, volume removed"; rm -f "$WF_FILE"
}

cmd_all() { cmd_up && cmd_data && cmd_run; }

menu() {
    while :; do
        hr; echo -e "${B}  Billing Reconciliation — demo${X}"
        echo "  rows=${ROWS} batch=${BATCH_SIZE} → $(num_batches) workflows | app :${APP_PORT} | temporal ${TEMPORAL_TARGET}"; hr
        cat <<MENU
  1) up       — start Postgres + app
  2) data     — load ${ROWS} rows
  3) run      — trigger reconciliation
  4) status   — stack + run status
  5) resolve  — resolve discrepancies
  6) restart  — restart app (worker + API)
  7) stop     — stop app + Postgres
  8) all      — up + data + run
  q) quit
MENU
        read -rp "  > " c; echo
        case "$c" in
          1) cmd_up ;; 2) cmd_data ;; 3) cmd_run ;; 4) cmd_status ;;
          5) cmd_resolve ;; 6) cmd_restart ;; 7) cmd_stop ;; 8) cmd_all ;; q|Q) break ;; *) warn "?" ;;
        esac
    done
}

usage() { sed -n '2,30p' "$0" | sed 's/^# \{0,1\}//'; }

# ---- dispatch -----------------------------------------------------------------
cmd="${1:-menu}"; cmd="${cmd#--}"
case "$cmd" in
    up|data|run|status|resolve|restart|stop|down|all) "cmd_${cmd}" ;;
    menu|"") menu ;;
    -h|help|--help) usage ;;
    *) err "unknown command: $1"; usage; exit 1 ;;
esac
