# Billing Reconciliation

Daily billing reconciliation orchestrated by **Temporal**. Transactions are validated, enriched, and priced in **Java activities**, matched to the general ledger, and held for operator resolution when amounts disagree.

The demonstration scale is **1.2 million** rows, processed as **12 parallel child workflows** of 100,000 transactions. One Spring Boot process hosts the REST API and the worker. PostgreSQL stores source data and run artifacts.

| Component | Detail |
| --- | --- |
| Language / runtime | Java 17, Spring Boot 3.3.5 |
| Orchestration | Temporal Java SDK 1.30.1 (local server or Temporal Cloud) |
| Database | PostgreSQL 16 (`billing`) |
| Scale (default) | 1.2M rows · 100K per child · 12 concurrent children |
| Task queue | `billing-reconciliation-queue` |

## Contents

- [Overview](#overview)
- [Architecture](#architecture)
- [Processing model](#processing-model)
- [Workflow design](#workflow-design)
- [Prerequisites](#prerequisites)
- [Getting started](#getting-started)
- [Running a reconciliation](#running-a-reconciliation)
- [Discrepancy resolution](#discrepancy-resolution)
- [Schedule](#schedule)
- [Configuration](#configuration)
- [Scenario mapping](#scenario-mapping)
- [Sample data](#sample-data)
- [HTTP API](#http-api)
- [Demo script](#demo-script)
- [Tests](#tests)
- [Source layout](#source-layout)

---

## Overview

Each run:

1. Slices `billing_transactions` into batches.
2. Validates schema in Java and records per-field failures.
3. Enriches valid rows with vendor and customer data (HTTP, with a local-table fallback).
4. Applies surcharge, discount, and late-fee rules in Java.
5. Matches processed original amounts to general-ledger entries.
6. Waits for `COMPENSATE` or `CONTINUE` when mismatches remain, then Continue-As-New and re-runs the pipeline.
7. After every child completes, persists compliance reports, notification records, and an audit trail.

**Workflows** orchestrate steps and stay deterministic. **Activities** execute I/O and business rules. **PostgreSQL** loads DTOs and persists results; it does not classify rows, compute fees, or decide mismatches.

A step-by-step mapping to the 16-step scenario is in [`IMPLEMENTATION_COVERAGE.md`](IMPLEMENTATION_COVERAGE.md).

---

## Architecture

```
                    Temporal Schedule                         REST API
                    08:00 America/Chicago                     POST /api/reconciliation/start
                    daily-billing-reconciliation              POST .../resolve
                              |                                         |
                              |  schedule start                         |  WorkflowClient
                              v                                         v
            +------------------------------- Temporal --------------------------------+
            |  UI :8088  ──────────────►  Server :7233                                 |
            |                                |                    |                    |
            |                           Task queue                +── Temporal Postgres |
            |                    billing-reconciliation-queue                          |
            +--------------------------------^-----------------------------------------+
                                             | poll / complete
            +--------------------------------+-----------------------------------------+
            |                         Spring Boot :8080                                |
            |     REST API     |     Temporal worker     |     Mock enrichment HTTP    |
            |                  |                         |     /api/vendors/bulk       |
            |                  |                         |     /api/customers/bulk     |
            +--------+---------+------------+------------+--------------+--------------+
                     |                      |                           |
                     |  start / signal      |  load / persist           |  loopback HTTP
                     |                      v                           v
                     |              +------ Billing PostgreSQL :5432 ------------------+
                     +------------► |  billing_transactions   gl_entries               |
                                    |  processed_transactions discrepancies            |
                                    |  reports  notifications  audit_log               |
                                    +--------------------------------------------------+
```

| Port | Service |
| --- | --- |
| 8080 | Application REST API and worker |
| 8088 | Temporal UI |
| 7233 | Temporal gRPC |
| 5432 | Billing PostgreSQL |

---

## Processing model

Temporal activities load rows as DTOs, apply Java engines, then persist. Heartbeats are emitted about every 5,000 rows so long batches stay within activity heartbeat timeouts.

| Concern | Java | PostgreSQL |
| --- | --- | --- |
| Schema validation | `SchemaValidator` | Status `VALID` / `INVALID` and `validation_errors` |
| Vendor / customer fetch | Parallel bulk HTTP | Cache write; local `vendors` / `customers` fallback |
| Enrichment | `RecordEnricher` | Upsert `processed_transactions` |
| Surcharge, discount, late fee | `BillingRulesEngine` | Updated money columns |
| GL match | `GlMatcher` | Insert `discrepancies` |
| Compensation | `CompensationPolicy` | Billing amount aligned to GL |

Lookups, batch slicing, counts, reports, and audit inserts remain SQL. They do not evaluate billing rules.

GL match (`GlMatcher`) flags `MISSING_GL` or `AMOUNT_MISMATCH` when the absolute difference exceeds tolerance `0.01`. Match uses **original** processed amount, not the fee-adjusted final amount.

---

## Workflow design

### Parent: `BillingReconciliationWorkflow`

One parent execution per run. Child workflow ids are `{parentId}-batch-{n}`. Reporting and notification run only after every child returns.

```
  Schedule 08:00  or  POST /start
              │
              ▼
  BillingReconciliationWorkflow
              │
              │  startRun, listBatches
              │
              ├── spawn children (max-parallel-batches = 12)
              │     BatchReconciliationWorkflow
              │     {parentId}-batch-1  …  {parentId}-batch-12
              │
              │     POST /batches/{childId}/resolve  ──► child signal
              │     POST /{parentId}/resolve         ──► fan-out to waiting children
              │
              │  wait for all children
              ▼
  generateReports → notifyStakeholders → completeRun
```

### Child: `BatchReconciliationWorkflow`

Every resolution round re-runs the full pipeline against current database state. `COMPENSATE` aligns flagged billing amounts to the ledger, then Continue-As-New. `CONTINUE` skips that write and re-reads the database. The loop stops after `max-resolution-rounds` (default 10).

```
  VALIDATE_SCHEMA
  HANDLE_VALIDATION_ERRORS
  FETCH_VENDOR_DATA            →  HTTP /api/vendors/bulk     (fallback: vendors)
  FETCH_CUSTOMER_DATA          →  HTTP /api/customers/bulk   (fallback: customers)
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
                            │  COMPENSATE  →  CompensationPolicy, then Continue-As-New
                            │  CONTINUE    →  Continue-As-New without auto-fix
                            ▼
                      round + 1, same workflow id, back to VALIDATE_SCHEMA
```

Parent execution timeout is unbounded so a run can wait on human resolution. Each scheduled start creates a **new** parent execution.

---

## Prerequisites

| Requirement | Notes |
| --- | --- |
| Docker and Docker Compose | PostgreSQL, Temporal, and (optional) the application image |
| JDK 17 | Local `mvn` builds and tests |
| Maven 3.9+ | `mvn test`, `mvn spring-boot:run` |
| Temporal CLI (optional) | Workflow inspection; not required to start a run via REST |

---

## Getting started

Start PostgreSQL, Temporal, the UI, and the application. The API listens on **8080**; Temporal UI on **8088**.

```bash
docker compose up -d --build
./scripts/insert-dummy-data.sh
curl -X POST http://localhost:8080/api/reconciliation/start
```

Windows PowerShell:

```powershell
docker compose up -d --build
.\scripts\insert-dummy-data.ps1
Invoke-RestMethod -Method Post -Uri http://localhost:8080/api/reconciliation/start
```

Rebuild the application image (`docker compose up -d --build`) after Java engine changes so the worker is not still running the previous SQL-based rules.

If a Temporal server is already running elsewhere, use the [demo script](#demo-script). It starts only PostgreSQL and the application (default API port **8081**, so it does not collide with a Temporal UI on 8080):

```bash
./scripts/demo.sh all
```

```powershell
.\scripts\demo.ps1 all
```

Examples below use port 8080. Substitute 8081 when using `demo.sh`.

---

## Running a reconciliation

```bash
APP=http://localhost:8080/api/reconciliation

curl -X POST $APP/start
# {"workflowId":"billing-reconciliation-2026-09-08-…","status":"STARTED","txnCount":1200000,"expectedBatches":12}
WF=<workflowId from the response>

curl $APP/$WF/progress
curl $APP/batches/$WF-batch-1/step
curl $APP/batches/$WF-batch-1/problems

curl -X POST $APP/batches/$WF-batch-1/resolve \
  -H 'Content-Type: application/json' \
  -d '{"decision":"COMPENSATE"}'

curl $APP/$WF/result
```

1. `POST /start` starts `BillingReconciliationWorkflow`. `listBatches` partitions by row count: `ceil(transactions / batch-size)`.
2. The parent starts children up to `max-parallel-batches` (default 12).
3. Each child validates, enriches, applies billing rules, matches the GL, and identifies discrepancies.
4. Clean children complete. Children with mismatches enter `WAITING_FOR_SIGNAL` and expose transaction ids.
5. A resolve signal uses **Continue-As-New**. The new round re-executes the pipeline; corrected ids drop off.
6. When every child is done, the parent writes reports, notifications, and audit rows.

Resolving one batch completes only that child. Reports and notifications run on the parent after all children finish.

---

## Discrepancy resolution

| Request | Scope | Behavior |
| --- | --- | --- |
| `{"decision":"COMPENSATE"}` | Entire batch | Align every flagged billing amount to the GL, then Continue-As-New. |
| `{"decision":"CONTINUE"}` | Entire batch | Re-run against current data. Unfixed ids wait again. |
| `{"txnId":"X","decision":"COMPENSATE"}` | One id | Compensate `X` only. Other ids reappear on the next round. |
| `{"txnId":"X","decision":"CONTINUE"}` | One id | Re-check that id. |
| Same body on the parent `/resolve` | Fan-out | Deliver the decision to every child still waiting. |

An empty body (`{}`) defaults to **COMPENSATE** for the whole batch. After a manual data correction, send `{"decision":"CONTINUE"}`.

```bash
curl $APP/txns/TXN0001050001
curl -X POST $APP/txns/TXN0001050001/correct -d '{}'
curl -X POST $APP/batches/$WF-batch-11/resolve \
  -H 'Content-Type: application/json' \
  -d '{"txnId":"TXN0001050001","decision":"CONTINUE"}'
```

Signaling a failed or completed workflow returns HTTP 409 `NOT_RUNNING`. After resolve, Temporal UI shows a **Continued as New** child run, then **Completed** when the batch is clean.

---

## Schedule

[`ReconciliationScheduleConfig`](src/main/java/com/billing/reconciliation/config/ReconciliationScheduleConfig.java) registers a Temporal Schedule at startup.

| Key | Default | Description |
| --- | --- | --- |
| `billing.schedule.enabled` | `true` | Register on boot (`BILLING_SCHEDULE_ENABLED`) |
| `billing.schedule.cron` | `0 8 * * *` | 08:00 |
| `billing.schedule.timezone` | `America/Chicago` | Schedule timezone |
| `billing.schedule.overlap-policy` | `BUFFER_ONE` | If the previous run is still open at 08:00 |

Overlap policies: `BUFFER_ONE`, `SKIP`, `ALLOW_ALL`, `CANCEL_OTHER`, `TERMINATE_OTHER`. Inspect the schedule in Temporal UI → **Schedules** → `daily-billing-reconciliation`.

---

## Configuration

Settings live in [`application.yaml`](src/main/resources/application.yaml) and are bound by [`BillingProperties`](src/main/java/com/billing/reconciliation/config/BillingProperties.java). Values are copied into workflow input so timeouts and rule parameters stay deterministic on replay.

| Area | Defaults |
| --- | --- |
| Batching | `batch-size` 100000, `max-parallel-batches` 12 |
| Worker | 100 activity executors, 50 workflow-task executors |
| Enrichment | 1000 concurrent calls, bulk chunk 200, 30s timeout, cache fallback |
| Rules | Surcharge, discount, and late fee in [`application.yaml`](src/main/resources/application.yaml) |
| Match | Amount tolerance `0.01`; max resolution rounds `10` |
| Timeouts | Parent and child execution unbounded (`0s`); activities 15–30m with 2m heartbeat; retry 1s → 16s, coefficient 2 |

### Temporal connection

| Variable | Local | Temporal Cloud |
| --- | --- | --- |
| `TEMPORAL_TARGET` | `127.0.0.1:7233` | `<ns>.<accountId>.tmprl.cloud:7233` |
| `TEMPORAL_NAMESPACE` | `default` | `<namespace>.<accountId>` |
| `TEMPORAL_ENABLE_HTTPS` / `TEMPORAL_TLS` | `false` | `true` (also forced when `TEMPORAL_API_KEY` is set) |
| `TEMPORAL_API_KEY` | empty | Cloud API key |
| `TEMPORAL_IDENTITY` | SDK `host@pid` | Optional worker label |
| `TEMPORAL_TASK_QUEUE` | `billing-reconciliation-queue` | Isolate queues if required |

Cloud is configuration-only ([`TemporalClientConfig`](src/main/java/com/billing/reconciliation/config/TemporalClientConfig.java)). Do not store the API key in YAML.

```bash
export TEMPORAL_TARGET=my-namespace.abc123.tmprl.cloud:7233
export TEMPORAL_NAMESPACE=my-namespace.abc123
export TEMPORAL_API_KEY='your-api-key'
docker compose up -d --build
```

---

## Scenario mapping

| Step | Implementation |
| --- | --- |
| 1. Scheduled trigger 08:00 | Temporal Schedule |
| 2. Read 1.2M in 100K batches | `IngestionActivities.listBatches` → child workflows |
| 3. Validate schema | `SchemaValidator` via `IngestionActivities.validateSchema` |
| 4. Handle validation errors | `handleValidationErrors` with activity retry (1s–16s) |
| 5–6. Vendor / customer | Parallel bulk HTTP, then local-table fallback |
| 7. Enrich | `RecordEnricher`, idempotent upsert |
| 8–10. Rules, discounts, late fees | `BillingRulesEngine` via `BillingRuleActivities` |
| 11–12. Query GL, match | `GlMatcher`; persist `discrepancies` |
| 13. Identify and resolve | Wait → `CompensationPolicy` or `CONTINUE` → Continue-As-New |
| 14–16. Reports, notify, audit | `ReportingActivities` → `reports`, `notifications`, `audit_log` |

Reports and notifications are stored in PostgreSQL. This demonstration does not send email, Teams webhooks, or SFTP files.

---

## Sample data

```bash
./scripts/insert-dummy-data.sh            # 1,200,000 rows
./scripts/insert-dummy-data.sh 24000      # smaller set
```

```powershell
.\scripts\insert-dummy-data.ps1
.\scripts\insert-dummy-data.ps1 24000
```

The script applies [`scripts/migrate-schema.sql`](scripts/migrate-schema.sql), then inserts. Seeded anomalies:

- General-ledger mismatches on `id % 10000 = 1` (GL amount + 417) — one discrepancy per 100K batch.
- Invalid rows with `amount = 0` on every 5,000th transaction.

Database **`billing`** on `localhost:5432`, user `billing` / `billing`.

| Kind | Tables |
| --- | --- |
| Source | `billing_transactions`, `vendors`, `customers`, `gl_entries` |
| Run | `processed_transactions`, `discrepancies`, `reports`, `notifications`, `audit_log`, `validation_errors`, `reconciliation_runs`, `vendor_cache`, `customer_cache` |

---

## HTTP API

Base path: `/api/reconciliation`.

| Method | Path | Purpose |
| --- | --- | --- |
| POST | `/start` | Start a run (rejected if the table is empty) |
| GET | `/{workflowId}/progress` | Parent progress and child ids |
| GET | `/{workflowId}/result` | Final result (waits until complete) |
| POST | `/{workflowId}/resolve` | Fan a decision to waiting children |
| GET | `/batches/{childWorkflowId}/step` | Child current step |
| GET | `/batches/{childWorkflowId}/problems` | Child discrepancy ids |
| POST | `/batches/{childWorkflowId}/resolve` | Resolve a batch or a single `txnId` |
| GET | `/runs/{runId}` | Persisted run row |
| GET | `/txns/{txnId}` | Demo: billing vs GL |
| POST | `/txns/{txnId}/correct` | Demo: `{}` aligns to GL; `{"amount":n}` sets a value |
| POST | `/api/vendors/bulk`, `/api/customers/bulk` | Mock enrichment endpoints |
| GET | `/actuator/health` | Health |

---

## Demo script

[`scripts/demo.sh`](scripts/demo.sh) (Linux/macOS) and [`scripts/demo.ps1`](scripts/demo.ps1) (Windows PowerShell) drive a full demonstration. With no arguments they show a menu; subcommands also accept `--up`, `--run`, and similar flags. They start PostgreSQL and the application and expect Temporal at `$TEMPORAL_TARGET` / `$env:TEMPORAL_TARGET`.

| Command | Action |
| --- | --- |
| `up` | Start PostgreSQL and the application (worker + API) |
| `data` | Load `ROWS` rows |
| `run` | Start a run; print workflow id, UI link, and waiting children |
| `status` | Stack health and current-run progress |
| `resolve` | Interactive: batch COMPENSATE/CONTINUE, a single id, correct-then-CONTINUE, or parent fan-out |
| `restart` | Restart the application; durable workflows resume |
| `stop` / `down` | Stop the application and PostgreSQL / also remove the volume |
| `all` | `up` + `data` + `run` |

Overridable environment (production-like defaults): `ROWS` (1200000), `BATCH_SIZE` (100000), `MAX_PARALLEL` (12), `APP_PORT` (8081), `TEMPORAL_TARGET`, `TEMPORAL_NAMESPACE`, `TEMPORAL_API_KEY`, `TEMPORAL_UI`.

```bash
./scripts/demo.sh all
ROWS=24000 BATCH_SIZE=2000 ./scripts/demo.sh all
```

```powershell
.\scripts\demo.ps1 all
$env:ROWS=24000; $env:BATCH_SIZE=2000; .\scripts\demo.ps1 all
```

---

## Tests

```bash
mvn test
```

| Suite | Coverage |
| --- | --- |
| [`BillingReconciliationWorkflowTest`](src/test/java/com/billing/reconciliation/workflow/BillingReconciliationWorkflowTest.java) | In-memory Temporal: happy path, batch COMPENSATE / CONTINUE, per-id Continue-As-New |
| [`SchemaValidatorTest`](src/test/java/com/billing/reconciliation/engine/SchemaValidatorTest.java) | Per-field schema rules |
| [`RecordEnricherTest`](src/test/java/com/billing/reconciliation/engine/RecordEnricherTest.java) | Vendor / customer merge |
| [`BillingRulesEngineTest`](src/test/java/com/billing/reconciliation/engine/BillingRulesEngineTest.java) | Surcharge, discount, late fee |
| [`GlMatcherTest`](src/test/java/com/billing/reconciliation/engine/GlMatcherTest.java) | Missing GL and amount mismatch |
| [`CompensationPolicyTest`](src/test/java/com/billing/reconciliation/engine/CompensationPolicyTest.java) | Align billing to GL and reset processed amounts |

---

## Source layout

Paths are under `src/main/java/com/billing/reconciliation/` unless noted.

### Configuration and stack

| Item | Path |
| --- | --- |
| Application settings | [`src/main/resources/application.yaml`](src/main/resources/application.yaml) |
| Property binding | [`config/BillingProperties.java`](src/main/java/com/billing/reconciliation/config/BillingProperties.java) |
| Daily schedule | [`config/ReconciliationScheduleConfig.java`](src/main/java/com/billing/reconciliation/config/ReconciliationScheduleConfig.java) |
| Temporal Cloud / TLS | [`config/TemporalClientConfig.java`](src/main/java/com/billing/reconciliation/config/TemporalClientConfig.java) |
| Compose stack | [`docker-compose.yml`](docker-compose.yml) |
| Database schema | [`docker/postgres/init.sql`](docker/postgres/init.sql) |

### Workflows and API

| Item | Path |
| --- | --- |
| Parent workflow | [`workflow/BillingReconciliationWorkflowImpl.java`](src/main/java/com/billing/reconciliation/workflow/BillingReconciliationWorkflowImpl.java) |
| Child workflow | [`workflow/BatchReconciliationWorkflowImpl.java`](src/main/java/com/billing/reconciliation/workflow/BatchReconciliationWorkflowImpl.java) |
| REST API | [`api/ReconciliationController.java`](src/main/java/com/billing/reconciliation/api/ReconciliationController.java) |
| Workflow / API models | [`model/`](src/main/java/com/billing/reconciliation/model) (`BatchRef`, `StepResult`, `ReconciliationRequest`, …) |

### Activities

| Item | Path |
| --- | --- |
| Ingestion | [`activity/IngestionActivitiesImpl.java`](src/main/java/com/billing/reconciliation/activity/IngestionActivitiesImpl.java) |
| Enrichment | [`activity/EnrichmentActivitiesImpl.java`](src/main/java/com/billing/reconciliation/activity/EnrichmentActivitiesImpl.java) |
| Billing rules | [`activity/BillingRuleActivitiesImpl.java`](src/main/java/com/billing/reconciliation/activity/BillingRuleActivitiesImpl.java) |
| Reconciliation | [`activity/ReconciliationActivitiesImpl.java`](src/main/java/com/billing/reconciliation/activity/ReconciliationActivitiesImpl.java) |
| Compensation | [`activity/CompensationActivitiesImpl.java`](src/main/java/com/billing/reconciliation/activity/CompensationActivitiesImpl.java) |
| Reporting | [`activity/ReportingActivitiesImpl.java`](src/main/java/com/billing/reconciliation/activity/ReportingActivitiesImpl.java) |

### Java engines

All business rules live in [`engine/`](src/main/java/com/billing/reconciliation/engine): `SchemaValidator`, `RecordEnricher`, `BillingRulesEngine`, `GlMatcher`, `CompensationPolicy`.

### DTOs

Row and enrichment payloads used by activities and persistence. Package: [`dto/`](src/main/java/com/billing/reconciliation/dto).

| DTO | Used for |
| --- | --- |
| `BillingTransactionDto` | Schema validation |
| `ValidationErrorDto` | Per-field validation failures |
| `VendorDto` / `CustomerDto` | Enrichment HTTP and cache |
| `ProcessedTransactionDto` | Enrichment and billing rules |
| `DiscrepancyDto` | GL match results |
| `BillingAmountCorrection` | Compensation (align billing to GL) |

### Persistence

| Item | Path |
| --- | --- |
| JDBC load / persist | [`db/BillingJdbc.java`](src/main/java/com/billing/reconciliation/db/BillingJdbc.java) |

