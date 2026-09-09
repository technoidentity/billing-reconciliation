# Temporal Transactions Reconciliation Implementation Coverage

This document maps the current implementation to the 16-step Temporal workflow described in the Transactions reconciliation PDF.

The PDF is treated as the scenario specification. Its Azure Logic Apps and hybrid-architecture sections are treated as alternatives or recommendations, not mandatory requirements for this Temporal-only implementation.

## Coverage summary

The implementation covers the core Temporal orchestration path: scheduled start, batching, child workflows, validation, enrichment, billing calculations, GL reconciliation, discrepancy handling, Continue-As-New, reporting, notification, audit logging, and completion. It is a self-contained demo implementation, not a complete production realization of every integration and output format described in the PDF.

Business logic now lives in a **pure-Java `engine` layer** (no JDBC), separate from persistence. Each activity follows a **load → compute → persist** pattern: `BillingJdbc` loads rows as DTOs, an engine computes the result in memory, and `BillingJdbc` persists it. Long 100K batches are processed in ~5,000-row chunks (`Heartbeats.CHUNK`) so activities keep heartbeating. The engines are independently unit-tested.

Several integrations are intentionally represented by local PostgreSQL persistence or loopback/demo endpoints. These are identified below as mocked or simplified implementations.

## Architecture layers

| Layer | Responsibility | Key types |
|---|---|---|
| Workflows | Orchestration, fan-out, signals, Continue-As-New | `BillingReconciliationWorkflowImpl`, `BatchReconciliationWorkflowImpl` |
| Activities | Load → call engine → persist; heartbeating | `*ActivitiesImpl` in `activity/` |
| Engines (pure Java, no JDBC) | Validation, enrichment, billing math, GL match, compensation | `SchemaValidator`, `RecordEnricher`, `BillingRulesEngine`, `GlMatcher`, `CompensationPolicy` |
| DTOs | Rows passed between JDBC and engines | `dto/` (`BillingTransactionDto`, `ProcessedTransactionDto`, `DiscrepancyDto`, `ValidationErrorDto`, `VendorDto`, `CustomerDto`, `BillingAmountCorrection`) |
| Persistence | Load/persist only — no business rules | `BillingJdbc` |

## Step-by-step mapping

| PDF step | Status | Current implementation | Notes / deviation |
|---|---|---|---|
| 1. Scheduled trigger | Covered as-is | `ReconciliationScheduleConfig` registers a Temporal Schedule for `0 8 * * *` in `America/Chicago`, with overlap policy `BUFFER_ONE`. | Uses the Temporal Schedule API rather than a workflow cron option. |
| 2. Read 1.2M transactions | Covered with variation | `IngestionActivities.listBatches` → `BillingJdbc.listBatches` slices by row number (`ROW_NUMBER()`), yielding `ceil(rows / batch-size)` batches (12 for 1.2M at 100K). | PostgreSQL demo tables stand in for the PDF's data warehouse. Batch count derives from available rows rather than requiring exactly 1.2M. |
| 3. Validate transaction schema | Covered as-is | `validateSchema` loads the batch as DTOs (`loadBatchForValidation`) and runs the Java `SchemaValidator` in 5K chunks; valid ids are persisted via `updateStatuses`. | Per-field validation is Java code, not SQL predicates. |
| 4. Handle validation errors | Covered as-is | `handleValidationErrors` classifies the batch with `SchemaValidator`, then persists invalid ids (`updateStatuses`) and one `ValidationErrorDto` per failed field (`replaceValidationErrors`). Activity retry is exponential 1s→16s. | The retry policy excludes `ValidationError`, so validation failures are recorded, not retried as transient activity failures. |
| 5. Fetch vendor data | Covered with mocked/demo integration | `EnrichmentActivities.fetchVendorData` calls `/api/vendors/bulk` concurrently (bounded by `max-concurrent-calls`), with timeout, retry, and local-table fallback. | The vendor API is a loopback/mock endpoint in the same app by default, not an external service. |
| 6. Fetch customer data | Covered with mocked/demo integration | `fetchCustomerData` calls `/api/customers/bulk` the same way, with PostgreSQL fallback. | Loopback/mock endpoint plus fallback. |
| 7. Enrich transaction records | Covered as-is | `enrichRecords` loads valid rows + vendor/customer cache maps and merges them **in memory** via `RecordEnricher.enrich` (5K chunks), then upserts `processed_transactions`. | Matches the PDF's in-memory merge. Rows without a cache hit are skipped (same as the previous INNER JOIN). |
| 8. Apply billing rules | Covered as-is | `applyBillingRules` runs `BillingRulesEngine.applySurcharge` (tiered surcharge / mid-tier flat fee / pass-through) on loaded DTOs, chunked, then persists via `updateProcessedMoney`. | Rules come from `BillingRuleParams.from(request)`, i.e. `application.yaml`. |
| 9. Calculate adjustments and discounts | Covered as-is | `calculateAdjustments` runs `BillingRulesEngine.applyDiscount` (Java `BigDecimal` math), chunked and persisted. | Computation is Java, not SQL expressions. |
| 10. Apply penalties and late fees | Covered as-is | `applyPenalties` runs `BillingRulesEngine.applyLateFees` against a cutoff of `LocalDate.now().minusDays(lateDays)`. | The cutoff uses the activity host date (activity-side, outside workflow determinism). |
| 11. Query General Ledger | Covered with variation | `ReconciliationActivities.queryGeneralLedger` reads GL rows from PostgreSQL. | PostgreSQL `gl_entries` stands in for the external GL system. |
| 12. Match transactions with GL | Covered as-is | `matchTransactions` loads processed rows + a `txn → gl amount` map and compares them in memory via `GlMatcher.match` (`MISSING_GL` / `AMOUNT_MISMATCH`), persisting results with `insertDiscrepancies`. | In-memory matching after SQL reads. |
| 13. Identify and resolve discrepancies | Partially covered / manual variation | `identifyDiscrepancies` returns problem ids. Children wait for `COMPENSATE`/`CONTINUE` and Continue-As-New. `CompensationPolicy` builds billing→GL corrections and resets processed money; `BillingJdbc` persists them. | The PDF describes automatic Saga compensation. This implementation pauses for a human/API signal before compensation or re-check, so it is not automatic rollback. |
| 14. Generate compliance reports | Partially covered / mocked output | `ReportingActivities.generateReports` stores a compliance CSV and an Excel-compatible CSV in the `reports` table. | The PDF calls for PDF/Excel files. No actual PDF/XLSX files, file delivery, or external reporting service is implemented. |
| 15. Notify stakeholders | Partially covered / mocked output | `notifyStakeholders` records email and Teams notification intents in `notifications`. | The PDF calls for sending email/Teams messages. No SMTP, Microsoft Graph, Teams webhook, or Logic Apps connector sends messages. |
| 16. Log audit trail and complete | Covered as-is | `logAuditTrail` and `completeRun` persist audit and run-completion records; Temporal persists workflow history/state. | Audit delivery is database-backed rather than an external platform. |

## Temporal-specific functions covered

- Parent `BillingReconciliationWorkflow` starts the run, reads batches, fans out child workflows, waits for all children, generates reports, notifies stakeholders, and completes the run.
- Child `BatchReconciliationWorkflow` executes the full reconciliation pipeline for each batch.
- Child workflows use stable IDs of the form `{parentWorkflowId}-batch-{batchNo}`.
- Parent concurrency is configurable through `max-parallel-batches`, defaulting to 12.
- Vendor and customer requests are parallelized within their respective activities, but the workflow invokes those two phases sequentially rather than concurrently.
- Child discrepancy resolution supports whole-batch and single-transaction targeting.
- Parent resolution fans a decision out to currently pending children only (completed children are not signaled).
- `Continue-As-New` carries the round, compensation state, and original discrepancy IDs while re-reading current database state.
- Resolution rounds are bounded by `max-resolution-rounds`, defaulting to 10.
- Activity retries, start-to-close timeouts, heartbeat timeouts, and retryable failure policies are configured per activity category; long batches heartbeat every ~5,000 rows.
- Workflow progress, current steps, discrepancy IDs, and child IDs are exposed through workflow queries and REST endpoints.
- Temporal Schedule overlap policy defaults to `BUFFER_ONE` when a prior daily run is still open.

## Mocked or demo-only implementations

The following functions are present to make the demonstration self-contained and should be replaced or connected to production systems for a real deployment:

- `/api/vendors/bulk` is a local mock vendor enrichment endpoint.
- `/api/customers/bulk` is a local mock customer enrichment endpoint.
- Vendor/customer database tables are fallback data sources when the mock HTTP calls fail after retries.
- PostgreSQL `billing_transactions` represents the data warehouse source.
- PostgreSQL `gl_entries` represents the General Ledger source.
- `CompensationPolicy` updates billing amounts to the GL amount. This is a demo correction policy, not a confirmed production business policy.
- Reports are CSV text stored in the database; the `.xlsx.csv` naming is Excel-compatible output, not an actual XLSX workbook, and no PDF is generated.
- Notifications are database rows representing email/Teams delivery; no external messages are sent.
- Dummy-data scripts create the 1.2M-row-scale dataset and seeded invalid rows/mismatches.
- `/txns/{txnId}/correct` is a demo helper for changing billing data before sending a `CONTINUE` resolution.

## Material deviations from the PDF

1. **Manual discrepancy resolution:** mismatches pause for an external signal instead of being automatically compensated by a Saga.
2. **Report format:** CSV and Excel-compatible CSV replace actual PDF/Excel file generation and delivery.
3. **Notification delivery:** database notification records replace real email/Teams delivery.
4. **Data sources:** local PostgreSQL replaces the data warehouse and external GL system.
5. **Enrichment phase concurrency:** vendor and customer activities run one after the other at workflow level, although each activity makes bounded parallel calls internally.
6. **Date input:** late-fee cutoff uses `LocalDate.now()` inside an activity, so the value is not a workflow-controlled input and may vary across retries on different dates.
7. **Integration architecture:** the current implementation is Temporal plus local/mock services; it does not implement the PDF's optional Azure Logic Apps hybrid architecture.

## Verification

`mvn test` — **29 tests, 0 failures, 0 errors**:

- `SchemaValidatorTest` (11), `BillingRulesEngineTest` (7), `GlMatcherTest` (3), `RecordEnricherTest` (2), `CompensationPolicyTest` (2) — the engines are tested in isolation, without Temporal or a database.
- `BillingReconciliationWorkflowTest` (4) — happy path, whole-batch COMPENSATE/CONTINUE, and per-id Continue-As-New in the in-memory Temporal test environment.

## Relevant source locations

- Parent workflow: `src/main/java/com/billing/reconciliation/workflow/BillingReconciliationWorkflowImpl.java`
- Child workflow: `src/main/java/com/billing/reconciliation/workflow/BatchReconciliationWorkflowImpl.java`
- Schedule: `src/main/java/com/billing/reconciliation/config/ReconciliationScheduleConfig.java`
- Activities: `src/main/java/com/billing/reconciliation/activity/`
- Engines (pure Java): `src/main/java/com/billing/reconciliation/engine/` — `SchemaValidator`, `RecordEnricher`, `BillingRulesEngine`, `GlMatcher`, `CompensationPolicy`
- DTOs: `src/main/java/com/billing/reconciliation/dto/`
- Persistence: `src/main/java/com/billing/reconciliation/db/BillingJdbc.java`
- REST API: `src/main/java/com/billing/reconciliation/api/ReconciliationController.java`
- Engine unit tests: `src/test/java/com/billing/reconciliation/engine/`
- Configuration: `src/main/resources/application.yaml`
