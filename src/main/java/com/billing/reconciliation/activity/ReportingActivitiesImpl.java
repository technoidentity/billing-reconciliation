package com.billing.reconciliation.activity;

import com.billing.reconciliation.config.TaskQueues;
import com.billing.reconciliation.file.ReconciliationFileStore;
import com.billing.reconciliation.model.ReconciliationRequest;
import com.billing.reconciliation.model.ReconciliationResult;
import com.billing.reconciliation.model.StepResult;
import io.temporal.spring.boot.ActivityImpl;
import org.springframework.stereotype.Component;

@Component
@ActivityImpl(taskQueues = TaskQueues.BILLING)
public class ReportingActivitiesImpl implements ReportingActivities {

    private final ReconciliationFileStore files;

    public ReportingActivitiesImpl(ReconciliationFileStore files) {
        this.files = files;
    }

    @Override
    public StepResult generateReports(String runId, String fileId) {
        Heartbeats.beat("generate-reports");
        files.saveReport(fileId, "compliance-summary.csv", files.buildComplianceCsv(fileId));
        files.saveReport(fileId, "discrepancies.csv", files.buildDiscrepancyCsv(fileId));
        files.logAudit(fileId, "GENERATE_REPORTS", "Wrote CSV compliance reports to outbound/" + fileId);
        return new StepResult("GENERATE_REPORTS", 2, "Compliance CSV reports generated");
    }

    @Override
    public StepResult notifyStakeholders(String runId, String fileId, ReconciliationRequest request) {
        Heartbeats.beat("notify");
        String message = "Billing reconciliation " + runId + " finished for file " + fileId + ". Review outbound reports.";
        int sent = 0;
        for (String recipient : request.getRecipients()) {
            files.saveNotification(fileId, "EMAIL", recipient, "Billing reconciliation complete", message);
            sent++;
        }
        if (request.getTeamsChannel() != null && !request.getTeamsChannel().isBlank()) {
            files.saveNotification(fileId, "TEAMS", request.getTeamsChannel(), "Billing reconciliation complete", message);
            sent++;
        }
        files.logAudit(fileId, "NOTIFY_STAKEHOLDERS", "Recorded " + sent + " email/Teams notifications");
        return new StepResult("NOTIFY_STAKEHOLDERS", sent, "Email and Teams stakeholders notified");
    }

    @Override
    public StepResult logAuditTrail(String runId, String fileId, String stepName, String message) {
        files.logAudit(fileId, stepName, message);
        return new StepResult(stepName, 1, message);
    }

    @Override
    public void completeRun(String runId, String fileId, ReconciliationResult result) {
        Heartbeats.beat("complete-run");
        files.writeResult(fileId, result);
        files.logAudit(fileId, "COMPLETE", "File " + fileId + " completed with status " + result.getStatus());
    }
}
