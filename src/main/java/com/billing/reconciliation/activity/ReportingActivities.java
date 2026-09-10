package com.billing.reconciliation.activity;

import com.billing.reconciliation.model.ReconciliationRequest;
import com.billing.reconciliation.model.ReconciliationResult;
import com.billing.reconciliation.model.StepResult;
import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;

@ActivityInterface
public interface ReportingActivities {

    @ActivityMethod
    StepResult generateReports(String runId, String fileId);

    @ActivityMethod
    StepResult notifyStakeholders(String runId, String fileId, ReconciliationRequest request);

    @ActivityMethod
    StepResult logAuditTrail(String runId, String fileId, String stepName, String message);

    @ActivityMethod
    void completeRun(String runId, String fileId, ReconciliationResult result);
}
