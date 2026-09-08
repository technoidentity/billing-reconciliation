# Billing Reconciliation

Daily billing reconciliation: **1.2 million** transactions validated, enriched, scored against billing rules, and matched to the general ledger. Orchestration is **Temporal**; all I/O is **PostgreSQL**.

A single Spring Boot process hosts the REST API and the Temporal worker. The parent workflow fans out to **12 child workflows** (100K rows each). Children with GL mismatches wait for a resolution signal, then **Continue-As-New** and re-run the same pipeline against the current database.

| Layer | Detail |
| --- | --- |
| Runtime | Java 17 · Spring Boot 3.3.5 · Temporal Java SDK 1.30.1 |
| Data | PostgreSQL 16 (`billing`) |
| Orchestration | Temporal Server (local) or Temporal Cloud |
| Scale | 1.2M rows · 100K per child · 12 parallel children |
| Task queue | `billing-reconciliation-queue` |

**Contents:** [Architecture](#architecture) · [Quick start](#quick-start) · [End-to-end run](#end-to-end-run) · [Discrepancy resolution](#discrepancy-resolution) · [Schedule](#schedule) · [Configuration](#configuration) · [Demo scenario mapping](#demo-scenario-mapping) · [Dummy data](#dummy-data) · [API](#api) · [Demo script](#demo-script) · [Tests](#tests) · [Source map](#source-map)

---

## Architecture

Verified against `BillingReconciliationWorkflowImpl`, `BatchReconciliationWorkflowImpl`, `ReconciliationController`, and `docker-compose.yml`.

**Runtime** — schedule and REST start work on Temporal; the worker polls the queue; activities hit billing Postgres (SQL) or loopback HTTP.

```
  Temporal Schedule                 REST API
  08:00 America/Chicago             POST /api/reconciliation/start
  daily-billing-reconciliation      POST .../resolve  GET .../progress
            |                                  |
            |  Schedule start                  |  WorkflowClient
            v                                  v
  +---------------------- Temporal --------------------------------+
  |  UI :8088 ------view-----> Server :7233                        |
  |                              |              |                  |
  |                         Task Queue          +-- Temporal PG    |
  |                   billing-reconciliation-queue                 |
  +------------------------------^---------------------------------+
                                 | poll / complete
  +------------------------------+---------------------------------+
  |                    Spring Boot JVM :8080                       |
  |   REST API   |   Temporal Worker   |   Mock enrichment HTTP    |
  |              |                     |   /api/vendors/bulk       |
  |              |                     |   /api/customers/bulk     |
  +------+-------+----------+----------+-------------+-------------+
         |                  |                        |
         |  start/signal    |  SQL                   |  HTTP loopback
         |  (to Temporal)   v                        v
         |            +----------- Billing Postgres :5432 -----------+
         |            |  billing_transactions  gl_entries            |
         +----------->|  processed_transactions  discrepancies       |
                      |  reports  notifications  audit_log           |
                      +----------------------------------------------+
```

**Workflows** — one parent per run; children `{parentId}-batch-{n}`; parent reports only after every child returns.

```
  Schedule 08:00  or  POST /start
              |
              v
  BillingReconciliationWorkflow          (long-running parent)
              |
              |  activities: startRun, listBatches
              |
              +-- spawn 12 children in parallel (max-parallel-batches=12)
              |
              |     BatchReconciliationWorkflow
              |     {parentId}-batch-1   ...  {parentId}-batch-12
              |     (100K txn ids each)
              |
              |     POST /batches/{childId}/resolve  ----+
              |     POST /{parentId}/resolve (fan-out    |
              |       to pendingChildIds only) ----------+--> child signal
              |
              |  wait until all children complete
              |
              v
  generateReports -> notifyStakeholders -> completeRun
  (reports / notifications / audit_log in billing Postgres)
```

**Child loop** — full pipeline every round; `COMPENSATE` writes billing:=GL then Continue-As-New; `CONTINUE` skips the write and re-reads the DB. Stops after `max-resolution-rounds` (10).

```
  VALIDATE_SCHEMA
  HANDLE_VALIDATION_ERRORS
  FETCH_VENDOR_DATA          --> HTTP /api/vendors/bulk   (fallback: vendors)
  FETCH_CUSTOMER_DATA        --> HTTP /api/customers/bulk (fallback: customers)
  ENRICH_RECORDS
  APPLY_BILLING_RULES
  CALCULATE_ADJUSTMENTS
  APPLY_PENALTIES
  QUERY_GL
  MATCH_TRANSACTIONS
  IDENTIFY_DISCREPANCIES
            |
            +-- 0 mismatches ----------------> COMPLETED ------------+
            |                                                        |
            +-- mismatches and round >= 10                           |
            |     ----------------------> COMPLETED_WITH_UNRESOLVED -+
            |                                                        |
            +-- mismatches and round < 10                            |
                  WAITING_FOR_SIGNAL                                 |
                        |                                            |
                        |  signal: COMPENSATE or CONTINUE            |
                        |  optional txnId (else all flagged ids)     |
                        |                                            |
                        +-- COMPENSATE                               |
                        |     compensateDiscrepancies                |
                        |     billing_transactions.amount := GL      |
                        |                                            |
                        +-- CONTINUE                                 |
                              (no auto-fix)                          |
                        |                                            |
                        v                                            |
                  Continue-As-New                                    |
                  same workflow id, round+1                          |
                        |                                            |
                        +---- back to VALIDATE_SCHEMA                |
                                                                     v
                                                            parent BatchResult
```



---

## Quick start

Postgres, Temporal, UI, and the app. App on **8080**, Temporal UI on **8088**.

```bash
docker compose up -d --build
./scripts/insert-dummy-data.sh
curl -X POST http://localhost:8080/api/reconciliation/start
```

**Ports:** app REST API `:8080`, Temporal UI `:8088`, Temporal server `:7233`, Postgres `:5432`.

Already running a Temporal server elsewhere? Use the [demo script](#demo-script) instead — it starts only
Postgres + the app (on `:8081` to avoid clashing with a Temporal UI on `:8080`) and drives the whole flow:

```bash
./scripts/demo.sh all          # up + data + run   (or: ./scripts/demo.sh  for a menu)
```

> Examples below use `:8080`. If you used `demo.sh`, substitute `:8081`.

---

## End-to-end run

```bash
APP=http://localhost:8080/api/reconciliation

curl -X POST $APP/start
# {"workflowId":"billing-reconciliation-2026-09-08-…","status":"STARTED","txnCount":1200000,"expectedBatches":12}
WF=<workflowId from the response>

curl $APP/$WF/progress
curl $APP/batches/$WF-batch-1/step
curl $APP/batches/$WF-batch-1/problems

# Auto-align every flagged id in the batch to the GL, then re-process
curl -X POST $APP/batches/$WF-batch-1/resolve \
  -H 'Content-Type: application/json' -d '{"decision":"COMPENSATE"}'

# After all 12 children complete, the parent writes reports and notifications
curl $APP/$WF/result
```

1. `POST /start` starts `BillingReconciliationWorkflow`. `listBatches` splits the table by row count (`ceil(txns / batch-size)`).
2. The parent starts 12 children in parallel (`max-parallel-batches`).
3. Each child: validate → errors → vendor → customer → enrich → rules → adjustments → penalties → query GL → match → identify discrepancies.
4. Clean children complete. Mismatched children set `WAITING_FOR_SIGNAL` and expose txn ids.
5. A resolve signal Continue-As-News the child. The new run re-executes the pipeline; fixed ids drop off, remaining ids wait again.
6. When every child is done, the parent runs reports, notify, and audit.

Resolving one batch only finishes that child. Steps 14–16 run on the **parent** after all children complete.

---

## Discrepancy resolution

| Request | Scope | Behavior |
| --- | --- | --- |
| `{"decision":"COMPENSATE"}` | Entire batch | Compensation activity aligns every flagged billing amount to the GL, then Continue-As-New. |
| `{"decision":"CONTINUE"}` | Entire batch | No auto-fix. Re-run against the current DB. Unfixed ids wait again. |
| `{"txnId":"X","decision":"COMPENSATE"}` | One id | Compensate `X` only; other ids reappear on the next run. |
| `{"txnId":"X","decision":"CONTINUE"}` | One id | Re-check the DB for that id. |
| Same body on the **parent** `/resolve` | Fan-out | Delivers the decision to every child still waiting. |

An empty body (`{}`) defaults to **COMPENSATE for the whole batch**. After a manual SQL fix, send `{"decision":"CONTINUE"}`.

```bash
# Manual fix, then re-check
psql ... -c "UPDATE billing_transactions
             SET amount = (SELECT amount FROM gl_entries WHERE txn_id='TXN0001050001')
             WHERE txn_id='TXN0001050001';"

curl -X POST $APP/batches/$WF-batch-11/resolve \
  -H 'Content-Type: application/json' -d '{"decision":"CONTINUE"}'
```

Demo helpers (same effect without raw SQL):

```bash
curl $APP/txns/TXN0001050001
curl -X POST $APP/txns/TXN0001050001/correct -d '{}'
curl -X POST $APP/batches/$WF-batch-11/resolve \
  -d '{"txnId":"TXN0001050001","decision":"CONTINUE"}'
```

Signaling a Failed or Completed workflow returns HTTP 409 `NOT_RUNNING`.

The parent execution is unbounded (`execution-timeout: 0`) so it can wait on human resolution. Each daily schedule start is a **new** parent execution. After resolve, the Temporal UI shows a `Continued as New` child run followed by `Completed` when the batch is clean.

---

## Schedule

Registered at startup by [`ReconciliationScheduleConfig`](src/main/java/com/billing/reconciliation/config/ReconciliationScheduleConfig.java) (Temporal Schedule API).

| Key | Default | Meaning |
| --- | --- | --- |
| `billing.schedule.enabled` | `true` | Register on boot (`BILLING_SCHEDULE_ENABLED`) |
| `billing.schedule.cron` | `0 8 * * *` | 08:00 |
| `billing.schedule.timezone` | `America/Chicago` | Schedule timezone |
| `billing.schedule.overlap-policy` | `BUFFER_ONE` | If yesterday’s run is still open at 08:00 |

Overlap options: `BUFFER_ONE`, `SKIP`, `ALLOW_ALL`, `CANCEL_OTHER`, `TERMINATE_OTHER`. Manage the schedule in Temporal UI → **Schedules** → `daily-billing-reconciliation`.

---

## Configuration

Knobs live in [`application.yaml`](src/main/resources/application.yaml) and are bound by [`BillingProperties`](src/main/java/com/billing/reconciliation/config/BillingProperties.java). Values are snapshotted into workflow input so timeouts and rules stay deterministic on replay.

| Area | Key defaults |
| --- | --- |
| Batching | `batch-size` 100000, `max-parallel-batches` 12 |
| Worker | 100 activity executors, 50 workflow-task executors |
| Enrichment | 1000 concurrent calls, bulk chunk 200, 30s timeout, cache fallback |
| Rules | surcharge 2% above 10000; mid-tier $5 above 1000; discount 5% above 5000; late fee 2% after 30 days |
| Match | amount tolerance `0.01`; max resolution rounds `10` |
| Timeouts | parent/child execution `0s` (unbounded); activities 15–30m with 2m heartbeat; retry 1s→16s ×2 |

### Temporal connection

| Env | Local | Cloud |
| --- | --- | --- |
| `TEMPORAL_TARGET` | `127.0.0.1:7233` | `<ns>.<accountId>.tmprl.cloud:7233` |
| `TEMPORAL_NAMESPACE` | `default` | `<namespace>.<accountId>` |
| `TEMPORAL_ENABLE_HTTPS` / `TEMPORAL_TLS` | `false` | `true` (forced when `TEMPORAL_API_KEY` is set) |
| `TEMPORAL_API_KEY` | empty | Cloud API key |
| `TEMPORAL_IDENTITY` | SDK `host@pid` | optional worker label |
| `TEMPORAL_TASK_QUEUE` | `billing-reconciliation-queue` | isolate queues if needed |

Cloud is config-only ([`TemporalClientConfig`](src/main/java/com/billing/reconciliation/config/TemporalClientConfig.java)). Do not put the API key in YAML.

```bash
export TEMPORAL_TARGET=my-namespace.abc123.tmprl.cloud:7233
export TEMPORAL_NAMESPACE=my-namespace.abc123
export TEMPORAL_API_KEY='your-api-key'
docker compose up -d --build
```

---

## Demo scenario mapping

| Step | Implementation |
| --- | --- |
| 1 Scheduled trigger 08:00 | Temporal Schedule |
| 2 Read 1.2M in 100K batches | `IngestionActivities.listBatches` → 12 children |
| 3 Validate schema | `IngestionActivities.validateSchema` |
| 4 Validation errors (retry 1s–16s) | `handleValidationErrors` + activity retry |
| 5–6 Vendor / customer | Parallel bulk HTTP, then local table fallback |
| 7 Enrich | SQL merge into `processed_transactions` (idempotent) |
| 8–10 Rules, discounts, late fees | `BillingRuleActivities` |
| 11–12 Query GL, match | In-memory compare; writes `discrepancies` |
| 13 Identify + resolve | Wait → COMPENSATE / CONTINUE → Continue-As-New |
| 14–16 Reports, notify, audit | `ReportingActivities` → `reports`, `notifications`, `audit_log` |

Reports and notify persist in Postgres (no SFTP/SMTP/Teams webhook).

---

## Dummy data

```bash
./scripts/insert-dummy-data.sh            # 1,200,000 rows
./scripts/insert-dummy-data.sh 24000      # smaller set
```

Applies [`scripts/migrate-schema.sql`](scripts/migrate-schema.sql), then inserts. Seeded anomalies:

- GL mismatches on `id % 10000 = 1` (`gl amount + 417`) — one discrepancy per 100K batch.
- Invalid rows `amount = 0` on every 5000th txn.

```sql
UPDATE gl_entries SET amount = amount + 417 WHERE txn_id IN ('TXN0000000002','TXN0000000003');
```

Database **`billing`** on `localhost:5432`, user `billing` / `billing`. Source tables: `billing_transactions`, `vendors`, `customers`, `gl_entries`. Run tables: `processed_transactions`, `discrepancies`, `reports`, `notifications`, `audit_log`, `validation_errors`, `reconciliation_runs`, `vendor_cache`, `customer_cache`.

---

## API

Base path `/api/reconciliation`.

| Method | Path | Purpose |
| --- | --- | --- |
| POST | `/start` | Start a run (refuses an empty table) |
| GET | `/{workflowId}/progress` | Parent progress and child ids |
| GET | `/{workflowId}/result` | Final result (blocks until complete) |
| POST | `/{workflowId}/resolve` | Fan a decision to waiting children |
| GET | `/batches/{childWorkflowId}/step` | Child current step |
| GET | `/batches/{childWorkflowId}/problems` | Child discrepancy ids |
| POST | `/batches/{childWorkflowId}/resolve` | Resolve a batch or a single `txnId` |
| GET | `/runs/{runId}` | Persisted run row |
| GET | `/txns/{txnId}` | Demo: billing vs GL |
| POST | `/txns/{txnId}/correct` | Demo: `{}` aligns to GL; `{"amount":n}` sets a value |
| POST | `/api/vendors/bulk`, `/api/customers/bulk` | Mock enrichment |
| GET | `/actuator/health` | Health |

---

## Demo script

[`scripts/demo.sh`](scripts/demo.sh) drives the whole demo — subcommands (also `--up`, `--run`, …) or an
interactive menu when run with no arguments. It starts Postgres + the app and assumes a Temporal server is
reachable at `$TEMPORAL_TARGET`.

| Command | Action |
| --- | --- |
| `up` | Start Postgres + app (worker + API) |
| `data` | Load `ROWS` rows |
| `run` | Trigger a run; print the workflow id, a UI link, and the waiting children |
| `status` | Stack health + current-run progress |
| `resolve` | Interactive: whole-batch COMPENSATE/CONTINUE, a single id, DB-correct + CONTINUE, or parent fan-out |
| `restart` | Restart the app (worker + API); durable workflows resume |
| `stop` / `down` | Stop app + Postgres / also wipe the volume |
| `all` | `up` + `data` + `run` |

Overridable env (defaults are production-like): `ROWS` (1200000), `BATCH_SIZE` (100000), `MAX_PARALLEL` (12),
`APP_PORT` (8081), `TEMPORAL_TARGET`, `TEMPORAL_NAMESPACE`, `TEMPORAL_API_KEY`, `TEMPORAL_UI`.

```bash
./scripts/demo.sh all                       # full run
ROWS=24000 BATCH_SIZE=2000 ./scripts/demo.sh all   # fast demo, still 12 children
```

---

## Tests

```bash
mvn test
```

In-memory Temporal test environment (no Docker): happy path, whole-batch COMPENSATE / CONTINUE, and per-id Continue-As-New — [`BillingReconciliationWorkflowTest`](src/test/java/com/billing/reconciliation/workflow/BillingReconciliationWorkflowTest.java).

---

## Source map

| Concern | Path |
| --- | --- |
| Config | [`src/main/resources/application.yaml`](src/main/resources/application.yaml) |
| Property binding | [`config/BillingProperties.java`](src/main/java/com/billing/reconciliation/config/BillingProperties.java) |
| Daily schedule | [`config/ReconciliationScheduleConfig.java`](src/main/java/com/billing/reconciliation/config/ReconciliationScheduleConfig.java) |
| Temporal Cloud / TLS | [`config/TemporalClientConfig.java`](src/main/java/com/billing/reconciliation/config/TemporalClientConfig.java) |
| Parent workflow | [`workflow/BillingReconciliationWorkflowImpl.java`](src/main/java/com/billing/reconciliation/workflow/BillingReconciliationWorkflowImpl.java) |
| Child workflow | [`workflow/BatchReconciliationWorkflowImpl.java`](src/main/java/com/billing/reconciliation/workflow/BatchReconciliationWorkflowImpl.java) |
| REST API | [`api/ReconciliationController.java`](src/main/java/com/billing/reconciliation/api/ReconciliationController.java) |
| SQL | [`db/BillingJdbc.java`](src/main/java/com/billing/reconciliation/db/BillingJdbc.java) |
| Schema | [`docker/postgres/init.sql`](docker/postgres/init.sql) |
