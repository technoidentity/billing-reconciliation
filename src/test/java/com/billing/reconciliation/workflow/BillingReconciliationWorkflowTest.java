package com.billing.reconciliation.workflow;

import com.billing.reconciliation.activity.BillingRuleActivities;
import com.billing.reconciliation.activity.CompensationActivities;
import com.billing.reconciliation.activity.EnrichmentActivities;
import com.billing.reconciliation.activity.IngestionActivities;
import com.billing.reconciliation.activity.ReconciliationActivities;
import com.billing.reconciliation.activity.ReportingActivities;
import com.billing.reconciliation.model.ActivityPolicyConfig;
import com.billing.reconciliation.model.BatchRef;
import com.billing.reconciliation.model.DiscrepancySummary;
import com.billing.reconciliation.model.ReconciliationRequest;
import com.billing.reconciliation.model.ReconciliationResult;
import com.billing.reconciliation.model.RetryPolicyConfig;
import com.billing.reconciliation.model.StepResult;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import io.temporal.client.WorkflowStub;
import io.temporal.testing.TestWorkflowEnvironment;
import io.temporal.testing.TestWorkflowExtension;
import io.temporal.worker.Worker;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
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
    void completesHappyPathWithoutSignal(TestWorkflowEnvironment env, Worker worker, WorkflowClient client) {
        fixtures(worker, env, new LinkedHashSet<>());

        BillingReconciliationWorkflow workflow = client.newWorkflowStub(
                BillingReconciliationWorkflow.class,
                WorkflowOptions.newBuilder().setTaskQueue(worker.getTaskQueue()).build());

        ReconciliationRequest request = sampleRequest();
        request.setTaskQueue(worker.getTaskQueue());
        ReconciliationResult result = workflow.run(request);

        assertEquals("COMPLETED", result.getStatus());
        assertEquals(100, result.getValidTxns());
        assertEquals(0, result.getDiscrepancyCount());
        assertFalse(result.isCompensated());
    }

    @Test
    void waitsForSignalThenCompensatesAndReprocesses(TestWorkflowEnvironment env, Worker worker, WorkflowClient client) {
        Set<String> remaining = new LinkedHashSet<>(List.of("TXN-ERR-1", "TXN-ERR-2"));
        CompensationActivities compensation = fixtures(worker, env, remaining);

        BillingReconciliationWorkflow workflow = client.newWorkflowStub(
                BillingReconciliationWorkflow.class,
                WorkflowOptions.newBuilder()
                        .setWorkflowId("parent-recon")
                        .setTaskQueue(worker.getTaskQueue())
                        .build());

        ReconciliationRequest request = sampleRequest();
        request.setTaskQueue(worker.getTaskQueue());
        WorkflowClient.start(workflow::run, request);

        env.sleep(Duration.ofSeconds(1));

        String childId = workflow.getProgress().getChildWorkflowIds().get(0);
        BatchReconciliationWorkflow child = client.newWorkflowStub(BatchReconciliationWorkflow.class, childId);

        assertEquals(List.of("TXN-ERR-1", "TXN-ERR-2"), child.getProblems().getTxnIds());
        assertEquals("WAITING_FOR_SIGNAL", child.getCurrentStep());
        assertTrue(workflow.getProgress().isWaitingForSignal());

        workflow.resolveDiscrepancies("COMPENSATE");
        ReconciliationResult result = WorkflowStub.fromTyped(workflow).getResult(ReconciliationResult.class);

        assertEquals("COMPLETED_WITH_COMPENSATION", result.getStatus());
        assertTrue(result.isCompensated());
        assertEquals(2, result.getDiscrepancyCount());
        verify(compensation).compensateDiscrepancies(anyString(), anyList());
    }

    @Test
    void continueSignalReprocessesCorrectedDataWithoutCompensation(
            TestWorkflowEnvironment env, Worker worker, WorkflowClient client) {
        Set<String> remaining = new LinkedHashSet<>(List.of("TXN-ERR-9"));
        CompensationActivities compensation = fixtures(worker, env, remaining);

        BillingReconciliationWorkflow workflow = client.newWorkflowStub(
                BillingReconciliationWorkflow.class,
                WorkflowOptions.newBuilder()
                        .setWorkflowId("parent-continue")
                        .setTaskQueue(worker.getTaskQueue())
                        .build());

        ReconciliationRequest request = sampleRequest();
        request.setTaskQueue(worker.getTaskQueue());
        WorkflowClient.start(workflow::run, request);
        env.sleep(Duration.ofSeconds(1));

        // Operator corrects the data in the DB (modelled by clearing the mismatch), then signals CONTINUE.
        remaining.clear();
        workflow.resolveDiscrepancies("CONTINUE");
        ReconciliationResult result = WorkflowStub.fromTyped(workflow).getResult(ReconciliationResult.class);

        assertEquals("COMPLETED", result.getStatus());
        assertFalse(result.isCompensated());
        verify(compensation, never()).compensateDiscrepancies(anyString(), anyList());
    }

    @Test
    void resolveOneTxnIdAtATimeReprocessesUntilClean(
            TestWorkflowEnvironment env, Worker worker, WorkflowClient client) {
        Set<String> remaining = new LinkedHashSet<>(List.of("TXN-ERR-1", "TXN-ERR-2"));
        CompensationActivities compensation = fixtures(worker, env, remaining);

        BillingReconciliationWorkflow workflow = client.newWorkflowStub(
                BillingReconciliationWorkflow.class,
                WorkflowOptions.newBuilder()
                        .setWorkflowId("parent-per-id")
                        .setTaskQueue(worker.getTaskQueue())
                        .build());

        ReconciliationRequest request = sampleRequest();
        request.setTaskQueue(worker.getTaskQueue());
        WorkflowClient.start(workflow::run, request);
        env.sleep(Duration.ofSeconds(1));

        String childId = workflow.getProgress().getChildWorkflowIds().get(0);
        BatchReconciliationWorkflow child = client.newWorkflowStub(BatchReconciliationWorkflow.class, childId);

        child.resolveDiscrepancy("TXN-ERR-1", "COMPENSATE");
        env.sleep(Duration.ofSeconds(1));

        assertEquals(List.of("TXN-ERR-2"), child.getProblems().getTxnIds());
        assertEquals("WAITING_FOR_SIGNAL", child.getCurrentStep());

        child.resolveDiscrepancy("TXN-ERR-2", "COMPENSATE");
        ReconciliationResult result = WorkflowStub.fromTyped(workflow).getResult(ReconciliationResult.class);

        assertTrue(result.isCompensated());
        verify(compensation).compensateDiscrepancies(anyString(), org.mockito.ArgumentMatchers.eq(List.of("TXN-ERR-1")));
        verify(compensation).compensateDiscrepancies(anyString(), org.mockito.ArgumentMatchers.eq(List.of("TXN-ERR-2")));
    }

    /**
     * Wires mock activities. {@code remaining} models the still-mismatched txn ids in the database:
     * {@code identifyDiscrepancies} reports a snapshot of it each round, and {@code compensateDiscrepancies}
     * corrects (removes) the targeted ids — so a Continue-As-New re-run sees the corrected data.
     */
    private CompensationActivities fixtures(Worker worker, TestWorkflowEnvironment env, Set<String> remaining) {
        IngestionActivities ingestion = mock(IngestionActivities.class, withSettings().withoutAnnotations());
        EnrichmentActivities enrichment = mock(EnrichmentActivities.class, withSettings().withoutAnnotations());
        BillingRuleActivities billingRules = mock(BillingRuleActivities.class, withSettings().withoutAnnotations());
        ReconciliationActivities reconciliation = mock(ReconciliationActivities.class, withSettings().withoutAnnotations());
        CompensationActivities compensation = mock(CompensationActivities.class, withSettings().withoutAnnotations());
        ReportingActivities reporting = mock(ReportingActivities.class, withSettings().withoutAnnotations());

        BatchRef batch = new BatchRef(1, 1, 100);
        when(ingestion.listBatches(100_000)).thenReturn(List.of(batch));
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
        when(compensation.compensateDiscrepancies(anyString(), anyList()))
                .thenAnswer(inv -> {
                    List<String> ids = inv.getArgument(1);
                    remaining.removeAll(ids);
                    return new StepResult("COMPENSATE", ids.size(), "ok");
                });
        when(reporting.generateReports(anyString())).thenReturn(new StepResult("GENERATE_REPORTS", 1, "ok"));
        when(reporting.notifyStakeholders(anyString(), any())).thenReturn(new StepResult("NOTIFY_STAKEHOLDERS", 2, "ok"));
        when(reporting.logAuditTrail(anyString(), anyString(), anyString())).thenReturn(new StepResult("AUDIT", 1, "ok"));

        worker.registerActivitiesImplementations(ingestion, enrichment, billingRules, reconciliation, compensation, reporting);
        env.start();
        return compensation;
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
        request.setIngestion(policy());
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
