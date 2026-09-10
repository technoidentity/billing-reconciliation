# Billing Reconciliation Demo Guide

Runs the file-based billing reconciliation demo with `scripts/demo.sh` (Linux/macOS) or
`scripts/demo.ps1` (Windows PowerShell 5.1). Input is **CSV files on SFTP** — there is no database.
A long-running coordinator workflow waits for file signals, validates by SHA-256, fans out child
workflows per batch, and resolves discrepancies with **COMPENSATE**.

## Prerequisites

- Docker Desktop with Docker Compose (for the SFTP container and Temporal)
- Java 17 and Maven (only if the application JAR must be built)
- A reachable Temporal server (local `127.0.0.1:7233`, or Temporal Cloud via `TEMPORAL_*`)
- Python 3 (the sample-file generator)

The scripts do not start Temporal. They start the SFTP container and the Spring Boot app (REST API +
Temporal worker), then drive the coordinator over its signals.

## Configuration

Set environment variables before a command:

| Variable | Default | Effect |
|---|---:|---|
| `APP_PORT` | `8080` | Application REST/health port |
| `ROWS` | `1200000` | Rows in the generated sample CSV |
| `BATCH_SIZE` | `100000` | Transactions per child workflow (child count = `ceil(ROWS / BATCH_SIZE)`) |
| `MAX_PARALLEL` | `12` | Max child workflows processed at once |
| `TEMPORAL_TARGET` | `127.0.0.1:7233` | Temporal endpoint |
| `TEMPORAL_NAMESPACE` | `default` | Temporal namespace |
| `TEMPORAL_API_KEY` | empty | Temporal Cloud API key (TLS auto-enables) |
| `TEMPORAL_UI` | `http://localhost:8088` | Base URL for the printed UI link |
| `BILLING_FILES_ASYNC_COPY` | `true` | Background copy + concurrent signal → `locate` retries until the file lands |
| `BILLING_FILES_COPY_DELAY` | `0` | Seconds to delay the async copy (forces visible `locate` retries in a demo) |

## Recommended demo

Linux/macOS:

```bash
ROWS=24000 BATCH_SIZE=2000 ./scripts/demo.sh up
ROWS=24000 BATCH_SIZE=2000 ./scripts/demo.sh data
./scripts/demo.sh process billing-demo.csv
./scripts/demo.sh status
./scripts/demo.sh resolve all
./scripts/demo.sh stop
```

Windows PowerShell 5.1:

```powershell
$env:ROWS = "24000"; $env:BATCH_SIZE = "2000"
.\scripts\demo.ps1 up
.\scripts\demo.ps1 data
.\scripts\demo.ps1 process billing-demo.csv
.\scripts\demo.ps1 status
.\scripts\demo.ps1 resolve all
.\scripts\demo.ps1 stop
```

One-shot: `./scripts/demo.sh quickstart 5000` (or `.\scripts\demo.ps1 quickstart 5000`).

## What a run does

1. **`data`** generates `billing-demo.csv`, its `gl-demo.csv` companion, and `.sha256` sidecars in
   `sftp/home` (plus `vendors.csv` / `customers.csv` reference files). It seeds one GL mismatch
   (`TXN0000000001`) and invalid rows every 5,000th txn.
2. **`process`** copies the file `home → destination` and signals the coordinator (**Signal 1**). By
   default the copy is asynchronous and the signal is sent at the same time.
3. The coordinator runs the `locateAndValidate` activity: it computes the file SHA-256 and compares it to
   the producer sidecar. The **run identity is the checksum**, not the filename.
4. It slices the file into batches and starts one child workflow per batch. Each child validates, enriches,
   applies billing rules, and matches against the GL file.
5. A batch with a mismatch pauses at `WAITING_FOR_SIGNAL`. **`resolve`** sends COMPENSATE, which aligns the
   billing amount to the GL and Continue-As-News to re-check.
6. When a file completes, reports are written under `sftp/destination/outbound/{sha256}/`. The coordinator
   returns to listening for the next file and never terminates.

## Failure and durability demos

- **Validation failure (red activity, coordinator survives):**

  ```bash
  ./scripts/demo.sh fail typo-name.csv
  ```

  Signals a missing/mistyped filename. `locateAndValidate` fails **red** in the Temporal UI and retries
  ("not found yet" is retryable); the coordinator catches the failure, parks the file in
  `WAITING_FOR_CORRECTION`, and keeps processing other files. Drop the corrected file and run
  `./scripts/demo.sh retry <name>` (**Signal 2**).

- **Durability (locate retries until a large file lands):**

  ```bash
  ./scripts/demo.sh large 800000
  ```

  Generates a large file and processes it async, so `locateAndValidate` retries until the copy finishes.
  Set `BILLING_FILES_COPY_DELAY` to force retries for small files too.

## Command reference

| Command | Action |
|---|---|
| `up` | Start the SFTP container + app + coordinator |
| `start` | Start the long-running coordinator (idempotent) |
| `data [rows]` | Generate billing + GL CSV + checksums into `sftp/home` |
| `files` | List billing CSVs in `sftp/home` |
| `process [file]` | Copy `home → destination`, then Signal 1 |
| `retry [file]` | Copy again, then Signal 2 (corrected file) |
| `resolve [all\|batch\|txn]` | COMPENSATE all waiting children, one batch, or one txn id |
| `status` | Containers + coordinator progress |
| `clear` | Delete all files in `sftp/home` and `sftp/destination` |
| `fail [name]` | Signal a missing/typed filename (validate fails red, coordinator continues) |
| `large [rows]` | Generate a large file and process async (locate retries → durability) |
| `restart` | Restart the local app (worker + API) |
| `stop` | Stop the local app + SFTP |
| `quickstart [rows]` | `up` + generate a sample + process `billing-demo.csv` |

## SFTP and ports

| Port | Service |
|---|---|
| 8080 | Application REST API + worker |
| 8088 | Temporal UI |
| 7233 | Temporal gRPC |
| 2222 | SFTP (`billing` / `billing`; `home` and `destination` dirs, bind-mounted from `./sftp`) |

## Manual curl

```bash
APP=http://localhost:8080/api/reconciliation
curl -X POST $APP/files/available -H 'Content-Type: application/json' -d '{"fileName":"billing-demo.csv"}'
curl $APP/billing-reconciliation-coordinator/progress
curl -X POST $APP/billing-reconciliation-coordinator/resolve -H 'Content-Type: application/json' -d '{"decision":"COMPENSATE"}'
# after fixing a rejected file in sftp/home:
curl -X POST $APP/files/retry -H 'Content-Type: application/json' -d '{"fileName":"billing-demo.csv"}'
```
