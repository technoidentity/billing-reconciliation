package com.billing.reconciliation.workflow;

import com.billing.reconciliation.activity.BillingRuleActivities;
import com.billing.reconciliation.activity.CompensationActivities;
import com.billing.reconciliation.activity.EnrichmentActivities;
import com.billing.reconciliation.activity.FileActivities;
import com.billing.reconciliation.activity.IngestionActivities;
import com.billing.reconciliation.activity.ReconciliationActivities;
import com.billing.reconciliation.activity.ReportingActivities;
import com.billing.reconciliation.model.ActivityPolicyConfig;
import com.billing.reconciliation.model.BatchRef;
import com.billing.reconciliation.model.CoordinatorState;
import com.billing.reconciliation.model.DiscrepancySummary;
import com.billing.reconciliation.model.FileNotification;
import com.billing.reconciliation.model.FileValidationResult;
import com.billing.reconciliation.model.ReconciliationRequest;
import com.billing.reconciliation.model.RetryPolicyConfig;
import com.billing.reconciliation.model.StepResult;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import io.temporal.client.WorkflowStub;
import io.temporal.failure.ApplicationFailure;
import io.temporal.testing.TestWorkflowEnvironment;
import io.temporal.testing.TestWorkflowExtension;
import io.temporal.worker.Worker;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

class BillingReconciliationWorkflowTest {

    @RegisterExtension
    static final TestWorkflowExtension testWorkflowExtension = TestWorkflowExtension.newBuilder()
            .setWorkflowTypes(BillingReconciliationWorkflowImpl.class, BatchReconciliationWorkflowImpl.class)
            .setDoNotStart(true)
            .build();

    @Test
    void processesSignaledFileWithoutDiscrepancies(
            TestWorkflowEnvironment env, Worker worker, WorkflowClient client) {
        fixtures(worker, env, new LinkedHashSet<>(), FileValidationResult.ok(
                "billing-demo", "billing-demo.csv", "gl-demo.csv", "abc"));

        BillingReconciliationWorkflow workflow = startCoordinator(client, worker);
        workflow.fileAvailable(new FileNotification("billing-demo.csv"));
        env.sleep(Duration.ofSeconds(2));

        assertEquals("COMPLETED", workflow.getProgress().getLastResult().getStatus());
        assertEquals(100, workflow.getProgress().getLastResult().getValidTxns());
        assertFalse(workflow.getProgress().getLastResult().isCompensated());

        workflow.shutdown();
        WorkflowStub.fromTyped(workflow).getResult(Void.class);
    }

    @Test
    void waitsForCorrectedFileThenRetriesValidation(
            TestWorkflowEnvironment env, Worker worker, WorkflowClient client) {
        FileValidationResult failed = FileValidationResult.failed(
                "billing-demo", "billing-demo.csv", "HASH_MISMATCH");
        FileValidationResult ok = FileValidationResult.ok(
                "billing-demo", "billing-demo.csv", "gl-demo.csv", "abc");
        fixtures(worker, env, new LinkedHashSet<>(), failed, ok);

        BillingReconciliationWorkflow workflow = startCoordinator(client, worker);
        workflow.fileAvailable(new FileNotification("billing-demo.csv"));
        env.sleep(Duration.ofSeconds(1));

        assertEquals("WAITING_FOR_CORRECTION", workflow.getProgress().getCurrentStep());
        assertTrue(workflow.getProgress().getWaitingForCorrection().contains("billing-demo.csv"));

        workflow.retryCorrectedFile(new FileNotification("billing-demo.csv"));
        env.sleep(Duration.ofSeconds(2));

        assertEquals("COMPLETED", workflow.getProgress().getLastResult().getStatus());
        assertEquals(1, workflow.getProgress().getCompletedFileCount());

        workflow.shutdown();
        WorkflowStub.fromTyped(workflow).getResult(Void.class);
    }

    @Test
    void waitsForCompensateThenReprocesses(
            TestWorkflowEnvironment env, Worker worker, WorkflowClient client) {
        Set<String> remaining = new LinkedHashSet<>(List.of("TXN-ERR-1", "TXN-ERR-2"));
        CompensationActivities compensation = fixtures(
                worker, env, remaining,
                FileValidationResult.ok("billing-demo", "billing-demo.csv", "gl-demo.csv", "abc"));

        BillingReconciliationWorkflow workflow = startCoordinator(client, worker, "parent-recon");
        workflow.fileAvailable(new FileNotification("billing-demo.csv"));
        env.sleep(Duration.ofSeconds(2));

        String childId = workflow.getProgress().getChildWorkflowIds().get(0);
        BatchReconciliationWorkflow child = client.newWorkflowStub(BatchReconciliationWorkflow.class, childId);

        assertEquals(List.of("TXN-ERR-1", "TXN-ERR-2"), child.getProblems().getTxnIds());
        assertEquals("WAITING_FOR_SIGNAL", child.getCurrentStep());
        assertTrue(workflow.getProgress().isWaitingForSignal());

        workflow.resolveDiscrepancies("COMPENSATE");
        env.sleep(Duration.ofSeconds(2));

        assertEquals("COMPLETED_WITH_COMPENSATION", workflow.getProgress().getLastResult().getStatus());
        assertTrue(workflow.getProgress().getLastResult().isCompensated());
        verify(compensation).compensateDiscrepancies(anyString(), any(), anyList());

        workflow.shutdown();
        WorkflowStub.fromTyped(workflow).getResult(Void.class);
    }

    @Test
    void ignoreContinueAndOnlyCompensate(
            TestWorkflowEnvironment env, Worker worker, WorkflowClient client) {
        Set<String> remaining = new LinkedHashSet<>(List.of("TXN-ERR-9"));
        CompensationActivities compensation = fixtures(
                worker, env, remaining,
                FileValidationResult.ok("billing-demo", "billing-demo.csv", "gl-demo.csv", "abc"));

        BillingReconciliationWorkflow workflow = startCoordinator(client, worker, "parent-continue");
        workflow.fileAvailable(new FileNotification("billing-demo.csv"));
        env.sleep(Duration.ofSeconds(2));

        workflow.resolveDiscrepancies("CONTINUE");
        env.sleep(Duration.ofSeconds(1));
        verify(compensation, never()).compensateDiscrepancies(anyString(), any(), anyList());
        assertEquals("WAITING_FOR_SIGNAL",
                client.newWorkflowStub(BatchReconciliationWorkflow.class,
                        workflow.getProgress().getChildWorkflowIds().get(0)).getCurrentStep());

        workflow.resolveDiscrepancies("COMPENSATE");
        env.sleep(Duration.ofSeconds(2));
        assertTrue(workflow.getProgress().getLastResult().isCompensated());

        workflow.shutdown();
        WorkflowStub.fromTyped(workflow).getResult(Void.class);
    }

    @Test
    void resolveOneTxnIdAtATimeReprocessesUntilClean(
            TestWorkflowEnvironment env, Worker worker, WorkflowClient client) {
        Set<String> remaining = new LinkedHashSet<>(List.of("TXN-ERR-1", "TXN-ERR-2"));
        CompensationActivities compensation = fixtures(
                worker, env, remaining,
                FileValidationResult.ok("billing-demo", "billing-demo.csv", "gl-demo.csv", "abc"));

        BillingReconciliationWorkflow workflow = startCoordinator(client, worker, "parent-per-id");
        workflow.fileAvailable(new FileNotification("billing-demo.csv"));
        env.sleep(Duration.ofSeconds(2));

        String childId = workflow.getProgress().getChildWorkflowIds().get(0);
        BatchReconciliationWorkflow child = client.newWorkflowStub(BatchReconciliationWorkflow.class, childId);

        child.resolveDiscrepancy("TXN-ERR-1", "COMPENSATE");
        env.sleep(Duration.ofSeconds(2));

        assertEquals(List.of("TXN-ERR-2"), child.getProblems().getTxnIds());
        assertEquals("WAITING_FOR_SIGNAL", child.getCurrentStep());

        child.resolveDiscrepancy("TXN-ERR-2", "COMPENSATE");
        env.sleep(Duration.ofSeconds(2));

        assertTrue(workflow.getProgress().getLastResult().isCompensated());
        verify(compensation, times(1)).compensateDiscrepancies(
                anyString(), any(), org.mockito.ArgumentMatchers.eq(List.of("TXN-ERR-1")));
        verify(compensation, times(1)).compensateDiscrepancies(
                anyString(), any(), org.mockito.ArgumentMatchers.eq(List.of("TXN-ERR-2")));

        workflow.shutdown();
        WorkflowStub.fromTyped(workflow).getResult(Void.class);
    }

    private CompensationActivities fixtures(
            Worker worker,
            TestWorkflowEnvironment env,
            Set<String> remaining,
            FileValidationResult validation,
            FileValidationResult... laterValidations) {
        FileActivities fileActivities = mock(FileActivities.class, withSettings().withoutAnnotations());
        IngestionActivities ingestion = mock(IngestionActivities.class, withSettings().withoutAnnotations());
        EnrichmentActivities enrichment = mock(EnrichmentActivities.class, withSettings().withoutAnnotations());
        BillingRuleActivities billingRules = mock(BillingRuleActivities.class, withSettings().withoutAnnotations());
        ReconciliationActivities reconciliation = mock(ReconciliationActivities.class, withSettings().withoutAnnotations());
        CompensationActivities compensation = mock(CompensationActivities.class, withSettings().withoutAnnotations());
        ReportingActivities reporting = mock(ReportingActivities.class, withSettings().withoutAnnotations());

        BatchRef batch = new BatchRef(1, 1, 100, "billing-demo");
        List<FileValidationResult> sequence = new ArrayList<>();
        sequence.add(validation);
        if (laterValidations != null) {
            sequence.addAll(Arrays.asList(laterValidations));
        }
        AtomicInteger call = new AtomicInteger(0);
        when(fileActivities.locateAndValidate(any())).thenAnswer(inv -> {
            int i = Math.min(call.getAndIncrement(), sequence.size() - 1);
            FileValidationResult v = sequence.get(i);
            if (!v.isValid()) {
                // A failed validation is modelled as the activity throwing (red in the UI); the parent
                // catches it and parks the file for a corrected re-send.
                throw ApplicationFailure.newNonRetryableFailure(
                        v.getMessage() == null ? "VALIDATION_FAILED" : v.getMessage(), "HashMismatch");
            }
            return v;
        });
        when(fileActivities.sliceBatches(anyString(), anyInt())).thenReturn(List.of(batch));
        when(ingestion.validateSchema(anyString(), any())).thenReturn(new StepResult("VALIDATE_SCHEMA", 100, "ok"));
        when(ingestion.handleValidationErrors(anyString(), any())).thenReturn(new StepResult("HANDLE_VALIDATION_ERRORS", 0, "ok"));
        when(enrichment.fetchVendorData(anyString(), any())).thenReturn(new StepResult("FETCH_VENDOR_DATA", 10, "ok"));
        when(enrichment.fetchCustomerData(anyString(), any())).thenReturn(new StepResult("FETCH_CUSTOMER_DATA", 10, "ok"));
        when(enrichment.enrichRecords(anyString(), any())).thenReturn(new StepResult("ENRICH_RECORDS", 100, "ok"));
        when(billingRules.applyBillingRules(anyString(), any(), any())).thenReturn(new StepResult("APPLY_BILLING_RULES", 5, "ok"));
        when(billingRules.calculateAdjustments(anyString(), any(), any())).thenReturn(new StepResult("CALCULATE_ADJUSTMENTS", 5, "ok"));
        when(billingRules.applyPenalties(anyString(), any(), any())).thenReturn(new StepResult("APPLY_PENALTIES", 2, "ok"));
        when(reconciliation.queryGeneralLedger(anyString(), any())).thenReturn(new StepResult("QUERY_GL", 100, "ok"));
        when(reconciliation.matchTransactions(anyString(), any(), anyDouble()))
                .thenAnswer(inv -> new StepResult("MATCH_TRANSACTIONS", remaining.size(), "ok"));
        when(reconciliation.identifyDiscrepancies(anyString(), any()))
                .thenAnswer(inv -> {
                    List<String> ids = new ArrayList<>(remaining);
                    return new DiscrepancySummary(ids.size(), ids.size() * 10.0, ids);
                });
        when(compensation.compensateDiscrepancies(anyString(), any(), anyList()))
                .thenAnswer(inv -> {
                    List<String> ids = inv.getArgument(2);
                    remaining.removeAll(ids);
                    return new StepResult("COMPENSATE", ids.size(), "ok");
                });
        when(reporting.generateReports(anyString(), anyString())).thenReturn(new StepResult("GENERATE_REPORTS", 1, "ok"));
        when(reporting.notifyStakeholders(anyString(), anyString(), any())).thenReturn(new StepResult("NOTIFY_STAKEHOLDERS", 2, "ok"));
        when(reporting.logAuditTrail(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(new StepResult("AUDIT", 1, "ok"));

        worker.registerActivitiesImplementations(
                fileActivities, ingestion, enrichment, billingRules, reconciliation, compensation, reporting);
        env.start();
        return compensation;
    }

    private BillingReconciliationWorkflow startCoordinator(WorkflowClient client, Worker worker) {
        return startCoordinator(client, worker, "billing-reconciliation-coordinator");
    }

    private BillingReconciliationWorkflow startCoordinator(WorkflowClient client, Worker worker, String workflowId) {
        BillingReconciliationWorkflow workflow = client.newWorkflowStub(
                BillingReconciliationWorkflow.class,
                WorkflowOptions.newBuilder()
                        .setWorkflowId(workflowId)
                        .setTaskQueue(worker.getTaskQueue())
                        .build());
        CoordinatorState state = new CoordinatorState();
        ReconciliationRequest request = sampleRequest();
        request.setTaskQueue(worker.getTaskQueue());
        state.setRequest(request);
        WorkflowClient.start(workflow::run, state);
        return workflow;
    }

    private static ReconciliationRequest sampleRequest() {
        ReconciliationRequest request = new ReconciliationRequest();
        request.setTaskQueue("test-queue");
        request.setBatchSize(100_000);
        request.setMaxParallelBatches(12);
        request.setAmountTolerance(0.01);
        request.setMaxDiscrepancyRounds(10);
        request.setSurchargeThreshold(10000);
        request.setSurchargeRate(0.02);
        request.setMidTierThreshold(1000);
        request.setMidTierFlatFee(5);
        request.setDiscountThreshold(5000);
        request.setDiscountRate(0.05);
        request.setLateDays(30);
        request.setLateFeeRate(0.02);
        request.setRecipients(List.of("ops@example.com"));
        request.setTeamsChannel("billing-ops-teams");
        request.setWorkflowExecutionTimeoutSeconds(0);
        request.setChildExecutionTimeoutSeconds(0);
        request.setContinueAsNewHistoryBytes(50L * 1024 * 1024);
        request.setMaxFileValidationAttempts(3);
        request.setFileValidationRetryIntervalSeconds(1);
        request.setIngestion(policy());
        request.setFileValidation(policy());
        request.setEnrichment(policy());
        request.setBillingRules(policy());
        request.setReconciliation(policy());
        request.setReporting(policy());
        request.setCompensation(policy());
        return request;
    }

    private static ActivityPolicyConfig policy() {
        ActivityPolicyConfig policy = new ActivityPolicyConfig();
        policy.setStartToCloseSeconds(30);
        policy.setHeartbeatSeconds(10);
        RetryPolicyConfig retry = new RetryPolicyConfig();
        retry.setInitialIntervalSeconds(1);
        retry.setBackoffCoefficient(2.0);
        retry.setMaximumIntervalSeconds(16);
        retry.setMaximumAttempts(3);
        policy.setRetry(retry);
        return policy;
    }
}
