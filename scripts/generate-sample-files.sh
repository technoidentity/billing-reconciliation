#!/usr/bin/env bash
set -euo pipefail

DEFAULT_ROWS=1200000
STEM="${2:-billing-demo}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

if [ -n "${1:-}" ]; then
  ROWS="$1"
elif [ -t 0 ]; then
  read -rp "How many rows to generate [${DEFAULT_ROWS}]: " input
  ROWS="${input:-$DEFAULT_ROWS}"
else
  ROWS="$DEFAULT_ROWS"
fi

if ! [[ "$ROWS" =~ ^[1-9][0-9]*$ ]]; then
  echo "rows must be a positive integer, got: $ROWS" >&2
  exit 1
fi

echo "Generating ${ROWS} rows into sftp/home (new file; existing CSVs are kept)"

mkdir -p "$ROOT/sftp/home" "$ROOT/sftp/destination/reference"

python3 scripts/generate-sample-files.py \
  --rows "$ROWS" \
  --home "$ROOT/sftp/home" \
  --reference "$ROOT/sftp/destination/reference" \
  --stem "$STEM"

# SFTP user billing (uid 1001) must be able to read home and write destination.
chmod -R a+rwX "$ROOT/sftp"

if docker compose ps --status running sftp >/dev/null 2>&1; then
  echo "Files are in ./sftp/home (sftp://billing@localhost:2222/home)."
  echo "POST /files/available copies them to ./sftp/destination, then signals the coordinator."
else
  echo "Wrote ./sftp/home. Start Docker SFTP so the worker can pull them:"
  echo "  docker compose up -d sftp"
fi
echo "Signal the new billing CSV (name printed above) with:"
echo "  curl -X POST http://localhost:8080/api/reconciliation/files/available \\"
echo "    -H 'Content-Type: application/json' \\"
echo "    -d '{\"fileName\":\"<billing-file>.csv\"}'"
