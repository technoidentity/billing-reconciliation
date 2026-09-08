package com.billing.reconciliation.activity;

import com.billing.reconciliation.model.BatchRef;
import com.billing.reconciliation.model.StepResult;
import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;

@ActivityInterface
public interface EnrichmentActivities {

    @ActivityMethod
    StepResult fetchVendorData(String runId, BatchRef batch);

    @ActivityMethod
    StepResult fetchCustomerData(String runId, BatchRef batch);

    @ActivityMethod
    StepResult enrichRecords(String runId, BatchRef batch);
}
