package com.billing.reconciliation.workflow;

import com.billing.reconciliation.model.BatchRef;
import com.billing.reconciliation.model.BatchResult;
import com.billing.reconciliation.model.BatchResume;
import com.billing.reconciliation.model.DiscrepancySummary;
import com.billing.reconciliation.model.ReconciliationRequest;
import io.temporal.workflow.QueryMethod;
import io.temporal.workflow.SignalMethod;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;

/**
 * Reconciles one file batch: validate → enrich → apply billing rules → match to the GL file →
 * identify discrepancies. On a discrepancy it waits for COMPENSATE, writes compensated amounts
 * back to the batch CSV, then Continue-As-News so the pipeline re-reads file data.
 */
@WorkflowInterface
public interface BatchReconciliationWorkflow {

    @WorkflowMethod
    BatchResult process(ReconciliationRequest request, String runId, BatchRef batch, BatchResume resume);

    @QueryMethod
    DiscrepancySummary getProblems();

    @QueryMethod
    String getCurrentStep();

    @SignalMethod
    void resolveDiscrepancies(String decision);

    @SignalMethod
    void resolveDiscrepancy(String txnId, String decision);
}
