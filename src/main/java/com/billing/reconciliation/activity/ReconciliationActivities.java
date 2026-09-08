package com.billing.reconciliation.activity;

import com.billing.reconciliation.model.BatchRef;
import com.billing.reconciliation.model.DiscrepancySummary;
import com.billing.reconciliation.model.StepResult;
import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;

@ActivityInterface
public interface ReconciliationActivities {

    @ActivityMethod
    StepResult queryGeneralLedger(String runId, BatchRef batch);

    @ActivityMethod
    StepResult matchTransactions(String runId, BatchRef batch, double amountTolerance);

    @ActivityMethod
    DiscrepancySummary identifyDiscrepancies(String runId, BatchRef batch);
}
