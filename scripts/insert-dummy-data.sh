#!/usr/bin/env bash
set -euo pipefail

ROWS="${1:-1200000}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

if ! docker compose exec -T postgresql pg_isready -U postgres >/dev/null 2>&1; then
  echo "Postgres is not running. Start the stack first:"
  echo "  docker compose up -d postgresql"
  exit 1
fi

echo "Applying schema updates..."
docker compose exec -T postgresql psql -U postgres -d billing -f - < scripts/migrate-schema.sql
echo "Inserting ${ROWS} billing transactions (plus vendors, customers, and GL rows)..."
docker compose exec -T postgresql psql -U postgres -d billing -v rows="${ROWS}" -f - < scripts/insert-dummy-data.sql
echo "Done."
