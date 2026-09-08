package com.billing.reconciliation.activity;

import com.billing.reconciliation.config.TaskQueues;
import com.billing.reconciliation.db.BillingJdbc;
import com.billing.reconciliation.model.BatchRef;
import com.billing.reconciliation.model.ReconciliationRequest;
import com.billing.reconciliation.model.StepResult;
import io.temporal.spring.boot.ActivityImpl;
import org.springframework.stereotype.Component;

@Component
@ActivityImpl(taskQueues = TaskQueues.BILLING)
public class BillingRuleActivitiesImpl implements BillingRuleActivities {

    private final BillingJdbc jdbc;

    public BillingRuleActivitiesImpl(BillingJdbc jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public StepResult applyBillingRules(String runId, BatchRef batch, ReconciliationRequest request) {
        Heartbeats.beat("billing-rules-batch-" + batch.getBatchNo());
        long count = jdbc.applySurcharge(runId, batch, request.getSurchargeThreshold(), request.getSurchargeRate(),
                request.getMidTierThreshold(), request.getMidTierFlatFee());
        return new StepResult("APPLY_BILLING_RULES", count,
                "Applied if-else billing tiers (surcharge / mid-tier fee / pass-through) to batch " + batch.getBatchNo());
    }

    @Override
    public StepResult calculateAdjustments(String runId, BatchRef batch, ReconciliationRequest request) {
        Heartbeats.beat("adjustments-batch-" + batch.getBatchNo());
        long count = jdbc.applyDiscount(runId, batch, request.getDiscountThreshold(), request.getDiscountRate());
        return new StepResult("CALCULATE_ADJUSTMENTS", count, "Applied discounts to batch " + batch.getBatchNo());
    }

    @Override
    public StepResult applyPenalties(String runId, BatchRef batch, ReconciliationRequest request) {
        Heartbeats.beat("penalties-batch-" + batch.getBatchNo());
        long count = jdbc.applyLateFees(runId, batch, request.getLateDays(), request.getLateFeeRate());
        return new StepResult("APPLY_PENALTIES", count, "Applied late fees to batch " + batch.getBatchNo());
    }
}
