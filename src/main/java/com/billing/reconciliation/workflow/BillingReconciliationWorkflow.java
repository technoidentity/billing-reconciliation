package com.billing.reconciliation.workflow;

import com.billing.reconciliation.model.CoordinatorState;
import com.billing.reconciliation.model.FileNotification;
import com.billing.reconciliation.model.WorkflowProgress;
import io.temporal.workflow.QueryMethod;
import io.temporal.workflow.SignalMethod;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;

@WorkflowInterface
public interface BillingReconciliationWorkflow {

    /** Long-running coordinator: wait for file signals, validate, fan out children. */
    @WorkflowMethod
    void run(CoordinatorState state);

    @QueryMethod
    WorkflowProgress getProgress();

    /** File is already copied into destination; start SHA-256 validation. */
    @SignalMethod
    void fileAvailable(FileNotification notification);

    /** Retry a file that failed integrity (API copies the corrected drop first). */
    @SignalMethod
    void retryCorrectedFile(FileNotification notification);

    /** Fan COMPENSATE to every child still waiting on discrepancies. */
    @SignalMethod
    void resolveDiscrepancies(String decision);

    @SignalMethod
    void shutdown();
}
