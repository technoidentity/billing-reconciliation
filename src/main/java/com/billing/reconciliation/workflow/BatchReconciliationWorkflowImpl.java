package com.billing.reconciliation.workflow;

import com.billing.reconciliation.config.TaskQueues;
import com.billing.reconciliation.activity.BillingRuleActivities;
import com.billing.reconciliation.activity.CompensationActivities;
import com.billing.reconciliation.activity.EnrichmentActivities;
import com.billing.reconciliation.activity.IngestionActivities;
import com.billing.reconciliation.activity.ReconciliationActivities;
import com.billing.reconciliation.model.BatchRef;
import com.billing.reconciliation.model.BatchResult;
import com.billing.reconciliation.model.BatchResume;
import com.billing.reconciliation.model.DiscrepancySummary;
import com.billing.reconciliation.model.ReconciliationRequest;
import com.billing.reconciliation.model.StepResult;
import io.temporal.spring.boot.WorkflowImpl;
import io.temporal.workflow.ContinueAsNewOptions;
import io.temporal.workflow.Workflow;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@WorkflowImpl(taskQueues = TaskQueues.BILLING)
public class BatchReconciliationWorkflowImpl implements BatchReconciliationWorkflow {

    private String currentStep = "PENDING";
    private boolean resolved;
    private String signaledTxnId = "";
    private DiscrepancySummary problems = new DiscrepancySummary();

    @Override
    public BatchResult process(ReconciliationRequest request, String runId, BatchRef batch, BatchResume resume) {
        String taskQueue = request.getTaskQueue();
        IngestionActivities ingestion = Workflow.newActivityStub(
                IngestionActivities.class, ActivityStubs.options(taskQueue, request.getIngestion()));
        EnrichmentActivities enrichment = Workflow.newActivityStub(
                EnrichmentActivities.class, ActivityStubs.options(taskQueue, request.getEnrichment()));
        BillingRuleActivities billingRules = Workflow.newActivityStub(
                BillingRuleActivities.class, ActivityStubs.options(taskQueue, request.getBillingRules()));
        ReconciliationActivities reconciliation = Workflow.newActivityStub(
                ReconciliationActivities.class, ActivityStubs.options(taskQueue, request.getReconciliation()));
        CompensationActivities compensation = Workflow.newActivityStub(
                CompensationActivities.class, ActivityStubs.options(taskQueue, request.getCompensation()));

        var log = Workflow.getLogger(BatchReconciliationWorkflowImpl.class);

        int round = resume == null ? 1 : resume.getRound();
        boolean everCompensated = resume != null && resume.isCompensated();

        log.info("Batch {} file {} reconciliation round {} (ids {}-{})",
                batch.getBatchNo(), batch.getFileId(), round, batch.getFromId(), batch.getToId());
        currentStep = "VALIDATE_SCHEMA";
        StepResult validated = ingestion.validateSchema(runId, batch);
        currentStep = "HANDLE_VALIDATION_ERRORS";
        StepResult errors = ingestion.handleValidationErrors(runId, batch);
        currentStep = "FETCH_VENDOR_DATA";
        enrichment.fetchVendorData(runId, batch);
        currentStep = "FETCH_CUSTOMER_DATA";
        enrichment.fetchCustomerData(runId, batch);
        currentStep = "ENRICH_RECORDS";
        StepResult enriched = enrichment.enrichRecords(runId, batch);
        currentStep = "APPLY_BILLING_RULES";
        billingRules.applyBillingRules(runId, batch, request);
        currentStep = "CALCULATE_ADJUSTMENTS";
        billingRules.calculateAdjustments(runId, batch, request);
        currentStep = "APPLY_PENALTIES";
        billingRules.applyPenalties(runId, batch, request);
        currentStep = "QUERY_GL";
        reconciliation.queryGeneralLedger(runId, batch);
        currentStep = "MATCH_TRANSACTIONS";
        reconciliation.matchTransactions(runId, batch, request.getAmountTolerance());
        currentStep = "IDENTIFY_DISCREPANCIES";
        problems = reconciliation.identifyDiscrepancies(runId, batch);

        long validCount = validated.getCount();
        long invalidCount = errors.getCount();
        long processedCount = enriched.getCount();
        List<String> firstSeen = resume == null
                ? new ArrayList<>(problems.getTxnIds())
                : new ArrayList<>(resume.getOriginalTxnIds());

        if (problems.getCount() == 0) {
            currentStep = "COMPLETED";
            showDiscrepancyIdsInUi(batch.getBatchNo(), List.of());
            log.info("Batch {} clean after round {}", batch.getBatchNo(), round);
            return buildResult(batch, validCount, invalidCount, processedCount, everCompensated, firstSeen);
        }

        currentStep = "WAITING_FOR_SIGNAL";
        List<String> flagged = new ArrayList<>(problems.getTxnIds());
        showDiscrepancyIdsInUi(batch.getBatchNo(), flagged);
        log.info("Batch {} round {} waiting on discrepancy ids: {}", batch.getBatchNo(), round, flagged);

        if (round >= request.getMaxDiscrepancyRounds()) {
            currentStep = "COMPLETED_WITH_UNRESOLVED";
            log.warn("Batch {} still has {} unresolved discrepancies after {} rounds; completing",
                    batch.getBatchNo(), flagged.size(), round);
            return buildResult(batch, validCount, invalidCount, processedCount, everCompensated, firstSeen);
        }

        Workflow.await(() -> this.resolved);
        resolved = false;

        List<String> targeted = idsToResolve(flagged, signaledTxnId);
        signaledTxnId = "";

        boolean compensatedThisRound = false;
        if (!targeted.isEmpty()) {
            currentStep = "COMPENSATE";
            compensation.compensateDiscrepancies(runId, batch, targeted);
            compensatedThisRound = true;
        }

        currentStep = "CONTINUE_AS_NEW";
        BatchResume next = new BatchResume();
        next.setRound(round + 1);
        next.setCompensated(everCompensated || compensatedThisRound);
        next.setOriginalTxnIds(firstSeen);
        return continueAsNew(request, runId, batch, taskQueue, next);
    }

    private static BatchResult buildResult(BatchRef batch, long validCount, long invalidCount,
                                           long processedCount, boolean compensated, List<String> problemTxnIds) {
        BatchResult result = new BatchResult();
        result.setBatchNo(batch.getBatchNo());
        result.setTotal(validCount + invalidCount);
        result.setValid(validCount);
        result.setInvalid(invalidCount);
        result.setProcessed(processedCount);
        result.setProblemTxnIds(new ArrayList<>(problemTxnIds));
        result.setCompensated(compensated);
        return result;
    }

    private static void showDiscrepancyIdsInUi(int batchNo, List<String> txnIds) {
        List<String> ids = txnIds == null ? List.of() : txnIds;
        StringBuilder details = new StringBuilder();
        details.append("## Batch ").append(batchNo).append(" discrepancy ids\n\n");
        if (ids.isEmpty()) {
            details.append("No remaining discrepancy ids.\n");
        } else {
            details.append("Discrepancy resolution uses **COMPENSATE only** (align billing amounts to the GL file).\n\n");
            details.append("`POST /api/reconciliation/batches/{this-workflow-id}/resolve`\n\n");
            details.append("```json\n{\"txnId\":\"<id below>\",\"decision\":\"COMPENSATE\"}\n```\n\n");
            details.append("Omit `txnId` to compensate every id below.\n\n");
            for (String id : ids) {
                details.append("- `").append(id).append("`\n");
            }
        }
        Workflow.setCurrentDetails(details.toString());
        Map<String, Object> memo = new LinkedHashMap<>();
        memo.put("discrepancyTxnIds", ids);
        memo.put("discrepancyCount", ids.size());
        Workflow.upsertMemo(memo);
    }

    private static List<String> idsToResolve(List<String> pending, String txnId) {
        if (txnId == null || txnId.isBlank()) {
            return new ArrayList<>(pending);
        }
        if (!pending.contains(txnId)) {
            return List.of();
        }
        return List.of(txnId);
    }

    private static BatchResult continueAsNew(
            ReconciliationRequest request,
            String runId,
            BatchRef batch,
            String taskQueue,
            BatchResume resume) {
        BatchReconciliationWorkflow next = Workflow.newContinueAsNewStub(
                BatchReconciliationWorkflow.class,
                ContinueAsNewOptions.newBuilder().setTaskQueue(taskQueue).build());
        return next.process(request, runId, batch, resume);
    }

    @Override
    public DiscrepancySummary getProblems() {
        return problems;
    }

    @Override
    public String getCurrentStep() {
        return currentStep;
    }

    @Override
    public void resolveDiscrepancies(String decision) {
        if (!"COMPENSATE".equalsIgnoreCase(decision)) {
            return;
        }
        this.signaledTxnId = "";
        this.resolved = true;
    }

    @Override
    public void resolveDiscrepancy(String txnId, String decision) {
        if (!"COMPENSATE".equalsIgnoreCase(decision)) {
            return;
        }
        this.signaledTxnId = txnId == null ? "" : txnId;
        this.resolved = true;
    }
}
