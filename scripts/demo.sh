#!/usr/bin/env bash
#
# Demo controller for the file-based Billing Reconciliation Temporal app.
#
#   ./scripts/demo.sh                 # interactive menu
#   ./scripts/demo.sh start           # start the long-running coordinator (idempotent)
#   ./scripts/demo.sh files           # list billing CSVs in sftp/home
#   ./scripts/demo.sh process         # pick a file from home and process it
#   ./scripts/demo.sh process billing-demo.csv
#   ./scripts/demo.sh retry           # pick a file and send Signal 2
#   ./scripts/demo.sh clear           # delete all files in sftp/home and sftp/destination
#   ./scripts/demo.sh fail [name]     # signal a missing/typed file → validate fails red, coordinator continues
#   ./scripts/demo.sh large [rows]    # generate a large file, process async → locate retries (durability)
#   ./scripts/demo.sh data            # prompt for row count, generate into sftp/home
#   ./scripts/demo.sh data 24000      # generate that many rows into sftp/home
#   ./scripts/demo.sh status          # containers + coordinator progress
#   ./scripts/demo.sh resolve         # COMPENSATE: all / one batch / one txn id
#   ./scripts/demo.sh resolve all
#   ./scripts/demo.sh resolve batch
#   ./scripts/demo.sh resolve txn
#   ./scripts/demo.sh quickstart      # up + generate sample + process billing-demo.csv
#   ./scripts/demo.sh quickstart 5000
#
# Windows PowerShell 5.1:
#   .\scripts\demo.ps1
#   powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\demo.ps1 process
#
set -uo pipefail

APP_PORT="${APP_PORT:-8080}"
ROWS="${ROWS:-1200000}"
BATCH_SIZE="${BATCH_SIZE:-100000}"
MAX_PARALLEL="${MAX_PARALLEL:-12}"
STEM="${STEM:-billing-demo}"
TEMPORAL_TARGET="${TEMPORAL_TARGET:-127.0.0.1:7233}"
TEMPORAL_NAMESPACE="${TEMPORAL_NAMESPACE:-default}"
TEMPORAL_API_KEY="${TEMPORAL_API_KEY:-}"
TEMPORAL_ENABLE_HTTPS="${TEMPORAL_ENABLE_HTTPS:-false}"
TEMPORAL_IDENTITY="${TEMPORAL_IDENTITY:-}"
TEMPORAL_UI="${TEMPORAL_UI:-http://localhost:8088}"

ROOT="$(cd "$(dirname "$0")/.." && pwd)"; cd "$ROOT"
SFTP_ROOT="${BILLING_SFTP_ROOT:-$ROOT/sftp}"
HOME_DIR="${BILLING_HOME_DIR:-$SFTP_ROOT/home}"
DEST_DIR="${BILLING_DESTINATION_DIR:-$SFTP_ROOT/destination}"
BASE="http://localhost:${APP_PORT}/api/reconciliation"
JAR="target/billing-reconciliation-1.0.0-SNAPSHOT.jar"
STATE_DIR="${TMPDIR:-/tmp}/billing-demo"; mkdir -p "$STATE_DIR"
WF_FILE="$STATE_DIR/wf"; PID_FILE="$STATE_DIR/app.pid"; APP_LOG="$STATE_DIR/app.log"
COORDINATOR="${BILLING_COORDINATOR_ID:-billing-reconciliation-coordinator}"

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
app_up()  { curl -sf "http://localhost:${APP_PORT}/actuator/health" >/dev/null 2>&1; }
need_app() { app_up || { err "app not running on :${APP_PORT} — run 'up' or docker compose up -d"; return 1; }; }
get_wf()  { [ -f "$WF_FILE" ] && cat "$WF_FILE" || echo "$COORDINATOR"; }

gl_companion() {
    local name="$1"
    if [[ "$name" == billing-* ]]; then
        echo "gl-${name#billing-}"
    else
        echo "gl-${name}"
    fi
}

# Billing CSVs in SFTP home (skip GL companions and checksum sidecars).
list_home_csvs() {
    mkdir -p "$HOME_DIR"
    find "$HOME_DIR" -maxdepth 1 -type f -name '*.csv' ! -name 'gl-*.csv' -printf '%f\n' 2>/dev/null | sort
}

print_home_files() {
    local files i=1 f size checksum gl
    mapfile -t files < <(list_home_csvs)
    if [ ${#files[@]} -eq 0 ]; then
        warn "no billing CSV files in ${HOME_DIR}"
        echo "  generate one with:  ./scripts/demo.sh data"
        return 1
    fi
    printf "  %-4s %-32s %10s  %s\n" "#" "FILE" "SIZE" "READY"
    for f in "${files[@]}"; do
        size=$(du -h "$HOME_DIR/$f" 2>/dev/null | awk '{print $1}')
        checksum="checksum=no"
        [ -f "$HOME_DIR/${f}.sha256" ] && checksum="checksum=yes"
        gl=$(gl_companion "$f")
        if [ -f "$HOME_DIR/$gl" ]; then
            gl="gl=$gl"
        else
            gl="gl=MISSING"
        fi
        printf "  %-4s %-32s %10s  %s  %s\n" "$i)" "$f" "$size" "$checksum" "$gl"
        i=$((i + 1))
    done
    return 0
}

# Sets SELECTED_FILE. Uses $1 if given, otherwise prompts when several files exist.
pick_home_file() {
    local requested="${1:-}" files
    mapfile -t files < <(list_home_csvs)
    if [ ${#files[@]} -eq 0 ]; then
        err "SFTP home is empty (${HOME_DIR}). Run: ./scripts/demo.sh data"
        return 1
    fi
    if [ -n "$requested" ]; then
        requested=$(basename "$requested")
        if [ ! -f "$HOME_DIR/$requested" ]; then
            err "not in SFTP home: $requested"
            print_home_files
            return 1
        fi
        SELECTED_FILE="$requested"
        return 0
    fi
    if [ ${#files[@]} -eq 1 ] && [ ! -t 0 ]; then
        SELECTED_FILE="${files[0]}"
        return 0
    fi
    say "Files in SFTP home (${HOME_DIR})"
    print_home_files || return 1
    if [ ${#files[@]} -eq 1 ]; then
        SELECTED_FILE="${files[0]}"
        ok "selected ${SELECTED_FILE}"
        return 0
    fi
    local sel
    read -rp "  file # (or name): " sel
    if [[ "$sel" =~ ^[0-9]+$ ]] && [ "$sel" -ge 1 ] && [ "$sel" -le ${#files[@]} ]; then
        SELECTED_FILE="${files[$((sel - 1))]}"
    elif [ -f "$HOME_DIR/$sel" ]; then
        SELECTED_FILE="$sel"
    else
        err "invalid selection"
        return 1
    fi
    ok "selected ${SELECTED_FILE}"
}

signal_file() {
    local signal="$1" file="$2" async="${3:-}" wf payload
    curl -s -X POST "${BASE}/start" >/dev/null
    wf="$COORDINATOR"
    echo "$wf" > "$WF_FILE"
    if [ -n "$async" ]; then
        payload="{\"fileName\":\"${file}\",\"async\":${async}}"
    else
        payload="{\"fileName\":\"${file}\"}"
    fi
    call POST "/files/${signal}" "$payload"
    echo -e "  ${C}UI:${X} ${TEMPORAL_UI}/namespaces/${TEMPORAL_NAMESPACE}/workflows/${wf}"
}

start_app() {
    if app_up; then ok "app already running on :${APP_PORT}"; return 0; fi
    [ -f "$JAR" ] || { say "building jar…"; mvn -q -DskipTests package || { err "build failed"; return 1; }; }
    mkdir -p "$HOME_DIR" "$DEST_DIR" "$DEST_DIR/reference"
    say "starting app (worker + API) on :${APP_PORT} (log → ${APP_LOG})"
    SERVER_PORT="$APP_PORT" \
      TEMPORAL_TARGET="$TEMPORAL_TARGET" TEMPORAL_NAMESPACE="$TEMPORAL_NAMESPACE" \
      TEMPORAL_API_KEY="$TEMPORAL_API_KEY" TEMPORAL_ENABLE_HTTPS="$TEMPORAL_ENABLE_HTTPS" TEMPORAL_IDENTITY="$TEMPORAL_IDENTITY" \
      VENDOR_API_URL="http://localhost:${APP_PORT}" CUSTOMER_API_URL="http://localhost:${APP_PORT}" \
      BILLING_BATCH_SIZE="$BATCH_SIZE" BILLING_MAX_PARALLEL_BATCHES="$MAX_PARALLEL" \
      BILLING_FILES_MODE=sftp BILLING_SFTP_ROOT="$SFTP_ROOT" \
      SFTP_HOST=localhost SFTP_PORT=2222 SFTP_USER=billing SFTP_PASSWORD=billing \
      SFTP_HOME_DIR=home SFTP_DESTINATION_DIR=destination \
      nohup java -jar "$JAR" > "$APP_LOG" 2>&1 &
    echo $! > "$PID_FILE"
    for _ in $(seq 1 60); do app_up && break; sleep 1; done
    app_up && ok "app UP (pid $(cat "$PID_FILE"))" || { err "app failed to start; tail ${APP_LOG}"; return 1; }
}

stop_app() {
    if [ -f "$PID_FILE" ] && kill "$(cat "$PID_FILE")" 2>/dev/null; then ok "app stopped (pid $(cat "$PID_FILE"))"; rm -f "$PID_FILE"
    elif pkill -f "$JAR" 2>/dev/null; then ok "app stopped (by jar match)"
    else warn "no local app process found (Docker app may still be running)"; fi
}

cmd_up() {
    say "Preflight"
    command -v docker >/dev/null || { err "docker not found"; return 1; }
    if (exec 3<>"/dev/tcp/${TEMPORAL_TARGET%%:*}/${TEMPORAL_TARGET##*:}") 2>/dev/null; then
        ok "Temporal reachable at ${TEMPORAL_TARGET}"; exec 3>&- 2>/dev/null
    else err "Temporal not reachable at ${TEMPORAL_TARGET} — start your Temporal stack first"; return 1; fi
    mkdir -p "$HOME_DIR" "$DEST_DIR"
    say "Docker SFTP (atmoz/sftp on :2222, user billing / billing)"
    docker compose up -d sftp
    for _ in $(seq 1 30); do
      docker compose exec -T sftp pgrep sshd >/dev/null 2>&1 && break
      sleep 1
    done
    ok "billing-sftp ready (sftp://billing@localhost:2222/home and /destination)"
    start_app
    cmd_start
}

cmd_start() {
    need_app || return 1
    say "Start long-running coordinator (${COORDINATOR})"
    local resp wf
    resp=$(curl -s -X POST "${BASE}/start")
    echo "$resp" | pp
    wf=$(echo "$resp" | jget "['workflowId']")
    [ -z "$wf" ] && wf="$COORDINATOR"
    echo "$wf" > "$WF_FILE"
    ok "coordinator ${wf} (idempotent if already running)"
    echo -e "  ${C}UI:${X} ${TEMPORAL_UI}/namespaces/${TEMPORAL_NAMESPACE}/workflows/${wf}"
}

cmd_restart() {
    say "Restarting app (worker + API)"
    stop_app; sleep 2; start_app
    cmd_start
}

ask_rows() {
    local given="${1:-}"
    if [ -n "$given" ]; then
        if [[ "$given" =~ ^[1-9][0-9]*$ ]]; then
            ROWS="$given"
            return 0
        fi
        err "rows must be a positive integer, got: $given"
        return 1
    fi
    if [ -t 0 ]; then
        local input
        read -rp "  How many rows to generate [${ROWS}]: " input
        if [ -n "${input:-}" ]; then
            if [[ "$input" =~ ^[1-9][0-9]*$ ]]; then
                ROWS="$input"
            else
                err "rows must be a positive integer, got: $input"
                return 1
            fi
        fi
    fi
}

cmd_data() {
    ask_rows "${1:-}" || return 1
    say "Generating ${ROWS} CSV transactions (new file in home; existing files are kept)"
    python3 scripts/generate-sample-files.py \
      --rows "$ROWS" \
      --home "$HOME_DIR" \
      --reference "$DEST_DIR/reference" \
      --stem "$STEM"
    chmod -R a+rX "$HOME_DIR" "$DEST_DIR"
    ok "files ready in Docker SFTP home (${HOME_DIR}) — ${ROWS} rows"
    print_home_files || true
}

cmd_files() {
    say "Available billing files in SFTP home"
    print_home_files || return 1
}

cmd_run() {
    need_app || return 1
    pick_home_file "${1:-}" || return 1
    say "Process ${SELECTED_FILE} (Signal 1 — copy home → destination, then validate)"
    signal_file "available" "$SELECTED_FILE"
}

cmd_retry() {
    need_app || return 1
    pick_home_file "${1:-}" || return 1
    say "Retry corrected file ${SELECTED_FILE} (Signal 2)"
    signal_file "retry" "$SELECTED_FILE"
}

cmd_status() {
    hr; say "Containers"
    if command -v docker >/dev/null; then
        docker compose ps --format 'table {{.Name}}\t{{.Status}}\t{{.Ports}}' 2>/dev/null \
          || docker compose ps
    else
        warn "docker not found"
    fi
    hr; say "Stack"
    app_up && ok "app UP on :${APP_PORT}" || warn "app DOWN"
    echo "  temporal: ${TEMPORAL_TARGET} (ns ${TEMPORAL_NAMESPACE})"
    echo "  sftp home: ${HOME_DIR}"
    echo "  sftp destination: ${DEST_DIR}"
    print_home_files || true
    local wf; wf=$(get_wf)
    hr; say "Coordinator ${wf}"; app_up || return 0
    call GET "/${wf}/progress"
}

cmd_resolve() {
    need_app || return 1
    local wf; wf=$(get_wf)
    local mode="${1:-}"
    if [ -z "$mode" ] && [ -t 0 ]; then
        say "COMPENSATE — choose a scope"
        cat <<OPTS
  1) all waiting children
  2) one batch
  3) one txn id (inside a batch)
OPTS
        local choice
        read -rp "  > " choice
        case "$choice" in
          1|all|a|A) mode=all ;;
          2|batch|b|B) mode=batch ;;
          3|txn|id|t|T) mode=txn ;;
          *) err "invalid choice"; return 1 ;;
        esac
    fi
    [ -z "$mode" ] && mode=all
    case "$mode" in
      all)
        say "COMPENSATE all waiting children"
        call POST "/${wf}/resolve" '{"decision":"COMPENSATE"}'
        ;;
      batch)
        local child
        child=$(pick_child_batch "${2:-}") || return 1
        say "COMPENSATE batch ${child}"
        call POST "/batches/${child}/resolve" '{"decision":"COMPENSATE"}'
        ;;
      txn)
        local child txn
        child=$(pick_child_batch "${2:-}") || return 1
        say "Problems in ${child}"
        call GET "/batches/${child}/problems"
        txn="${3:-}"
        if [ -z "$txn" ]; then
            read -rp "  txn id to COMPENSATE: " txn
        fi
        if [ -z "${txn:-}" ]; then
            err "txn id is required"
            return 1
        fi
        local payload
        payload=$(python3 -c "import json,sys; print(json.dumps({'decision':'COMPENSATE','txnId':sys.argv[1]}))" "$txn")
        say "COMPENSATE txn ${txn} in ${child}"
        call POST "/batches/${child}/resolve" "$payload"
        ;;
      *)
        err "usage: resolve [all|batch|txn]"
        return 1
        ;;
    esac
}

# Prints child workflow ids from coordinator progress; echoes the selected child id on stdout.
pick_child_batch() {
    local requested="${1:-}" wf json
    wf=$(get_wf)
    json=$(curl -s "${BASE}/${wf}/progress")
    mapfile -t CHILDREN < <(python3 -c "
import sys, json
try:
    data = json.load(sys.stdin)
except Exception:
    data = {}
for item in data.get('childWorkflowIds') or []:
    if item:
        print(item)
" <<< "$json")
    if [ -n "$requested" ]; then
        if [[ "$requested" =~ ^[0-9]+$ ]] && [ "$requested" -ge 1 ] && [ "$requested" -le ${#CHILDREN[@]} ]; then
            echo "${CHILDREN[$((requested - 1))]}"
            return 0
        fi
        echo "$requested"
        return 0
    fi
    if [ ${#CHILDREN[@]} -eq 0 ]; then
        warn "no child workflow ids on coordinator progress yet" >&2
        read -rp "  child workflow id: " requested
        if [ -z "${requested:-}" ]; then
            err "child workflow id is required"
            return 1
        fi
        echo "$requested"
        return 0
    fi
    say "Child batches for ${wf}" >&2
    local i=1 c step
    for c in "${CHILDREN[@]}"; do
        step=$(curl -s "${BASE}/batches/${c}/step" | python3 -c "import sys,json; print(json.load(sys.stdin).get('currentStep',''))" 2>/dev/null || true)
        printf "  %s) %s  %s\n" "$i" "$c" "${step:+[$step]}" >&2
        i=$((i + 1))
    done
    if [ ${#CHILDREN[@]} -eq 1 ]; then
        ok "selected ${CHILDREN[0]}" >&2
        echo "${CHILDREN[0]}"
        return 0
    fi
    local sel
    read -rp "  batch # (or child workflow id): " sel
    if [[ "$sel" =~ ^[0-9]+$ ]] && [ "$sel" -ge 1 ] && [ "$sel" -le ${#CHILDREN[@]} ]; then
        echo "${CHILDREN[$((sel - 1))]}"
        return 0
    fi
    if [ -n "${sel:-}" ]; then
        echo "$sel"
        return 0
    fi
    err "invalid batch selection"
    return 1
}

cmd_stop() {
    say "Stopping local app + SFTP"
    stop_app
    docker compose stop sftp >/dev/null 2>&1 && ok "sftp stopped" || warn "sftp not running"
}

cmd_down() {
    cmd_stop; docker compose down >/dev/null 2>&1 && ok "stack down"; rm -f "$WF_FILE"
}

cmd_quickstart() {
    cmd_up && cmd_data "${1:-}" && cmd_run "${STEM}.csv"
}

# Delete every file in SFTP home and destination (guarded against empty vars).
cmd_clear() {
    say "Clearing SFTP home + destination"
    rm -rf "${HOME_DIR:?}"/* "${DEST_DIR:?}"/* 2>/dev/null || true
    mkdir -p "$HOME_DIR" "$DEST_DIR"
    ok "cleared ${HOME_DIR} and ${DEST_DIR}"
    print_home_files || true
}

# Failure demo: signal a missing/typed filename so the locate/validate activity fails RED and retries,
# while the coordinator stays up and keeps processing other files. Uses async so the API signals even
# though the file is not present. Usage: demo.sh fail [name]
cmd_fail() {
    need_app || return 1
    local name="${1:-does-not-exist.csv}"
    say "Failure demo — signal '${name}' (async); locate/validate goes red and retries; coordinator continues"
    signal_file "available" "$name" "true"
    echo "  Open the coordinator in the UI: the validate activity shows red attempts, then the file"
    echo "  is parked in WAITING_FOR_CORRECTION while other files keep processing."
}

# Durability demo: generate a large file, then process it async so the locate/validate activity retries
# until the (still-copying) large file finishes landing. Usage: demo.sh large [rows]
cmd_large() {
    need_app || return 1
    local rows="${1:-800000}"
    say "Large-file durability demo — generate ${rows} rows, then process async (locate retries while copying)"
    python3 scripts/generate-sample-files.py \
      --rows "$rows" --home "$HOME_DIR" --reference "$DEST_DIR/reference" --stem "bigfile"
    chmod -R a+rX "$HOME_DIR" "$DEST_DIR"
    signal_file "available" "bigfile.csv" "true"
    echo "  Watch locate/validate retry in the UI until the large file finishes copying, then it processes."
    echo "  (Tip: set BILLING_FILES_COPY_DELAY on the app to force retries even for small files.)"
}

menu() {
    while :; do
        hr; echo -e "${B}  Billing Reconciliation — file demo${X}"
        echo "  sftp home=${HOME_DIR} | dest=${DEST_DIR} | app :${APP_PORT} | temporal ${TEMPORAL_TARGET}"; hr
        cat <<MENU
  1) up       — start SFTP + app + long-running coordinator
  s) start    — start long-running coordinator
  2) data     — generate CSV (asks how many rows) into home
  3) files    — list files available in SFTP home
  4) process file — select a file from home and process it
  5) status   — containers + coordinator progress
  6) resolve  — COMPENSATE all waiting, one batch, or one txn id
  7) retry    — select a corrected file (Signal 2)
  8) restart  — restart app
  9) stop     — stop app + SFTP
  c) clear    — delete all files in home + destination
  f) fail     — signal a missing/typed filename (validate fails red, coordinator continues)
  L) large    — generate a large file and process async (locate retries → durability)
  b) quickstart — start stack, generate sample, process ${STEM}.csv
  q) quit
MENU
        read -rp "  > " c; echo
        case "$c" in
          1) cmd_up ;;
          s|S) cmd_start ;;
          2) cmd_data ;;
          3) cmd_files ;;
          4) cmd_run ;;
          5) cmd_status ;;
          6) cmd_resolve ;;
          7) cmd_retry ;;
          8) cmd_restart ;;
          9) cmd_stop ;;
          c|C) cmd_clear ;;
          f|F) cmd_fail ;;
          L) cmd_large ;;
          b|B) cmd_quickstart ;;
          q|Q) break ;;
          *) warn "?" ;;
        esac
    done
}

usage() { awk 'NR==1 { next } /^#/ { sub(/^# ?/, ""); print; next } { exit }' "$0"; }

cmd="${1:-menu}"; cmd="${cmd#--}"; shift $(( $# > 0 ? 1 : 0 )) || true
case "$cmd" in
    process) cmd_run "$@" ;;
    all|quickstart) cmd_quickstart "$@" ;;
    up|start|data|files|run|status|resolve|retry|restart|stop|down|clear|fail|large) "cmd_${cmd}" "$@" ;;
    menu|"") menu ;;
    -h|help|--help) usage ;;
    *) err "unknown command: $cmd"; usage; exit 1 ;;
esac
