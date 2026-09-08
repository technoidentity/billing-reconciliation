package com.billing.reconciliation.activity;

import com.billing.reconciliation.model.BatchRef;
import com.billing.reconciliation.model.ReconciliationRequest;
import com.billing.reconciliation.model.StepResult;
import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;

@ActivityInterface
public interface BillingRuleActivities {

    @ActivityMethod
    StepResult applyBillingRules(String runId, BatchRef batch, ReconciliationRequest request);

    @ActivityMethod
    StepResult calculateAdjustments(String runId, BatchRef batch, ReconciliationRequest request);

    @ActivityMethod
    StepResult applyPenalties(String runId, BatchRef batch, ReconciliationRequest request);
}
