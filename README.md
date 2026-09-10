# Billing Reconciliation

Billing reconciliation orchestrated by **Temporal**. Transactions arrive as **CSV files** on SFTP, are validated, enriched, and priced in **Java activities**, matched to a companion general-ledger file, and held for **COMPENSATE** when amounts disagree.

There is no daily schedule and no billing database. A long-running parent workflow waits for file signals. Files are the source of input and of intermediate results. One Spring Boot process hosts the REST API and the worker.

The designed demonstration scale is **1.2 million** rows as **12 parallel child workflows** of 100,000 transactions. Local Compose defaults use `batch-size` **10000** (override with `BILLING_BATCH_SIZE`).

| Component | Detail |
| --- | --- |
| Language / runtime | Java 17, Spring Boot 3.3.5 |
| Orchestration | Temporal Java SDK 1.30.1 (local server or Temporal Cloud) |
| Input | CSV + SHA-256 sidecar on SFTP (or a local directory in tests) |
| Task queue | `billing-reconciliation-queue` |
| Coordinator workflow id | `billing-reconciliation-coordinator` |

## Architecture

SFTP login (Docker `atmoz/sftp`) is defined in [`docker/sftp/users.conf`](docker/sftp/users.conf): user `billing`, password `billing`, directories `home` and `destination`, port **2222**.

```
sftp/                          # Docker SFTP mount in this repo
  home/                        # drop (bind-mounted to /home/billing/home)
    billing-demo.csv
    billing-demo.csv.sha256
    gl-demo.csv
    gl-demo.csv.sha256
  destination/                 # API copy + work (bind-mounted to /home/billing/destination)
    billing-demo.csv            # copy of the selected drop file
    work/{sha256}/             # slices and processing copies (identity = billing SHA-256)
    outbound/{sha256}/         # reports
    reference/                 # vendors.csv, customers.csv
```

Checksum files use GNU `sha256sum` format (`<hex>  filename`). Integrity is validated against the **producer's `.sha256` sidecar** (an independent reference), or an `expectedSha256` on the signal — never a hash the API recomputes from the same bytes. The GL companion is `gl-` + the billing stem when `glFileName` is omitted.

```
     POST /files/available          POST /files/retry
     API copies home → destination (sync) OR starts the copy in the background
     and signals at the same time (async → locate retries for large files)
                 \                     /
                  v                   v
            BillingReconciliationWorkflow   (long-running parent, no schedule)
                  |
                  |  activity: locateAndValidate (SHA-256 vs sidecar); run identity = checksum
                  |     THROWS on failure → activity is RED in the UI:
                  |       "not found yet" retries (per file-validation policy) — durability
                  |       "present but wrong" fails fast (non-retryable)
                  |     parent CATCHES the failure → WAITING_FOR_CORRECTION (stays up, next files continue)
                  |     ok → promote to destination/work/{sha256}/ → slice → children
                  v
            BatchReconciliationWorkflow × N
                  |
                  v
            sftp/destination/work/{sha256}/  and  outbound/{sha256}/ reports
```

| Port | Service |
| --- | --- |
| 8080 | Application REST API and worker |
| 8088 | Temporal UI |
| 7233 | Temporal gRPC |
| 2222 | SFTP (`billing` / `billing`, directories `home` and `destination`) |

Temporal uses its own Postgres for workflow history. Billing data stays in CSV files.

## Workflow design

### Parent: `BillingReconciliationWorkflow`

Starts **once** (`billing-reconciliation-coordinator`) and stays running. No Temporal Schedule.

1. **Signal 1 — `fileAvailable`:** the API moves the named file from **sftp/home** to **sftp/destination** and notifies the parent. In **async** mode (default: `billing.files.async-copy=true`) the copy runs in the background and the signal is sent concurrently, so `locateAndValidate` retries until the file lands; set `"async": false` (or the config) for a synchronous copy that is present before the signal. The run id is the billing file **SHA-256**, not the filename.
2. The `locateAndValidate` activity finds the file in destination and SHA-256-validates it against the producer sidecar. It **throws on failure** (red in the UI): "not found yet" is **retryable** (a large file still landing is retried, per `billing.file-validation.max-retry-attempts`, default 3), "present but wrong" (hash mismatch) is **non-retryable**.
3. On success it promotes to `destination/work/{sha256}/`, slices, and starts child workflows.
4. On failure the parent **catches the activity failure**, parks the file in **WAITING_FOR_CORRECTION**, and keeps processing other files (it never terminates).
5. **Signal 2 — `retryCorrectedFile`:** the API copies the corrected drop again; the parent re-runs `locateAndValidate` (which retries internally).
6. When a file’s children finish, the parent writes reports under `destination/outbound/{sha256}/` and waits for the next file.

**Continue-As-New** is driven by Temporal's `isContinueAsNewSuggested()` (covers history size **and** event count). `billing.workflow.continue-as-new-history-bytes` is an optional byte override (**0 = off**, capped at **50MB**). CAN only fires when the parent is idle; pending files, waiting-for-correction state, and recent results are carried forward under the same workflow id.

### Child: `BatchReconciliationWorkflow`

Children operate on file slices only. Discrepancy resolution is **COMPENSATE only** (align billing CSV amounts to the GL file).

```
  VALIDATE_SCHEMA
  HANDLE_VALIDATION_ERRORS
  FETCH_VENDOR_DATA            →  HTTP /api/vendors/bulk     (fallback: reference/vendors.csv)
  FETCH_CUSTOMER_DATA          →  HTTP /api/customers/bulk   (fallback: reference/customers.csv)
  ENRICH_RECORDS
  APPLY_BILLING_RULES
  CALCULATE_ADJUSTMENTS
  APPLY_PENALTIES
  QUERY_GL
  MATCH_TRANSACTIONS
  IDENTIFY_DISCREPANCIES
            │
            ├── no mismatches ────────────────────────────► COMPLETED
            ├── mismatches and round ≥ 10 ────────────────► COMPLETED_WITH_UNRESOLVED
            └── mismatches and round < 10
                      WAITING_FOR_SIGNAL
                            │
                            │  COMPENSATE  →  write GL amounts into the batch CSV, then Continue-As-New
                            ▼
                      round + 1, same workflow id, back to VALIDATE_SCHEMA
```

## Getting started

Linux / macOS:

```bash
mkdir -p sftp/home sftp/destination
docker compose up -d --build
./scripts/demo.sh quickstart 5000
```

Windows PowerShell 5.1:

```powershell
New-Item -ItemType Directory -Force -Path sftp\home, sftp\destination | Out-Null
docker compose up -d --build
.\scripts\demo.ps1 quickstart 5000
```

If ExecutionPolicy blocks `.ps1` files:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\demo.ps1 quickstart 5000
```

The coordinator auto-starts when the app boots (`billing.coordinator.auto-start: true`). `POST /start` and demo `start` are idempotent if it is already running.

SFTP credentials: user `billing`, password `billing`, port **2222**. Host `./sftp/home` and `./sftp/destination` are bind-mounted into the container. `POST /files/available` **copies** the named file from `home` to `destination` in the API, then signals the parent. Temporal identifies the run by the file **SHA-256**.

Manual curl (after the stack is up and a CSV is in `sftp/home`):

```bash
curl -X POST http://localhost:8080/api/reconciliation/files/available \
  -H 'Content-Type: application/json' \
  -d '{"fileName":"billing-demo.csv"}'
```

Local JVM talking to Docker SFTP:

```bash
docker compose up -d sftp temporal temporal-ui
BILLING_FILES_MODE=sftp \
SFTP_HOST=localhost \
SFTP_PORT=2222 \
SFTP_USER=billing \
SFTP_PASSWORD=billing \
SFTP_HOME_DIR=home \
SFTP_DESTINATION_DIR=destination \
BILLING_SFTP_ROOT=./sftp \
mvn spring-boot:run
```

## Running a reconciliation

```bash
APP=http://localhost:8080/api/reconciliation
WF=billing-reconciliation-coordinator

curl -X POST $APP/start
curl -X POST $APP/files/available \
  -H 'Content-Type: application/json' \
  -d '{"fileName":"billing-demo.csv"}'

curl $APP/$WF/progress
# Child ids are {coordinatorId}-{fileId}-{runShort}-batch-{n}

curl -X POST $APP/$WF/resolve \
  -H 'Content-Type: application/json' \
  -d '{"decision":"COMPENSATE"}'

# After a failed hash check, replace the CSV + .sha256 in sftp/home, then:
curl -X POST $APP/files/retry \
  -H 'Content-Type: application/json' \
  -d '{"fileName":"billing-demo.csv"}'

curl $APP/files/{sha256}/result
```

## Discrepancy resolution

| Request | Scope | Behavior |
| --- | --- | --- |
| `{"decision":"COMPENSATE"}` | Entire batch | Align every flagged billing amount to the GL file, then Continue-As-New. |
| `{"txnId":"X","decision":"COMPENSATE"}` | One id | Compensate `X` only. |
| Same body on the parent `/resolve` | Fan-out | COMPENSATE every child still waiting. |

Invalid files are fixed by replacing the CSV on SFTP and sending Signal 2 (`POST /files/retry`).

## Configuration

Settings live in [`application.yaml`](src/main/resources/application.yaml) and are bound by [`BillingProperties`](src/main/java/com/billing/reconciliation/config/BillingProperties.java).

| Area | Defaults |
| --- | --- |
| Batching | `batch-size` 10000 (set `BILLING_BATCH_SIZE=100000` for the 1.2M-row scale), `max-parallel-batches` 12 |
| Files | `sftp/home` (drop) → `sftp/destination` (copy + work); mode `sftp` or `local`; CSV + `.sha256` |
| Copy mode | `async-copy` **true (default)** = background copy + concurrent signal → locate retries; false = copy then signal; `copy-delay-seconds` 0 (demo) |
| File retry | `max-retry-attempts` 3, `retry-interval` 5s — drives the `locateAndValidate` activity retry (visible red attempts) |
| Continue-As-New | `isContinueAsNewSuggested()` primary; `continue-as-new-history-bytes` **0 = off** (optional byte override, cap 50MB) |
| Match | Amount tolerance `0.01`; max resolution rounds `10` |
| Timeouts | Parent and child execution unbounded (`0s`) |

### Temporal connection

| Variable | Local | Temporal Cloud |
| --- | --- | --- |
| `TEMPORAL_TARGET` | `127.0.0.1:7233` (Compose app uses `temporal:7233`) | `<ns>.<accountId>.tmprl.cloud:7233` |
| `TEMPORAL_NAMESPACE` | `default` | `<namespace>.<accountId>` |
| `TEMPORAL_ENABLE_HTTPS` / `TEMPORAL_TLS` | `false` | `true` |
| `TEMPORAL_API_KEY` | empty | Cloud API key |

## HTTP API

Base path: `/api/reconciliation`.

| Method | Path | Purpose |
| --- | --- | --- |
| POST | `/start` | Start the long-running coordinator if it is not running |
| POST | `/files/available` | Copy `sftp/home` → `sftp/destination` (API), then Signal 1. Run id = sha256 |
| POST | `/files/retry` | Copy again, then Signal 2 — retry a corrected file |
| GET | `/{workflowId}/progress` | Coordinator state, waiting files, child ids |
| GET | `/{workflowId}/result` | Last completed file summary |
| GET | `/files/{fileId}/result` | Persisted outbound result JSON (`fileId` is the SHA-256) |
| POST | `/{workflowId}/resolve` | Fan COMPENSATE to waiting children |
| GET | `/batches/{childWorkflowId}/step` | Child current step |
| GET | `/batches/{childWorkflowId}/problems` | Child discrepancy ids |
| POST | `/batches/{childWorkflowId}/resolve` | COMPENSATE a batch or a single `txnId` |
| POST | `/api/vendors/bulk`, `/api/customers/bulk` | Mock enrichment from reference CSVs |
| GET | `/actuator/health` | Health |

## Sample data

```bash
./scripts/generate-sample-files.sh            # prompts for row count (default 1,200,000)
./scripts/generate-sample-files.sh 24000      # generate 24,000 rows as a new file
./scripts/demo.sh data 5000                   # same, via the demo CLI
```

Windows:

```powershell
.\scripts\demo.ps1 data 5000
```

Each generate creates a **new** pair in `sftp/home` (`billing-demo.csv`, then `billing-demo-2.csv`, `billing-demo-3.csv`, …). Existing files are never overwritten.

Seeded anomalies:

- General-ledger mismatches on `id % 10000 = 1` (GL amount + 417)
- Invalid rows with `amount = 0` on every 5,000th transaction

Working copies land under `sftp/destination/work/{sha256}/` after the API copies the file from `sftp/home`. Reports land under `sftp/destination/outbound/{sha256}/`.

## Demo script

[`scripts/demo.sh`](scripts/demo.sh) (Linux/macOS) and [`scripts/demo.ps1`](scripts/demo.ps1) (Windows PowerShell 5.1) expose the same commands.

| Command | What it does |
| --- | --- |
| *(no args)* | Interactive menu |
| `up` | Start SFTP + app + coordinator |
| `start` | Start the long-running coordinator (idempotent) |
| `data [rows]` | Generate billing + GL CSV + checksums into `sftp/home` |
| `files` | List billing CSVs in `sftp/home` |
| `process [file]` | Copy home → destination in the API, then Signal 1 |
| `status` | Compose containers + coordinator progress |
| `resolve [all\|batch\|txn]` | COMPENSATE waiting children, one batch, or one txn id |
| `retry [file]` | Copy again, then Signal 2 (corrected file) |
| `clear` | Delete every file in `sftp/home` and `sftp/destination` |
| `fail [name]` | Signal a missing/typed filename (async) → `locateAndValidate` fails **red** and retries; the coordinator continues (failure demo) |
| `large [rows]` | Generate a large file and process **async** → `locateAndValidate` retries until it lands (durability demo) |
| `restart` | Restart the local JVM app (not the Docker `app` service) |
| `stop` | Stop the local app + SFTP |
| `quickstart [rows]` | `up` + generate sample + process `billing-demo.csv` |

```bash
./scripts/demo.sh
./scripts/demo.sh process billing-demo.csv
./scripts/demo.sh resolve all
./scripts/demo.sh fail typo-name.csv       # locate fails red, coordinator keeps running
./scripts/demo.sh large 800000             # durability: locate retries until the big file lands
./scripts/demo.sh quickstart 5000
```

```powershell
.\scripts\demo.ps1
.\scripts\demo.ps1 process billing-demo.csv
.\scripts\demo.ps1 resolve all
.\scripts\demo.ps1 quickstart 5000
```

The demo lists files from the SFTP home drop (`./sftp/home` → `/home/billing/home` in the container). GL companions (`gl-*.csv`) and `.sha256` sidecars are shown as readiness, not as selectable inputs. If the Compose app is already on `:8080`, `process` talks to that instead of starting a second JVM.

`restart` only stops a JVM started by the demo script. It does not run `docker compose restart app`.

## Tests

```bash
mvn test
```

| Suite | Coverage |
| --- | --- |
| [`BillingReconciliationWorkflowTest`](src/test/java/com/billing/reconciliation/workflow/BillingReconciliationWorkflowTest.java) | File signal, hash-fail then retry, COMPENSATE |
| [`FileActivitiesImplTest`](src/test/java/com/billing/reconciliation/activity/FileActivitiesImplTest.java) | API copy, destination check, run id = SHA-256 |
| [`FileHasherTest`](src/test/java/com/billing/reconciliation/file/FileHasherTest.java) | SHA-256 and sidecar parsing |
| Engine tests | Schema, enricher, billing rules, GL match, compensation |

## Source layout

Java paths are under `src/main/java/com/billing/reconciliation/` unless noted.

| Item | Path |
| --- | --- |
| Application settings | [`src/main/resources/application.yaml`](src/main/resources/application.yaml) |
| Property binding | [`config/BillingProperties.java`](src/main/java/com/billing/reconciliation/config/BillingProperties.java) |
| Coordinator auto-start | [`config/CoordinatorStarter.java`](src/main/java/com/billing/reconciliation/config/CoordinatorStarter.java) |
| Parent workflow | [`workflow/BillingReconciliationWorkflowImpl.java`](src/main/java/com/billing/reconciliation/workflow/BillingReconciliationWorkflowImpl.java) |
| Child workflow | [`workflow/BatchReconciliationWorkflowImpl.java`](src/main/java/com/billing/reconciliation/workflow/BatchReconciliationWorkflowImpl.java) |
| REST API | [`api/ReconciliationController.java`](src/main/java/com/billing/reconciliation/api/ReconciliationController.java) |
| Home → destination copy (API) | [`file/InboundFileTransfer.java`](src/main/java/com/billing/reconciliation/file/InboundFileTransfer.java) |
| File store / CSV | [`file/ReconciliationFileStore.java`](src/main/java/com/billing/reconciliation/file/ReconciliationFileStore.java) |
| SFTP home / destination | [`file/SftpInboundFiles.java`](src/main/java/com/billing/reconciliation/file/SftpInboundFiles.java), [`file/LocalInboundFiles.java`](src/main/java/com/billing/reconciliation/file/LocalInboundFiles.java) |
| File integrity activity | [`activity/FileActivitiesImpl.java`](src/main/java/com/billing/reconciliation/activity/FileActivitiesImpl.java) |
| Compose stack | [`docker-compose.yml`](docker-compose.yml) |
| SFTP users | [`docker/sftp/users.conf`](docker/sftp/users.conf) |
| Demo CLI (Linux/macOS) | [`scripts/demo.sh`](scripts/demo.sh) |
| Demo CLI (Windows PS 5.1) | [`scripts/demo.ps1`](scripts/demo.ps1) |
| Sample generator | [`scripts/generate-sample-files.py`](scripts/generate-sample-files.py) |
