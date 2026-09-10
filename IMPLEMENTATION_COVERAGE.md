# Implementation Coverage — File-based Billing Reconciliation

Maps the current implementation to the file-based requirement: SFTP CSV input, one long-running
coordinator driven by signals, hash-validation with retries, child workflows on file slices,
COMPENSATE-only resolution, and periodic Continue-As-New. There is **no database** — Temporal keeps
workflow history (in its own store); all billing data and outputs are files.

## Architecture layers

| Layer | Responsibility | Key types |
|---|---|---|
| Coordinator (parent) | Long-running; waits for file signals, validates, fans out, Continue-As-New | `BillingReconciliationWorkflowImpl` |
| Child | Reconciles one file slice; COMPENSATE-only | `BatchReconciliationWorkflowImpl` |
| File activities | Locate + SHA-256 validate (throws/retries); slice batches | `FileActivitiesImpl` |
| Engines (pure Java) | Validation, enrichment, billing math, GL match, compensation | `SchemaValidator`, `RecordEnricher`, `BillingRulesEngine`, `GlMatcher`, `CompensationPolicy` |
| File store | Read slices, write processed/discrepancy/report CSVs under `destination/work` + `outbound` | `ReconciliationFileStore` |
| Inbound files | SFTP (`sshj`) or local dir; copy home→destination | `SftpInboundFiles`, `LocalInboundFiles`, `InboundFileTransfer` |
| API | `/start`, `/files/available` (Signal 1), `/files/retry` (Signal 2), `/resolve` (COMPENSATE) | `ReconciliationController` |

## Requirement → implementation

| Requirement | Status | Implementation |
|---|---|---|
| Input from SFTP files, not a DB | Covered | `sshj` SFTP client; CSV + `.sha256` sidecar in `sftp/home`; no JDBC/datasource anywhere. |
| One long-running parent, starts once, no schedule | Covered | `CoordinatorStarter` starts a single fixed id `billing-reconciliation-coordinator` on boot (idempotent). `run()` is a `while(!shutdown)` signal loop. No Temporal Schedule. |
| Signal 1 — new file → hash-validation activity | Covered | `@SignalMethod fileAvailable` queues the file; `FileActivities.locateAndValidate` computes SHA-256 and compares to the **producer sidecar** (independent reference), run identity = the checksum. |
| Validation success → start child workflow(s) | Covered | On success the file is promoted to `destination/work/{sha256}/`, sliced by row count, and one `BatchReconciliationWorkflow` per slice is started. |
| Validation failure → keep file waiting (no terminate) | Covered | The activity **throws** (red in the UI). The parent **catches** the `ActivityFailure`, parks the file in `WAITING_FOR_CORRECTION`, and keeps processing other files. |
| Signal 2 — retry corrected file, configured attempts | Covered | `@SignalMethod retryCorrectedFile` re-runs `locateAndValidate`, whose Temporal retry policy retries "not found yet" up to `billing.file-validation.max-retry-attempts` (default 3). |
| Parent stays active for subsequent files | Covered | After each file it returns to `LISTENING`; signals are buffered while busy. (Files are processed sequentially — see Notes.) |
| Children operate on file data only | Covered | Activities read slices via `ReconciliationFileStore.loadBatch`; outputs are CSVs. No DB. |
| No DB correction/manual-fix; COMPENSATE only | Covered | Controller and child reject any non-`COMPENSATE` decision; there is no `/txns/correct` endpoint. COMPENSATE writes GL amounts into the batch CSV, then Continue-As-New. |
| Continue-As-New to bound history | Covered | Parent CANs on `Workflow.getInfo().isContinueAsNewSuggested()` (size **and** event count); optional byte override `continue-as-new-history-bytes` (0 = off, cap 50MB). Carries pending files, waiting state, recent results. |
| Data drop is external; a signal triggers validation with retries | Covered | The API only moves home→destination and signals; validation + retry happen in Temporal. |
| SFTP folder mounting | Covered | `docker/sftp` (atmoz/sftp) with `./sftp/home` and `./sftp/destination` bind-mounted. |
| Demo scripts (bash + PowerShell 5.1) | Covered | `scripts/demo.sh` and `scripts/demo.ps1` (`#requires -Version 5.1`), same commands incl. `clear`, `fail`, `large`. |
| Reports no longer DB-bound | Covered | `ReportingActivities` writes CSVs to `destination/outbound/{sha256}/` via the file store. |

## Copy modes (durability)

- **async (default, `billing.files.async-copy=true`):** the API starts the home→destination copy in the
  background and signals concurrently. `locateAndValidate` throws `FileNotFound` (retryable) and retries
  until a large file lands — visible red attempts in the UI, then green. `copy-delay-seconds` can force
  retries for small files in a demo.
- **sync (`false`):** copy completes before the signal, so locate finds the file on attempt 1.
- Overridable per request with `"async": true|false`.

## Validation failure classification

| Condition | Failure | Retry |
|---|---|---|
| Billing/GL/sidecar not present yet | `FileNotFound` / `GlFileNotFound` / `ChecksumNotFound` | Retryable (large file still landing) |
| Content ≠ sidecar | `HashMismatch` / `GlHashMismatch` | Non-retryable (must be corrected + re-sent) |

All failures show the activity **red** in the UI; the coordinator catches them and parks the file for a
Signal-2 retry while other files continue.

## Child pipeline (per slice)

`VALIDATE_SCHEMA → HANDLE_VALIDATION_ERRORS → FETCH_VENDOR/CUSTOMER → ENRICH → APPLY_BILLING_RULES →
CALCULATE_ADJUSTMENTS → APPLY_PENALTIES → QUERY_GL → MATCH_TRANSACTIONS → IDENTIFY_DISCREPANCIES`. On a
mismatch the child waits for COMPENSATE, writes GL amounts into the batch CSV, and Continue-As-News to
re-check (bounded by `max-resolution-rounds`).

## Mocked / demo-only

- Vendor/customer enrichment endpoints (`/api/vendors/bulk`, `/api/customers/bulk`) are loopback mocks
  with `reference/*.csv` fallback.
- Reports are CSVs in `outbound/{sha256}/`; notifications are logged, not delivered (no SMTP/Teams).
- `generate-sample-files.py` seeds a GL mismatch on `id % 10000 == 1` and invalid rows on `id % 5000 == 0`.

## Notes / deviations

1. **Sequential file processing.** The coordinator processes one valid file at a time; new signals are
   buffered and processed in order. A valid file that is mid-processing (or whose children wait on
   COMPENSATE) delays the next file. Validation-failed files are non-blocking (parked, loop continues).
2. **Temporal history store** is Postgres inside the Temporal server image — infra only, not billing data.
   Temporal Cloud removes it entirely.

## Verification

`mvn test` — engine unit tests, `FileHasherTest`, `FileActivitiesImplTest` (sidecar validation, retryable
`FileNotFound`, non-retryable `HashMismatch`), and `BillingReconciliationWorkflowTest` (file signal,
validate-fails-red-then-retry, COMPENSATE re-process, per-id COMPENSATE) — all green.

## Source map

- Coordinator: `src/main/java/com/billing/reconciliation/workflow/BillingReconciliationWorkflowImpl.java`
- Child: `src/main/java/com/billing/reconciliation/workflow/BatchReconciliationWorkflowImpl.java`
- File activities: `src/main/java/com/billing/reconciliation/activity/FileActivitiesImpl.java`
- Engines: `src/main/java/com/billing/reconciliation/engine/`
- File store / SFTP: `src/main/java/com/billing/reconciliation/file/`
- API: `src/main/java/com/billing/reconciliation/api/ReconciliationController.java`
- Config: `src/main/resources/application.yaml`, `config/BillingProperties.java`
