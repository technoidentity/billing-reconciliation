package com.billing.reconciliation.workflow;

import com.billing.reconciliation.model.ReconciliationRequest;
import com.billing.reconciliation.model.ReconciliationResult;
import com.billing.reconciliation.model.WorkflowProgress;
import io.temporal.workflow.QueryMethod;
import io.temporal.workflow.SignalMethod;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;

@WorkflowInterface
public interface BillingReconciliationWorkflow {

    @WorkflowMethod
    ReconciliationResult run(ReconciliationRequest request);

    @QueryMethod
    WorkflowProgress getProgress();

    @SignalMethod
    void resolveDiscrepancies(String decision);
}
