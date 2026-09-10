package com.billing.reconciliation.activity;

import com.billing.reconciliation.model.BatchRef;
import com.billing.reconciliation.model.StepResult;
import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;

import java.util.List;

@ActivityInterface
public interface CompensationActivities {

    @ActivityMethod
    StepResult compensateDiscrepancies(String runId, BatchRef batch, List<String> txnIds);
}
