package com.billing.reconciliation.workflow;

import com.billing.reconciliation.config.TaskQueues;
import com.billing.reconciliation.activity.IngestionActivities;
import com.billing.reconciliation.activity.ReportingActivities;
import com.billing.reconciliation.model.BatchRef;
import com.billing.reconciliation.model.BatchResult;
import com.billing.reconciliation.model.ReconciliationRequest;
import com.billing.reconciliation.model.ReconciliationResult;
import com.billing.reconciliation.model.WorkflowProgress;
import io.temporal.spring.boot.WorkflowImpl;
import io.temporal.workflow.Async;
import io.temporal.workflow.ChildWorkflowOptions;
import io.temporal.workflow.Promise;
import io.temporal.workflow.Workflow;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

@WorkflowImpl(taskQueues = TaskQueues.BILLING)
public class BillingReconciliationWorkflowImpl implements BillingReconciliationWorkflow {

    private String runId = "";
    private String currentStep = "PENDING";
    private int completedBatches;
    private int totalBatches;
    private final List<String> childWorkflowIds = new ArrayList<>();
    // Children still open (removed as each completes) so fan-out only signals workflows that can receive it.
    private final Set<String> pendingChildIds = new LinkedHashSet<>();
    private final List<String> problemTxnIds = new ArrayList<>();
    private boolean waitingForSignal;

    @Override
    public ReconciliationResult run(ReconciliationRequest request) {
        String taskQueue = request.getTaskQueue();
        IngestionActivities ingestion = Workflow.newActivityStub(
                IngestionActivities.class, ActivityStubs.options(taskQueue, request.getIngestion()));
        ReportingActivities reporting = Workflow.newActivityStub(
                ReportingActivities.class, ActivityStubs.options(taskQueue, request.getReporting()));

        runId = Workflow.randomUUID().toString();
        currentStep = "START_RUN";
        ingestion.startRun(runId, Workflow.getInfo().getWorkflowId());
        reporting.logAuditTrail(runId, "START_RUN", "Daily billing reconciliation started");

        currentStep = "READ_TRANSACTIONS";
        List<BatchRef> batches = ingestion.listBatches(request.getBatchSize());
        totalBatches = batches.size();
        reporting.logAuditTrail(runId, "READ_TRANSACTIONS", "Spawning " + totalBatches + " child workflows");

        currentStep = "PROCESS_BATCHES";
        List<BatchResult> batchResults = processBatches(request, batches);
        completedBatches = batchResults.size();
        waitingForSignal = false;

        long total = 0;
        long valid = 0;
        long invalid = 0;
        long discrepancyCount = 0;
        boolean compensated = false;
        for (BatchResult batchResult : batchResults) {
            total += batchResult.getTotal();
            valid += batchResult.getValid();
            invalid += batchResult.getInvalid();
            discrepancyCount += batchResult.getProblemTxnIds().size();
            problemTxnIds.addAll(batchResult.getProblemTxnIds());
            if (batchResult.isCompensated()) {
                compensated = true;
            }
        }

        currentStep = "GENERATE_REPORTS";
        reporting.generateReports(runId);
        currentStep = "NOTIFY_STAKEHOLDERS";
        reporting.notifyStakeholders(runId, request);

        ReconciliationResult result = new ReconciliationResult();
        result.setRunId(runId);
        result.setStatus(compensated ? "COMPLETED_WITH_COMPENSATION" : "COMPLETED");
        result.setTotalTxns(total);
        result.setValidTxns(valid);
        result.setInvalidTxns(invalid);
        result.setDiscrepancyCount(discrepancyCount);
        result.setCompensated(compensated);
        result.setBatches(batchResults);

        currentStep = "LOG_AUDIT";
        reporting.completeRun(runId, result);
        reporting.logAuditTrail(runId, "COMPLETE", "Workflow completed with status " + result.getStatus());
        currentStep = "COMPLETED";
        return result;
    }

    @Override
    public WorkflowProgress getProgress() {
        WorkflowProgress progress = new WorkflowProgress();
        progress.setRunId(runId);
        progress.setCurrentStep(currentStep);
        progress.setCompletedBatches(completedBatches);
        progress.setTotalBatches(totalBatches);
        progress.setChildWorkflowIds(new ArrayList<>(childWorkflowIds));
        progress.setProblemTxnIds(new ArrayList<>(problemTxnIds));
        progress.setWaitingForSignal(waitingForSignal);
        return progress;
    }

    @Override
    public void resolveDiscrepancies(String decision) {
        var log = Workflow.getLogger(BillingReconciliationWorkflowImpl.class);
        // Only signal children still open. try/catch covers the rare race where a child completes
        // between this snapshot and the signal being delivered.
        for (String childId : new ArrayList<>(pendingChildIds)) {
            try {
                BatchReconciliationWorkflow child = Workflow.newExternalWorkflowStub(
                        BatchReconciliationWorkflow.class, childId);
                child.resolveDiscrepancies(decision);
            } catch (Exception ex) {
                log.info("Skipped fan-out signal to {} ({})", childId, ex.getMessage());
            }
        }
    }

    private List<BatchResult> processBatches(ReconciliationRequest request, List<BatchRef> batches) {
        List<BatchResult> results = new ArrayList<>();
        String parentWorkflowId = Workflow.getInfo().getWorkflowId();
        int maxParallel = Math.max(1, request.getMaxParallelBatches());
        for (int i = 0; i < batches.size(); i += maxParallel) {
            int end = Math.min(i + maxParallel, batches.size());
            List<Promise<BatchResult>> promises = new ArrayList<>();
            for (int j = i; j < end; j++) {
                BatchRef batch = batches.get(j);
                // Business-readable, parent-correlated child id, e.g. billing-reconciliation-2026-09-08-...-batch-1
                String childId = parentWorkflowId + "-batch-" + batch.getBatchNo();
                childWorkflowIds.add(childId);
                pendingChildIds.add(childId);
                ChildWorkflowOptions.Builder childOptions = ChildWorkflowOptions.newBuilder()
                        .setWorkflowId(childId)
                        .setTaskQueue(request.getTaskQueue())
                        .setStaticSummary("Batch " + batch.getBatchNo() + " ids " + batch.getFromId() + "-" + batch.getToId());
                if (request.getChildExecutionTimeoutSeconds() > 0) {
                    childOptions.setWorkflowExecutionTimeout(Duration.ofSeconds(request.getChildExecutionTimeoutSeconds()));
                }
                BatchReconciliationWorkflow child = Workflow.newChildWorkflowStub(
                        BatchReconciliationWorkflow.class, childOptions.build());
                String cid = childId;
                promises.add(Async.function(child::process, request, runId, batch, null)
                        .thenApply(res -> {
                            pendingChildIds.remove(cid);
                            return res;
                        }));
            }
            currentStep = "PROCESSING_BATCHES";
            // True while children run: any child may be paused on a discrepancy signal.
            waitingForSignal = true;
            Workflow.setCurrentDetails(
                    "## Child batches\n\n"
                            + "Open a child workflow and use **Current Details** / **Memo** for discrepancy txn ids.\n\n"
                            + childWorkflowIds.stream().map(id -> "- `" + id + "`\n").reduce("", String::concat));
            Promise.allOf(promises).get();
            for (Promise<BatchResult> promise : promises) {
                results.add(promise.get());
                completedBatches = results.size();
            }
        }
        return results;
    }
}
