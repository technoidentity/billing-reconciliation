# Temporal Billing Reconciliation Implementation Coverage

This document maps the current implementation to the 16-step Temporal workflow described in the billing reconciliation PDF.

The PDF is treated as the scenario specification. Its Azure Logic Apps and hybrid-architecture sections are treated as alternatives or recommendations, not mandatory requirements for this Temporal-only implementation.

## Coverage summary

The implementation covers the complete Temporal orchestration path: scheduled start, batching, child workflows, validation, enrichment, billing calculations, GL reconciliation, discrepancy resolution, Continue-As-New, reporting, notification, audit logging, and completion.

Several integrations are intentionally represented by local PostgreSQL persistence or loopback/demo endpoints. These are identified below as mocked or simplified implementations.

## Step-by-step mapping

| PDF step | Status | Current implementation | Notes / deviation |
|---|---|---|---|
| 1. Scheduled trigger | Covered as-is | `ReconciliationScheduleConfig` registers a Temporal Schedule for `0 8 * * *` in `America/Chicago`. | Uses the Temporal Schedule API rather than a workflow cron option. |
| 2. Read 1.2M transactions | Covered with variation | `IngestionActivities.listBatches` reads PostgreSQL billing rows and creates batches. Default batch size is 100,000, yielding 12 batches for 1.2M rows. | PostgreSQL demo tables stand in for the PDF's data warehouse. The implementation determines batch count from available rows rather than requiring exactly 1.2M rows. |
| 3. Validate transaction schema | Covered as-is | `IngestionActivities.validateSchema` marks rows valid and counts them per batch. | Validation rules are implemented in SQL/database logic rather than a detailed Java schema validator. |
| 4. Handle validation errors | Covered with variation | `handleValidationErrors` marks invalid rows and activity policies provide exponential retry from 1s through 16s. | Validation errors are persisted and excluded from processing. The configured retry policy excludes `ValidationError`, so validation failures themselves are not retried as transient activity failures. |
| 5. Fetch vendor data | Covered with mocked/demo integration | `EnrichmentActivities.fetchVendorData` calls `/api/vendors/bulk` concurrently, with timeout, retry, and local-table fallback. | The vendor API is a loopback/mock endpoint in the same Spring Boot application by default, not an external production vendor service. |
| 6. Fetch customer data | Covered with mocked/demo integration | `EnrichmentActivities.fetchCustomerData` calls `/api/customers/bulk` concurrently, with timeout, retry, and local-table fallback. | The customer service is represented by a loopback/mock endpoint and PostgreSQL fallback. |
| 7. Enrich transaction records | Covered with variation | `EnrichmentActivities.enrichRecords` merges cached vendor/customer data into `processed_transactions`. | The PDF describes an in-memory merge; the implementation performs the merge with PostgreSQL SQL for durable, idempotent persistence. |
| 8. Apply billing rules | Covered as-is | `BillingRuleActivities.applyBillingRules` applies configured surcharge and tiered fee rules. | Rules are configurable through `application.yaml`. |
| 9. Calculate adjustments and discounts | Covered as-is | `calculateAdjustments` applies mathematical adjustment and discount calculations. | Calculations are executed in SQL activity code rather than entirely in workflow code. |
| 10. Apply penalties and late fees | Covered with variation | `applyPenalties` applies late fees using configured due-date and rate rules. | Temporal replay protects workflow decisions, but the calculation itself is activity/database work. The late-date cutoff uses the activity host date. |
| 11. Query General Ledger | Covered with variation | `ReconciliationActivities.queryGeneralLedger` reads GL rows from PostgreSQL. | PostgreSQL `gl_entries` stands in for the external GL database/service. |
| 12. Match transactions with GL | Covered with variation | `matchTransactions` loads billing and GL rows, builds an in-memory GL map, compares amounts, and persists discrepancies. | It is in-memory matching after SQL reads, rather than a fully external or warehouse-native matching operation. |
| 13. Identify and resolve discrepancies | Covered with variation | `identifyDiscrepancies` persists/returns problem IDs. Children wait for `COMPENSATE` or `CONTINUE` signals and then Continue-As-New. | The PDF's diagram suggests automatic Saga compensation. This implementation requires human/API resolution before compensation or re-checking. |
| 14. Generate compliance reports | Covered with variation / mocked output | `ReportingActivities.generateReports` stores a compliance CSV and an Excel-compatible CSV in the `reports` table. | The PDF calls for PDF/Excel files. Actual PDF/XLSX files and file delivery are not generated. |
| 15. Notify stakeholders | Covered with variation / mocked output | `notifyStakeholders` records email and Teams notification intents in the `notifications` table. | No SMTP, Microsoft Graph, Teams webhook, or Logic Apps connector sends messages externally. |
| 16. Log audit trail and complete | Covered as-is | `logAuditTrail` and `completeRun` persist audit and run-completion records in PostgreSQL; Temporal persists workflow history/state. | Audit delivery is database-backed rather than an external audit platform. |

## Temporal-specific functions covered

- Parent `BillingReconciliationWorkflow` starts the run, reads batches, fans out child workflows, waits for all children, generates reports, notifies stakeholders, and completes the run.
- Child `BatchReconciliationWorkflow` executes the full reconciliation pipeline for each batch.
- Child workflows use stable IDs of the form `{parentWorkflowId}-batch-{batchNo}`.
- Parent concurrency is configurable through `max-parallel-batches`, defaulting to 12.
- Child discrepancy resolution supports whole-batch and single-transaction targeting.
- Parent resolution supports fan-out signaling to currently pending children.
- `Continue-As-New` carries the round, compensation state, and original discrepancy IDs while re-reading current database state.
- Resolution rounds are bounded by `max-resolution-rounds`, defaulting to 10.
- Activity retries, start-to-close timeouts, heartbeat timeouts, and retryable failure policies are configured per activity category.
- Workflow progress, current steps, discrepancy IDs, and child IDs are exposed through workflow queries and REST endpoints.
- Temporal Schedule overlap policy defaults to `BUFFER_ONE` when a prior daily run is still open.

## Mocked or demo-only implementations

The following functions are present to make the demonstration self-contained and should be replaced or connected to production systems for a real deployment:

- `/api/vendors/bulk` is a local mock vendor enrichment endpoint.
- `/api/customers/bulk` is a local mock customer enrichment endpoint.
- Vendor/customer database tables are fallback data sources when the mock HTTP calls fail after retries.
- PostgreSQL `billing_transactions` represents the data warehouse source.
- PostgreSQL `gl_entries` represents the General Ledger source.
- Compensation updates billing amounts to the GL amount. This is a demo correction policy, not a confirmed production business policy.
- Reports are CSV text stored in the database; the `.xlsx.csv` naming is Excel-compatible output, not an actual XLSX workbook.
- Notifications are database rows representing email/Teams delivery; no external messages are sent.
- Dummy-data scripts create the 1.2M-row-scale dataset and seeded invalid rows/mismatches.
- `/txns/{txnId}/correct` is a demo helper for changing billing data before sending a `CONTINUE` resolution.

## Material deviations from the PDF

1. **Manual discrepancy resolution:** mismatches pause for an external signal instead of being automatically compensated.
2. **Report format:** CSV and Excel-compatible CSV replace actual PDF/Excel file generation.
3. **Notification delivery:** database notification records replace real email/Teams delivery.
4. **Data sources:** local PostgreSQL replaces the data warehouse and external GL system.
5. **Integration architecture:** the current implementation is Temporal plus local/mock services; it does not implement the PDF's optional Azure Logic Apps hybrid architecture.
6. **Validation detail:** the implementation uses database status marking and counts rather than a fully described per-field schema-validation engine.

## Relevant source locations

- Parent workflow: `src/main/java/com/billing/reconciliation/workflow/BillingReconciliationWorkflowImpl.java`
- Child workflow: `src/main/java/com/billing/reconciliation/workflow/BatchReconciliationWorkflowImpl.java`
- Schedule: `src/main/java/com/billing/reconciliation/config/ReconciliationScheduleConfig.java`
- Ingestion activities: `src/main/java/com/billing/reconciliation/activity/IngestionActivitiesImpl.java`
- Enrichment activities: `src/main/java/com/billing/reconciliation/activity/EnrichmentActivitiesImpl.java`
- Reconciliation activities: `src/main/java/com/billing/reconciliation/activity/ReconciliationActivitiesImpl.java`
- Compensation activity: `src/main/java/com/billing/reconciliation/activity/CompensationActivitiesImpl.java`
- Reporting and notifications: `src/main/java/com/billing/reconciliation/activity/ReportingActivitiesImpl.java`
- PostgreSQL operations: `src/main/java/com/billing/reconciliation/db/BillingJdbc.java`
- REST API: `src/main/java/com/billing/reconciliation/api/ReconciliationController.java`
- Configuration: `src/main/resources/application.yaml`
