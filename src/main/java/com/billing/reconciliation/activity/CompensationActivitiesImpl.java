package com.billing.reconciliation.activity;

import com.billing.reconciliation.config.TaskQueues;
import com.billing.reconciliation.engine.CompensationPolicy;
import com.billing.reconciliation.db.BillingJdbc;
import com.billing.reconciliation.dto.BillingAmountCorrection;
import com.billing.reconciliation.dto.ProcessedTransactionDto;
import com.billing.reconciliation.model.StepResult;
import io.temporal.spring.boot.ActivityImpl;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

@Component
@ActivityImpl(taskQueues = TaskQueues.BILLING)
public class CompensationActivitiesImpl implements CompensationActivities {

    private final BillingJdbc jdbc;
    private final CompensationPolicy compensationPolicy;

    public CompensationActivitiesImpl(BillingJdbc jdbc, CompensationPolicy compensationPolicy) {
        this.jdbc = jdbc;
        this.compensationPolicy = compensationPolicy;
    }

    @Override
    public StepResult compensateDiscrepancies(String runId, List<String> txnIds) {
        Heartbeats.beat("compensate");
        if (txnIds == null || txnIds.isEmpty()) {
            return new StepResult("COMPENSATE", 0, "Compensated []");
        }
        Map<String, BigDecimal> glByTxn = jdbc.loadGlAmounts(txnIds);
        List<BillingAmountCorrection> corrections = compensationPolicy.billingCorrections(txnIds, glByTxn);
        List<ProcessedTransactionDto> processed = jdbc.loadProcessedByTxnIds(runId, txnIds);
        compensationPolicy.resetProcessed(processed);
        jdbc.updateBillingAmounts(corrections);
        jdbc.updateProcessedMoney(runId, processed);
        jdbc.markDiscrepanciesCompensated(runId, txnIds);
        Heartbeats.beat("compensate-done-" + corrections.size());
        jdbc.logAudit(runId, "COMPENSATE", "Reversed adjustments on " + corrections.size() + " problem ids: " + txnIds);
        return new StepResult("COMPENSATE", corrections.size(), "Compensated " + txnIds);
    }
}
