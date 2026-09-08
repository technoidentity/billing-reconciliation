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
 * Reconciles one 100K-transaction batch: validate → enrich → apply billing rules → match to the GL →
 * identify discrepancies. On a discrepancy it surfaces the problem txn ids and waits for a resolution
 * signal, then Continue-As-News into a fresh run that re-processes the corrected data.
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
