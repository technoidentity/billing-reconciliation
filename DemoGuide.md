# Billing Reconciliation Demo Guide

This guide covers running the Temporal billing-reconciliation demo with `scripts/demo.ps1`.

## Prerequisites

- Windows PowerShell 5.1 or later
- Docker Desktop with Docker Compose
- Java and Maven, if the application JAR must be built
- A running Temporal server, normally at `127.0.0.1:7233`

`demo.ps1` does not start Temporal. It checks that the configured Temporal TCP endpoint is reachable, starts billing PostgreSQL, and starts the Spring Boot application/Temporal worker.

## PowerShell version

`demo.ps1` is a native Windows PowerShell script and has been validated with Windows PowerShell 5.1. Check the version if needed:

```powershell
$PSVersionTable.PSVersion
```

Run the script directly from Windows PowerShell:

```powershell
.\scripts\demo.ps1
```

## Configuration

Set environment variables before running a command:

```powershell
$env:ROWS = "24000"
$env:BATCH_SIZE = "2000"
$env:MAX_PARALLEL = "12"
$env:APP_PORT = "8081"
```

| Variable | Default | Effect |
|---|---:|---|
| `APP_PORT` | `8080` | Application REST/health port. |
| `ROWS` | `1200000` | Dummy transactions loaded by `data`. |
| `BATCH_SIZE` | `100000` | Transactions per child workflow. |
| `MAX_PARALLEL` | `12` | Maximum parallel batch setting passed to the app. |
| `TEMPORAL_TARGET` | `127.0.0.1:7233` | Temporal host and port checked by `up`. |
| `TEMPORAL_NAMESPACE` | `default` | Temporal namespace. |
| `TEMPORAL_API_KEY` | empty | Optional Temporal Cloud API key. |
| `TEMPORAL_ENABLE_HTTPS` | `false` | Enables HTTPS/TLS configuration. |
| `TEMPORAL_IDENTITY` | empty | Optional Temporal client identity. |
| `TEMPORAL_UI` | `http://localhost:8088` | Base URL used for the printed Temporal UI link. |

The child count is `ceil(ROWS / BATCH_SIZE)`. For example, 24,000 rows with a batch size of 2,000 creates 12 child workflows.

## Recommended demo

```powershell
$env:ROWS = "24000"
$env:BATCH_SIZE = "2000"

.\scripts\demo.ps1 up
.\scripts\demo.ps1 data
.\scripts\demo.ps1 run
.\scripts\demo.ps1 status
.\scripts\demo.ps1 resolve
.\scripts\demo.ps1 stop
```

For a one-shot start/load/run:

```powershell
.\scripts\demo.ps1 all
```

## Commands

### `up`

Checks for Docker, tests the Temporal TCP endpoint, starts the `postgresql` Compose service, waits for `billing-postgres` to become ready, builds the JAR if missing, and starts the Java application in the background.

The app health check is `GET http://localhost:<APP_PORT>/actuator/health`, polled for up to 60 seconds. Logs are written to `%TEMP%\billing-demo\app.log` and `%TEMP%\billing-demo\app.err.log`; the process ID is stored in `%TEMP%\billing-demo\app.pid`.

### `data`

Checks PostgreSQL readiness and runs `scripts\insert-dummy-data.ps1 -Rows <ROWS>`. The seed data includes invalid transactions and GL mismatches for the discrepancy-resolution demo. This command does not start a workflow.

### `run`

Checks application health and sends `POST /api/reconciliation/start`. It saves the returned parent workflow ID to `%TEMP%\billing-demo\wf`, prints a Temporal UI link, waits ten seconds, and scans child status.

The parent workflow starts one `BatchReconciliationWorkflow` per batch. Each child runs:

1. Schema validation and validation-error handling.
2. Vendor and customer enrichment.
3. Record merging.
4. Billing rules, adjustments/discounts, and penalties/late fees.
5. GL query and in-memory transaction matching.
6. Discrepancy identification.
7. Completion, or waiting for a resolution signal.

After all children finish, the parent generates reports, records notifications, logs audit data, and completes the run.

### `status`

Displays application health, PostgreSQL container state, configured Temporal target/namespace, parent workflow progress, and every child’s current step. It uses the saved workflow ID from `%TEMP%\billing-demo\wf`.

### `restart`

Stops and restarts only the Java application. PostgreSQL and Temporal remain running. Temporal workflow history is retained, so parked children can resume waiting for signals after the worker restarts.

### `stop`

Stops the Java app and the `postgresql` Compose service. It does not delete volumes or the Temporal schedule. The daily Temporal schedule remains registered.

### `down`

Requires an exact lowercase `y` confirmation, then runs `stop` and `docker compose down -v`. This removes Docker volumes and demo PostgreSQL data. Use it only to reset the environment.

### `all`

Runs `up`, then `data`, then `run`. If `up` fails, the remaining commands are not useful and should be run separately for diagnosis.

### No command / `menu`

Opens an interactive menu with these mappings:

| Menu option | Command |
|---|---|
| `1` | `up` |
| `2` | `data` |
| `3` | `run` |
| `4` | `status` |
| `5` | `resolve` |
| `6` | `restart` |
| `7` | `stop` |
| `8` | `all` |
| `q` | Quit |

## `resolve` and child options

`resolve` requires the app to be healthy and a saved parent workflow ID. It scans all expected child IDs (`<parent-id>-batch-<number>`) and lists children in `WAITING_FOR_SIGNAL`.

## Signal routing: parent versus child workflow

Discrepancy decisions are ultimately consumed by the `BatchReconciliationWorkflow` child that is paused in `WAITING_FOR_SIGNAL`.

| Demo action | Workflow signaled | Endpoint | Effect |
|---|---|---|---|
| Resolve one batch | The selected `BatchReconciliationWorkflow` child | `POST /api/reconciliation/batches/{childWorkflowId}/resolve` | Sends the decision only to that child. |
| Resolve one transaction ID | The selected `BatchReconciliationWorkflow` child | Same child endpoint, with `txnId` | Sends a targeted decision to that child and transaction. |
| Correct a transaction + Continue | The selected `BatchReconciliationWorkflow` child | Correction endpoint, then same child resolve endpoint | Updates the demo transaction first, then signals the child to re-read the database. |
| Parent fan-out | The `BillingReconciliationWorkflow` parent | `POST /api/reconciliation/{parentWorkflowId}/resolve` | The parent signals every currently pending child; completed children are skipped. |

The parent does not perform the batch compensation itself. In the fan-out case, it forwards the decision to each waiting child. Each child then decides whether to invoke its compensation activity, Continue-As-New, and rerun its own batch pipeline.

The `COMPENSATE` or `CONTINUE` decision is therefore handled as follows:

1. The API receives the request.
2. The API signals either the selected child or the parent.
3. If the parent was signaled, it forwards the signal to pending child workflows.
4. The child wakes from `Workflow.await(...)`.
5. `COMPENSATE` invokes the compensation activity for the selected IDs; `CONTINUE` skips compensation.
6. The child uses Continue-As-New and re-runs validation, enrichment, billing calculation, GL matching, and discrepancy identification.

### 1. COMPENSATE a whole batch

Selects a waiting batch and sends `COMPENSATE` to that child. The compensation policy computes corrections in Java memory, but the result is persisted to PostgreSQL. For each flagged ID with a GL amount, it updates the billing transaction amount to the GL amount, resets processed adjustment/discount/penalty/final-amount fields, and marks the discrepancy compensated. The child then uses Continue-As-New to re-read the corrected database state and rerun the pipeline.

### 2. CONTINUE a whole batch

Selects a waiting batch and sends `CONTINUE`. No compensation writes occur. The child Continue-As-News and rechecks current database state; records corrected outside the workflow can clear the mismatch while unchanged records remain flagged.

### 3. Resolve a single ID

Selects a waiting batch, lists its discrepancy transaction IDs, asks for one ID, and asks for `COMPENSATE` or `CONTINUE`. The decision is targeted to that transaction ID. A targeted `COMPENSATE` persists the billing correction for only that ID, resets its processed money, and marks its discrepancy compensated.

### 4. Correct a transaction in DB + CONTINUE

Selects one discrepancy, calls `GET /api/reconciliation/txns/{txnId}`, calls the demo correction endpoint `POST /api/reconciliation/txns/{txnId}/correct`, then sends targeted `CONTINUE`. This demonstrates external data correction followed by workflow re-evaluation.

### 5. Parent fan-out to all waiting

Asks for `COMPENSATE` or `CONTINUE`, then sends the decision to `POST /api/reconciliation/{parentId}/resolve`. The parent fans the signal out to all currently pending children. Completed children are not signaled.

### 6. Refresh

Refreshes child steps and parent progress without sending a resolution.

### `b` Back

Leaves the resolution loop.

## Workflow observation

The printed Temporal UI URL opens the parent workflow. The REST endpoints used by the script are:

```text
GET  /api/reconciliation/{workflowId}/progress
GET  /api/reconciliation/batches/{childWorkflowId}/step
GET  /api/reconciliation/batches/{childWorkflowId}/problems
POST /api/reconciliation/batches/{childWorkflowId}/resolve
POST /api/reconciliation/{workflowId}/resolve
```

Expected child states include `VALIDATE_SCHEMA`, `MATCH_TRANSACTIONS`, `WAITING_FOR_SIGNAL`, `CONTINUE_AS_NEW`, and `COMPLETED`.

## Troubleshooting

- Temporal unreachable: start Temporal or set `$env:TEMPORAL_TARGET` correctly.
- App not running: inspect `%TEMP%\billing-demo\app.log` and `app.err.log`.
- `Postgres not up`: run `up` before `data`.
- `no run - run 'run' first`: the app health check passed, but `%TEMP%\billing-demo\wf` is missing or empty; run `run` first.
- No waiting children: the workflow may still be processing, or all batches may have completed without discrepancies.

## Reset

To remove the demo database and start over:

```powershell
.\scripts\demo.ps1 down
```

Confirm with lowercase `y`, then run `all` again.
