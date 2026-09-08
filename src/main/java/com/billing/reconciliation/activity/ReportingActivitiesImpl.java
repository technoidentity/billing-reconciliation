package com.billing.reconciliation.activity;

import com.billing.reconciliation.config.TaskQueues;
import com.billing.reconciliation.db.BillingJdbc;
import com.billing.reconciliation.model.ReconciliationRequest;
import com.billing.reconciliation.model.ReconciliationResult;
import com.billing.reconciliation.model.StepResult;
import io.temporal.spring.boot.ActivityImpl;
import org.springframework.stereotype.Component;

@Component
@ActivityImpl(taskQueues = TaskQueues.BILLING)
public class ReportingActivitiesImpl implements ReportingActivities {

    private final BillingJdbc jdbc;

    public ReportingActivitiesImpl(BillingJdbc jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public StepResult generateReports(String runId) {
        Heartbeats.beat("generate-reports");
        jdbc.saveReport(runId, "compliance-summary.csv", "CSV", jdbc.buildComplianceCsv(runId));
        jdbc.saveReport(runId, "discrepancies.xlsx.csv", "EXCEL", jdbc.buildDiscrepancyCsv(runId));
        jdbc.logAudit(runId, "GENERATE_REPORTS", "Wrote Excel-compatible CSV compliance reports");
        return new StepResult("GENERATE_REPORTS", 2, "Compliance CSV reports generated");
    }

    @Override
    public StepResult notifyStakeholders(String runId, ReconciliationRequest request) {
        Heartbeats.beat("notify");
        String message = "Billing reconciliation " + runId + " finished. Review reports and discrepancy ids.";
        int sent = 0;
        for (String recipient : request.getRecipients()) {
            jdbc.saveNotification(runId, "EMAIL", recipient, "Billing reconciliation complete", message);
            sent++;
        }
        if (request.getTeamsChannel() != null && !request.getTeamsChannel().isBlank()) {
            jdbc.saveNotification(runId, "TEAMS", request.getTeamsChannel(), "Billing reconciliation complete", message);
            sent++;
        }
        jdbc.logAudit(runId, "NOTIFY_STAKEHOLDERS", "Sent " + sent + " email/Teams notifications");
        return new StepResult("NOTIFY_STAKEHOLDERS", sent, "Email and Teams stakeholders notified");
    }

    @Override
    public StepResult logAuditTrail(String runId, String stepName, String message) {
        jdbc.logAudit(runId, stepName, message);
        return new StepResult(stepName, 1, message);
    }

    @Override
    public void completeRun(String runId, ReconciliationResult result) {
        Heartbeats.beat("complete-run");
        jdbc.completeRun(runId, result);
    }
}
