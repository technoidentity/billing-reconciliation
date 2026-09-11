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

The generator never overwrites an existing drop: repeated runs produce names such as
`billing-demo-2.csv`. Use `files` and pass the displayed filename to `process` or `retry`. The
`quickstart` shortcut expects `billing-demo.csv`, and `large` expects `bigfile.csv`, so use `clear`
before repeating either shortcut.

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
5. A batch with a mismatch pauses at `WAITING_FOR_SIGNAL`. **`resolve`** sends COMPENSATE, which calculates
   the correction in memory, persists corrected billing/processed/discrepancy CSV state, and Continue-As-News
   to re-check the same batch.
6. When a file completes, reports are written under `sftp/destination/outbound/{sha256}/`. The coordinator
   returns to listening for the next file and never terminates.

## Locate and validate modes

`LocateAndValidate` runs in the parent coordinator before any child workflows are started. The demo
supports both file-copy modes through the `process` command (menu option **4**) and the configured
copy mode:

- **Synchronous copy:** when `$env:BILLING_FILES_ASYNC_COPY = "false"` is configured before the app
  starts, option **4** calls `POST /files/available` without an `async` override. The API copies the
  billing CSV, sidecar, and GL companion from `sftp/home` to `sftp/destination` first, then signals
  the coordinator. `locateAndValidate` finds the file on its first activity attempt.
- **Asynchronous copy:** use the `large` command (menu option **L**) or set
  `$env:BILLING_FILES_ASYNC_COPY = "true"` before starting the app. The API signals the coordinator
  while the copy runs in the background. The first `locateAndValidate` attempt can fail with
  `FileNotFound`, and Temporal retries the activity until the file lands successfully. This simulates
  durable retry/auto-healing for a file that is not available yet. `BILLING_FILES_COPY_DELAY` can be set
  to make the retry visible even for a small file.

For an explicit synchronous demonstration, set `$env:BILLING_FILES_ASYNC_COPY = "false"` before
`up`, generate a file with option **2**, and process it with option **4**. For the asynchronous
demonstration, set it to `"true"` before `up`, or use option **L**, which explicitly sends
`async=true`. The application default is asynchronous.

## Failure and durability demos

- **Validation failure (red activity, coordinator survives):**

  ```bash
  ./scripts/demo.sh fail typo-name.csv
  ```

  Signals a missing/mistyped filename. `locateAndValidate` fails **red** in the Temporal UI and retries
  ("not found yet" is retryable); the coordinator catches the failure, parks the file in
  `WAITING_FOR_CORRECTION`, and keeps processing other files. Replace the corrected CSV and sidecar in
  `sftp/home`, then run `./scripts/demo.sh retry <name>` (**Signal 2**). The same parent workflow
  re-validates and reprocesses the corrected file.

  Windows menu flow:

  1. Use option **2 (data)** to generate the normal billing CSV, GL companion, and SHA-256 sidecars.
  2. Edit either the billing CSV or its `.sha256` sidecar in `sftp/home` so the content and expected
     hash no longer match.
  3. Use option **4 (process file)** to copy the intentionally invalid drop and send Signal 1.
  4. In the Temporal UI, confirm that `locateAndValidate` fails and the parent reaches
     `WAITING_FOR_CORRECTION`.
  5. Correct the CSV or regenerate its matching `.sha256` sidecar in `sftp/home`.
  6. Use option **7 (retry)** and select the same filename. This copies the corrected drop and sends
     Signal 2; the existing coordinator re-validates and reprocesses the corrected file in the same
     workflow.

  Option **4** is the normal Signal-1 file submission path. Option **7** is required for a file that
  has already been parked in `WAITING_FOR_CORRECTION`; submitting it again with option 4 does not
  acknowledge the parked correction state.

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

## Step-by-step command walkthrough

The commands below correspond to the functions and menu entries in `scripts/demo.ps1`. The script
talks to the same fixed coordinator workflow, `billing-reconciliation-coordinator`; it does not
create a new parent workflow for each file.

### Option 1 / `up` — start the demo stack

1. Confirm Docker and Temporal are reachable.
2. Start the SFTP container with `docker compose up -d sftp`.
3. Start the local Spring Boot JAR, if the app is not already running.
4. Call `POST /start` to start the long-running coordinator idempotently.

The coordinator begins in `LISTENING` and waits for `fileAvailable` or `retryCorrectedFile` signals.
Temporal itself is not started by the script; start it separately or use the Compose services first.

### Option 2 / `data [rows]` — generate an input file

1. Generate a billing CSV, matching GL CSV, and SHA-256 sidecars in `sftp/home`.
2. Generate vendor/customer reference CSVs under `sftp/destination/reference`.
3. Seed GL discrepancies and invalid billing rows for the compensation and validation demos.

Existing files are preserved. If the stem already exists, the generator creates a suffix such as
`billing-demo-2.csv`; use option 3 to identify the actual filename.

### Option 3 / `files` — inspect available drops

Lists billing CSVs in `sftp/home`, along with whether the billing sidecar and GL companion are present.
GL files and sidecars are readiness artifacts and are not selectable as billing inputs.

### Option 4 / `process [file]` — submit a file with Signal 1

1. Select a billing CSV from `sftp/home`.
2. Call `POST /start` to ensure the coordinator exists.
3. Call `POST /files/available` with the filename.
4. The API copies the billing file, sidecar, and GL companion from `home` to `destination`, then
   signals the coordinator according to `billing.files.async-copy` in `application.yml`.

With `async-copy: false`, the copy completes before Signal 1, so `locateAndValidate` normally succeeds
on its first attempt. With `async-copy: true`, the signal and copy run concurrently; the activity can
fail with `FileNotFound` on an early attempt and succeed on a later Temporal retry. After validation,
the parent promotes the file, slices it, and starts the batch child workflows.

### Option 5 / `status` — inspect workflow progress

Displays Docker container status, app health, SFTP paths, available files, and
`GET /{coordinatorId}/progress`. The progress response shows the current parent step, current file,
batch progress, child workflow IDs, pending files, files waiting for correction, and the last result.

### Option 6 / `resolve [all|batch|txn]` — compensate discrepancies

- `resolve all`: calls the parent `/{workflowId}/resolve`; the parent fans `COMPENSATE` to every
  pending child.
- `resolve batch`: calls `/batches/{childWorkflowId}/resolve` for one child.
- `resolve txn`: first reads `/batches/{childWorkflowId}/problems`, then sends a selected `txnId`.

The child loads the affected file rows and GL amounts, calculates the correction in memory, writes the
corrected billing/processed/discrepancy CSV state, and Continue-As-News to re-read and re-run the batch.
The controller accepts only `COMPENSATE`; no database correction is performed.

### Option 7 / `retry [file]` — submit a corrected file with Signal 2

Select the corrected file in `sftp/home`. The script calls `POST /files/retry`; the API synchronously
copies the corrected drop by default and queues `retryCorrectedFile` on the existing coordinator. The
parent re-runs validation and, after success, starts the corrected file's child workflows in that same
coordinator workflow.

This is the intended command when a validation failure has parked a file in
`WAITING_FOR_CORRECTION`.

### Option 8 / `restart` — restart the local app

Stops only a JVM started by the script, starts it again, and calls `POST /start`. It does not restart
the Docker `app` service or terminate the Temporal coordinator; the coordinator continues in Temporal.

### Option 9 / `stop` — stop local demo processes

Stops the locally started JVM and the SFTP container. It does not delete workflow history or billing
files.

### `down` — stop and remove the Compose stack

Runs the stop flow, then `docker compose down`. The script also removes its local workflow-ID helper
file. Temporal workflow history in the named Temporal Postgres volume is not deleted by this command.

### `clear` — delete SFTP demo files

Deletes the contents of `sftp/home` and `sftp/destination`, recreates the directories, and shows that
the home drop is empty. This is useful before `quickstart` or `large`, because generated filenames are
suffixes when files already exist. It does not reset Temporal workflow history.

### `fail [name]` — demonstrate a missing-file validation failure

Sends Signal 1 for a missing or mistyped filename using asynchronous mode. The copy cannot find the
file, while `locateAndValidate` retries `FileNotFound`; after retries are exhausted, the parent catches
the activity failure and enters `WAITING_FOR_CORRECTION` without terminating.

### `large [rows]` — demonstrate durable asynchronous retries

Generates a large `bigfile` drop and explicitly submits `async=true`. The coordinator can receive the
signal before the copy finishes, so `locateAndValidate` shows an initial failed attempt and then
successfully retries after the file lands.

### `quickstart [rows]` — run the basic happy path

Runs `up`, `data`, and `process billing-demo.csv`. Use `clear` first if the generator has already
created a suffixed file such as `billing-demo-2.csv`.

## Complete scenario: happy path

### Synchronous happy path

1. Set `$env:BILLING_FILES_ASYNC_COPY = "false"` before starting the app.
2. Run option **1 (`up`)**.
3. Run option **2 (`data 24000`)**.
4. Run option **3 (`files`)** and note the billing filename.
5. Run option **4 (`process <filename>`)**.
6. The API copies the files first, then sends Signal 1.
7. The parent validates successfully on the first activity attempt.
8. The parent slices the file and starts child workflows.
9. Run option **5 (`status`)** to observe `PROCESSING_BATCHES` and the child IDs.
10. If a child reaches `WAITING_FOR_SIGNAL`, run option **6 (`resolve all`)**. The child compensates
    and Continue-As-News until the batch is reconciled or the resolution-round limit is reached.
11. When complete, results and reports are written under `sftp/destination/outbound/{sha256}/`.

### Asynchronous happy path / durable retry

1. Set `$env:BILLING_FILES_ASYNC_COPY = "true"` before starting the app, or use option **L (`large`)**,
   which explicitly sends `async=true`.
2. Run options **1**, **2**, **3**, and **4** as above.
3. The API signals the parent while copying. The first locate attempt may fail because the destination
   file is not present yet.
4. Temporal retries `locateAndValidate`; once the copy completes, validation succeeds and the parent
   starts the child workflows.
5. Use option **5** and the Temporal UI to observe the retry and subsequent fan-out.

## Complete scenario: induce and correct a file validation error

1. Run option **1 (`up`)**.
2. Run option **2 (`data 24000`)** to create a billing CSV, GL companion, and sidecars.
3. Run option **3 (`files`)** and identify the billing filename.
4. In `sftp/home`, edit either the billing CSV contents or its `.sha256` sidecar so the expected hash
   no longer matches the actual file bytes.
5. Run option **4 (`process <filename>`)**. This performs the normal Signal-1 submission and copies the
   intentionally invalid drop to `sftp/destination`.
6. Open the Temporal UI or run option **5 (`status`)**. `locateAndValidate` fails with non-retryable
   `HashMismatch` after the activity policy, and the parent enters `WAITING_FOR_CORRECTION`.
7. Correct the mismatch: restore the CSV, regenerate the sidecar, or replace both with a newly generated
   matching pair in `sftp/home`.
8. If the **same filename** was corrected, option **4** can submit it again with Signal 1 to the same
   long-running coordinator, but this is treated as a new file-available submission and does not remove
   the old waiting entry. Option **7 (`retry <filename>`)** is the implementation's intended Signal-2
   path for a file parked in `WAITING_FOR_CORRECTION`.
9. If a **new correctly generated filename** was created, select that filename with option **7** only
   if intentionally using the Signal-2 retry queue; otherwise use option **4** for the normal Signal-1
   submission. In both cases the coordinator remains the same, but Signal 2 is the semantically correct
   correction flow.
10. Confirm that the corrected file validates, is assigned a new SHA-256-based file identity if its
    contents changed, is sliced again, and its child workflows are started by the same coordinator.


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
