package com.billing.reconciliation.activity;

import com.billing.reconciliation.model.BatchRef;
import com.billing.reconciliation.model.StepResult;
import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;

import java.util.List;

@ActivityInterface
public interface IngestionActivities {

    @ActivityMethod
    void startRun(String runId, String workflowId);

    @ActivityMethod
    List<BatchRef> listBatches(int batchSize);

    @ActivityMethod
    StepResult validateSchema(String runId, BatchRef batch);

    @ActivityMethod
    StepResult handleValidationErrors(String runId, BatchRef batch);
}
