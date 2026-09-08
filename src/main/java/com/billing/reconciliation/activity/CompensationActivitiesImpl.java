package com.billing.reconciliation.activity;

import com.billing.reconciliation.config.TaskQueues;
import com.billing.reconciliation.db.BillingJdbc;
import com.billing.reconciliation.model.StepResult;
import io.temporal.spring.boot.ActivityImpl;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@ActivityImpl(taskQueues = TaskQueues.BILLING)
public class CompensationActivitiesImpl implements CompensationActivities {

    private final BillingJdbc jdbc;

    public CompensationActivitiesImpl(BillingJdbc jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public StepResult compensateDiscrepancies(String runId, List<String> txnIds) {
        Heartbeats.beat("compensate");
        long reversed = jdbc.compensateDiscrepancies(runId, txnIds);
        jdbc.logAudit(runId, "COMPENSATE", "Reversed adjustments on " + reversed + " problem ids: " + txnIds);
        return new StepResult("COMPENSATE", reversed, "Compensated " + txnIds);
    }
}
