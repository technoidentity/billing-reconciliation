package com.billing.reconciliation.activity;

import com.billing.reconciliation.model.BatchRef;
import com.billing.reconciliation.model.StepResult;
import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;

@ActivityInterface
public interface IngestionActivities {

    @ActivityMethod
    void startRun(String runId, String fileId, String workflowId);

    @ActivityMethod
    StepResult validateSchema(String runId, BatchRef batch);

    @ActivityMethod
    StepResult handleValidationErrors(String runId, BatchRef batch);
}
