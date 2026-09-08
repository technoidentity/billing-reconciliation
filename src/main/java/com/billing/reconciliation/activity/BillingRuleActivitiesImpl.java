package com.billing.reconciliation.activity;

import com.billing.reconciliation.engine.BillingRuleParams;
import com.billing.reconciliation.engine.BillingRulesEngine;
import com.billing.reconciliation.config.TaskQueues;
import com.billing.reconciliation.db.BillingJdbc;
import com.billing.reconciliation.dto.ProcessedTransactionDto;
import com.billing.reconciliation.model.BatchRef;
import com.billing.reconciliation.model.ReconciliationRequest;
import com.billing.reconciliation.model.StepResult;
import io.temporal.spring.boot.ActivityImpl;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.List;
import java.util.function.ToIntFunction;

@Component
@ActivityImpl(taskQueues = TaskQueues.BILLING)
public class BillingRuleActivitiesImpl implements BillingRuleActivities {

    private final BillingJdbc jdbc;
    private final BillingRulesEngine billingRulesEngine;

    public BillingRuleActivitiesImpl(BillingJdbc jdbc, BillingRulesEngine billingRulesEngine) {
        this.jdbc = jdbc;
        this.billingRulesEngine = billingRulesEngine;
    }

    @Override
    public StepResult applyBillingRules(String runId, BatchRef batch, ReconciliationRequest request) {
        Heartbeats.beat("billing-rules-batch-" + batch.getBatchNo());
        BillingRuleParams params = BillingRuleParams.from(request);
        int applied = applyInChunks(runId, batch, "surcharge-chunk-",
                chunk -> billingRulesEngine.applySurcharge(chunk, params));
        return new StepResult("APPLY_BILLING_RULES", applied,
                "Applied if-else billing tiers (surcharge / mid-tier fee / pass-through) to batch " + batch.getBatchNo());
    }

    @Override
    public StepResult calculateAdjustments(String runId, BatchRef batch, ReconciliationRequest request) {
        Heartbeats.beat("adjustments-batch-" + batch.getBatchNo());
        BillingRuleParams params = BillingRuleParams.from(request);
        int applied = applyInChunks(runId, batch, "discount-chunk-",
                chunk -> billingRulesEngine.applyDiscount(chunk, params));
        return new StepResult("CALCULATE_ADJUSTMENTS", applied, "Applied discounts to batch " + batch.getBatchNo());
    }

    @Override
    public StepResult applyPenalties(String runId, BatchRef batch, ReconciliationRequest request) {
        Heartbeats.beat("penalties-batch-" + batch.getBatchNo());
        BillingRuleParams params = BillingRuleParams.from(request);
        LocalDate cutoff = LocalDate.now().minusDays(request.getLateDays());
        int applied = applyInChunks(runId, batch, "late-fee-chunk-",
                chunk -> billingRulesEngine.applyLateFees(chunk, params, cutoff));
        return new StepResult("APPLY_PENALTIES", applied, "Applied late fees to batch " + batch.getBatchNo());
    }

    private int applyInChunks(
            String runId,
            BatchRef batch,
            String beatPrefix,
            ToIntFunction<List<ProcessedTransactionDto>> rule) {
        List<ProcessedTransactionDto> rows = jdbc.loadProcessedBatch(runId, batch);
        int total = rows.size();
        int applied = 0;
        for (int i = 0; i < total; i += Heartbeats.CHUNK) {
            List<ProcessedTransactionDto> chunk = rows.subList(i, Math.min(i + Heartbeats.CHUNK, total));
            applied += rule.applyAsInt(chunk);
            jdbc.updateProcessedMoney(runId, chunk);
            Heartbeats.beat(beatPrefix + Math.min(i + Heartbeats.CHUNK, total) + "/" + total);
        }
        if (total == 0) {
            Heartbeats.beat(beatPrefix + "0/0");
        }
        return applied;
    }
}
