package com.billing.reconciliation.workflow;

import com.billing.reconciliation.activity.FileActivities;
import com.billing.reconciliation.activity.ReportingActivities;
import com.billing.reconciliation.config.BillingProperties;
import com.billing.reconciliation.config.TaskQueues;
import com.billing.reconciliation.file.FileIds;
import com.billing.reconciliation.model.ActivityPolicyConfig;
import com.billing.reconciliation.model.BatchRef;
import com.billing.reconciliation.model.BatchResult;
import com.billing.reconciliation.model.CoordinatorState;
import com.billing.reconciliation.model.FileNotification;
import com.billing.reconciliation.model.FileRunSummary;
import com.billing.reconciliation.model.FileValidationResult;
import com.billing.reconciliation.model.FileWaitState;
import com.billing.reconciliation.model.ReconciliationRequest;
import com.billing.reconciliation.model.ReconciliationResult;
import com.billing.reconciliation.model.WorkflowProgress;
import io.temporal.failure.ActivityFailure;
import io.temporal.failure.ApplicationFailure;
import io.temporal.spring.boot.WorkflowImpl;
import io.temporal.workflow.Async;
import io.temporal.workflow.ChildWorkflowOptions;
import io.temporal.workflow.ContinueAsNewOptions;
import io.temporal.workflow.Promise;
import io.temporal.workflow.Workflow;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

@WorkflowImpl(taskQueues = TaskQueues.BILLING)
public class BillingReconciliationWorkflowImpl implements BillingReconciliationWorkflow {

    private static final int RECENT_RESULTS_CAP = 20;

    private ReconciliationRequest request = new ReconciliationRequest();
    private final List<FileNotification> pendingFiles = new ArrayList<>();
    private final List<FileNotification> pendingRetries = new ArrayList<>();
    private final List<FileWaitState> waitingForCorrection = new ArrayList<>();
    private final List<FileRunSummary> recentResults = new ArrayList<>();
    private int completedFileCount;

    private String runId = "";
    private String currentStep = "LISTENING";
    private String currentFileId = "";
    private String currentFileName = "";
    private String lastValidationError = "";
    private int completedBatches;
    private int totalBatches;
    private final List<String> childWorkflowIds = new ArrayList<>();
    private final Set<String> pendingChildIds = new LinkedHashSet<>();
    private final List<String> problemTxnIds = new ArrayList<>();
    private boolean waitingForSignal;
    private boolean shutdownRequested;

    @Override
    public void run(CoordinatorState incoming) {
        restore(incoming);
        FileActivities files = fileActivities();
        ReportingActivities reporting = reportingActivities();

        while (!shutdownRequested) {
            if (shouldContinueAsNew() && idle()) {
                continueAsNew();
                return;
            }
            Workflow.await(() -> shutdownRequested
                    || !pendingFiles.isEmpty()
                    || !pendingRetries.isEmpty()
                    || shouldContinueAsNew());
            if (shutdownRequested) {
                break;
            }
            if (shouldContinueAsNew() && idle()) {
                continueAsNew();
                return;
            }
            if (!pendingRetries.isEmpty()) {
                processFile(pendingRetries.remove(0), true, files, reporting);
                continue;
            }
            if (!pendingFiles.isEmpty()) {
                processFile(pendingFiles.remove(0), false, files, reporting);
            }
        }
        currentStep = "SHUTDOWN";
    }

    private void processFile(FileNotification notification, boolean retry,
                             FileActivities files, ReportingActivities reporting) {
        currentFileName = notification.getFileName();
        currentFileId = notification.getExpectedSha256() == null ? ""
                : FileIds.fromSha256(notification.getExpectedSha256());
        lastValidationError = "";
        completedBatches = 0;
        totalBatches = 0;
        childWorkflowIds.clear();
        pendingChildIds.clear();
        problemTxnIds.clear();
        waitingForSignal = true;

        currentStep = retry ? "RETRY_VALIDATE_FILE" : "VALIDATE_FILE";
        FileValidationResult validation;
        try {
            validation = files.locateAndValidate(notification);
        } catch (ActivityFailure ex) {
            // The validation activity failed (shown red in the UI) after its own retries. Keep the
            // coordinator alive and park this file for a corrected re-send (Signal 2); other files continue.
            waitForCorrection(notification, failureMessage(ex));
            return;
        }
        currentFileId = validation.getFileId() == null ? currentFileId : validation.getFileId();
        removeWaiting(notification);
        runId = Workflow.randomUUID().toString();
        currentStep = "START_RUN";
        ingestion().startRun(runId, currentFileId, Workflow.getInfo().getWorkflowId());
        reporting.logAuditTrail(runId, currentFileId, "START_RUN",
                "sha256 " + currentFileId + " (" + currentFileName + ") passed integrity check");

        currentStep = "SLICE_BATCHES";
        List<BatchRef> batches = files.sliceBatches(currentFileId, request.getBatchSize());
        totalBatches = batches.size();
        reporting.logAuditTrail(runId, currentFileId, "READ_TRANSACTIONS",
                "Spawning " + totalBatches + " child workflows for sha256 " + currentFileId);

        currentStep = "PROCESS_BATCHES";
        List<BatchResult> batchResults = processBatches(batches);
        completedBatches = batchResults.size();
        waitingForSignal = false;

        long total = 0;
        long valid = 0;
        long invalid = 0;
        long discrepancyCount = 0;
        boolean compensated = false;
        problemTxnIds.clear();
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
        reporting.generateReports(runId, currentFileId);
        currentStep = "NOTIFY_STAKEHOLDERS";
        reporting.notifyStakeholders(runId, currentFileId, request);

        ReconciliationResult result = new ReconciliationResult();
        result.setRunId(runId);
        result.setFileId(currentFileId);
        result.setFileName(currentFileName);
        result.setStatus(compensated ? "COMPLETED_WITH_COMPENSATION" : "COMPLETED");
        result.setTotalTxns(total);
        result.setValidTxns(valid);
        result.setInvalidTxns(invalid);
        result.setDiscrepancyCount(discrepancyCount);
        result.setCompensated(compensated);
        result.setBatches(batchResults);

        currentStep = "LOG_AUDIT";
        reporting.completeRun(runId, currentFileId, result);
        reporting.logAuditTrail(runId, currentFileId, "COMPLETE",
                "File completed with status " + result.getStatus());
        currentStep = "LISTENING";
        completedFileCount++;
        remember(toSummary(result));
        currentFileId = "";
        currentFileName = "";
        childWorkflowIds.clear();
        pendingChildIds.clear();
    }

    private static String failureMessage(ActivityFailure ex) {
        Throwable cause = ex.getCause();
        if (cause instanceof ApplicationFailure af) {
            String type = af.getType() == null ? "" : af.getType();
            return (type.isBlank() ? "" : type + ": ") + af.getOriginalMessage();
        }
        return ex.getMessage();
    }

    private void waitForCorrection(FileNotification notification, String message) {
        lastValidationError = message;
        currentStep = "WAITING_FOR_CORRECTION";
        waitingForSignal = true;
        upsertWaiting(notification, message);
        Workflow.setCurrentDetails("## Waiting for corrected file\n\n`"
                + notification.getFileName() + "`\n\nsha256: `"
                + (notification.getExpectedSha256() == null ? "" : notification.getExpectedSha256()) + "`\n\n"
                + message
                + "\n\nCopy the corrected CSV + SHA-256 sidecar to sftp/home, then Signal `retryCorrectedFile`.");
    }

    private List<BatchResult> processBatches(List<BatchRef> batches) {
        List<BatchResult> results = new ArrayList<>();
        String parentWorkflowId = Workflow.getInfo().getWorkflowId();
        int maxParallel = Math.max(1, request.getMaxParallelBatches());
        String runShort = runId.length() >= 8 ? runId.substring(0, 8) : runId;
        for (int i = 0; i < batches.size(); i += maxParallel) {
            int end = Math.min(i + maxParallel, batches.size());
            List<Promise<BatchResult>> promises = new ArrayList<>();
            for (int j = i; j < end; j++) {
                BatchRef batch = batches.get(j);
                String childId = parentWorkflowId + "-" + currentFileId + "-" + runShort + "-batch-" + batch.getBatchNo();
                childWorkflowIds.add(childId);
                pendingChildIds.add(childId);
                ChildWorkflowOptions.Builder childOptions = ChildWorkflowOptions.newBuilder()
                        .setWorkflowId(childId)
                        .setTaskQueue(request.getTaskQueue())
                        .setStaticSummary("sha256 " + currentFileId + " batch " + batch.getBatchNo()
                                + " ids " + batch.getFromId() + "-" + batch.getToId());
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
            waitingForSignal = true;
            Workflow.setCurrentDetails(
                    "## Child batches for sha256 `" + currentFileId + "` (`" + currentFileName + "`)\n\n"
                            + childWorkflowIds.stream().map(id -> "- `" + id + "`\n").reduce("", String::concat));
            Promise.allOf(promises).get();
            for (Promise<BatchResult> promise : promises) {
                results.add(promise.get());
                completedBatches = results.size();
            }
        }
        return results;
    }

    @Override
    public WorkflowProgress getProgress() {
        WorkflowProgress progress = new WorkflowProgress();
        progress.setRunId(runId);
        progress.setCurrentStep(currentStep);
        progress.setCurrentFileId(currentFileId);
        progress.setCurrentFileName(currentFileName);
        progress.setCompletedBatches(completedBatches);
        progress.setTotalBatches(totalBatches);
        progress.setCompletedFileCount(completedFileCount);
        progress.setChildWorkflowIds(new ArrayList<>(childWorkflowIds));
        progress.setProblemTxnIds(new ArrayList<>(problemTxnIds));
        progress.setPendingFiles(pendingFiles.stream().map(this::describeFile).toList());
        progress.setWaitingForCorrection(waitingForCorrection.stream()
                .map(w -> w.getNotification() == null ? "" : describeFile(w.getNotification()))
                .toList());
        progress.setLastValidationError(lastValidationError);
        if (!recentResults.isEmpty()) {
            progress.setLastResult(recentResults.get(recentResults.size() - 1));
        }
        progress.setWaitingForSignal(waitingForSignal || "WAITING_FOR_CORRECTION".equals(currentStep));
        progress.setHistorySizeBytes(Workflow.getInfo().getHistorySize());
        return progress;
    }

    @Override
    public void fileAvailable(FileNotification notification) {
        if (usableNotification(notification)) {
            pendingFiles.add(notification);
        }
    }

    @Override
    public void retryCorrectedFile(FileNotification notification) {
        if (usableNotification(notification)) {
            pendingRetries.add(notification);
        }
    }

    private static boolean usableNotification(FileNotification notification) {
        return notification != null
                && notification.getFileName() != null
                && !notification.getFileName().isBlank();
    }

    private String describeFile(FileNotification notification) {
        String hash = notification.getExpectedSha256();
        if (hash != null && !hash.isBlank()) {
            return FileIds.fromSha256(hash) + " (" + notification.getFileName() + ")";
        }
        return notification.getFileName();
    }

    @Override
    public void resolveDiscrepancies(String decision) {
        if (!"COMPENSATE".equalsIgnoreCase(decision)) {
            return;
        }
        var log = Workflow.getLogger(BillingReconciliationWorkflowImpl.class);
        for (String childId : new ArrayList<>(pendingChildIds)) {
            try {
                BatchReconciliationWorkflow child = Workflow.newExternalWorkflowStub(
                        BatchReconciliationWorkflow.class, childId);
                child.resolveDiscrepancies("COMPENSATE");
            } catch (Exception ex) {
                log.info("Skipped fan-out signal to {} ({})", childId, ex.getMessage());
            }
        }
    }

    @Override
    public void shutdown() {
        this.shutdownRequested = true;
    }

    private void restore(CoordinatorState incoming) {
        CoordinatorState state = incoming == null ? new CoordinatorState() : incoming;
        if (state.getRequest() != null) {
            this.request = state.getRequest();
        }
        if (state.getPendingFiles() != null) {
            pendingFiles.addAll(state.getPendingFiles());
        }
        if (state.getPendingRetries() != null) {
            pendingRetries.addAll(state.getPendingRetries());
        }
        if (state.getWaitingForCorrection() != null) {
            waitingForCorrection.addAll(state.getWaitingForCorrection());
        }
        if (state.getRecentResults() != null) {
            recentResults.addAll(state.getRecentResults());
        }
        completedFileCount = state.getCompletedFileCount();
        currentStep = "LISTENING";
    }

    private CoordinatorState snapshot() {
        CoordinatorState state = new CoordinatorState();
        state.setRequest(request);
        state.setPendingFiles(new ArrayList<>(pendingFiles));
        state.setPendingRetries(new ArrayList<>(pendingRetries));
        state.setWaitingForCorrection(new ArrayList<>(waitingForCorrection));
        state.setRecentResults(new ArrayList<>(recentResults));
        state.setCompletedFileCount(completedFileCount);
        return state;
    }

    private boolean idle() {
        return pendingChildIds.isEmpty()
                && (currentFileName == null || currentFileName.isBlank() || "LISTENING".equals(currentStep)
                || "WAITING_FOR_CORRECTION".equals(currentStep));
    }

    private boolean shouldContinueAsNew() {
        // Primary: let Temporal decide (covers history size AND event count).
        if (Workflow.getInfo().isContinueAsNewSuggested()) {
            return true;
        }
        // Optional byte override (0 = off): also CAN once history reaches the configured size (capped 50MB).
        long override = request.getContinueAsNewHistoryBytes();
        if (override <= 0) {
            return false;
        }
        override = Math.min(override, BillingProperties.CONTINUE_AS_NEW_HISTORY_BYTES_CAP);
        return Workflow.getInfo().getHistorySize() >= override;
    }

    private void continueAsNew() {
        currentStep = "CONTINUE_AS_NEW";
        BillingReconciliationWorkflow next = Workflow.newContinueAsNewStub(
                BillingReconciliationWorkflow.class,
                ContinueAsNewOptions.newBuilder().setTaskQueue(request.getTaskQueue()).build());
        next.run(snapshot());
    }

    private void upsertWaiting(FileNotification notification, String error) {
        removeWaiting(notification);
        FileWaitState wait = new FileWaitState(notification, error);
        waitingForCorrection.add(wait);
    }

    private void removeWaiting(FileNotification notification) {
        waitingForCorrection.removeIf(w -> sameFile(w.getNotification(), notification));
    }

    private static boolean sameFile(FileNotification left, FileNotification right) {
        // Filename is the stable identity across corrections (a corrected file has a new content hash).
        return left != null && right != null
                && left.getFileName() != null && left.getFileName().equals(right.getFileName());
    }

    private void remember(FileRunSummary summary) {
        recentResults.add(summary);
        while (recentResults.size() > RECENT_RESULTS_CAP) {
            recentResults.remove(0);
        }
    }

    private FileRunSummary toSummary(ReconciliationResult result) {
        FileRunSummary summary = new FileRunSummary();
        summary.setFileId(result.getFileId());
        summary.setFileName(result.getFileName());
        summary.setRunId(result.getRunId());
        summary.setStatus(result.getStatus());
        summary.setTotalTxns(result.getTotalTxns());
        summary.setValidTxns(result.getValidTxns());
        summary.setInvalidTxns(result.getInvalidTxns());
        summary.setDiscrepancyCount(result.getDiscrepancyCount());
        summary.setCompensated(result.isCompensated());
        return summary;
    }

    private FileActivities fileActivities() {
        ActivityPolicyConfig policy = request.getFileValidation() != null
                ? request.getFileValidation() : request.getIngestion();
        return Workflow.newActivityStub(FileActivities.class, ActivityStubs.options(request.getTaskQueue(), policy));
    }

    private ReportingActivities reportingActivities() {
        return Workflow.newActivityStub(
                ReportingActivities.class, ActivityStubs.options(request.getTaskQueue(), request.getReporting()));
    }

    private com.billing.reconciliation.activity.IngestionActivities ingestion() {
        return Workflow.newActivityStub(
                com.billing.reconciliation.activity.IngestionActivities.class,
                ActivityStubs.options(request.getTaskQueue(), request.getIngestion()));
    }
}
