package com.billing.reconciliation.activity;

import com.billing.reconciliation.config.TaskQueues;
import com.billing.reconciliation.engine.CompensationPolicy;
import com.billing.reconciliation.dto.BillingAmountCorrection;
import com.billing.reconciliation.dto.ProcessedTransactionDto;
import com.billing.reconciliation.file.ReconciliationFileStore;
import com.billing.reconciliation.model.BatchRef;
import com.billing.reconciliation.model.StepResult;
import io.temporal.spring.boot.ActivityImpl;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

@Component
@ActivityImpl(taskQueues = TaskQueues.BILLING)
public class CompensationActivitiesImpl implements CompensationActivities {

    private final ReconciliationFileStore files;
    private final CompensationPolicy compensationPolicy;

    public CompensationActivitiesImpl(ReconciliationFileStore files, CompensationPolicy compensationPolicy) {
        this.files = files;
        this.compensationPolicy = compensationPolicy;
    }

    @Override
    public StepResult compensateDiscrepancies(String runId, BatchRef batch, List<String> txnIds) {
        Heartbeats.beat("compensate");
        if (txnIds == null || txnIds.isEmpty()) {
            return new StepResult("COMPENSATE", 0, "Compensated []");
        }
        Map<String, BigDecimal> glByTxn = files.loadGlAmounts(batch, txnIds);
        List<BillingAmountCorrection> corrections = compensationPolicy.billingCorrections(txnIds, glByTxn);
        List<ProcessedTransactionDto> processed = files.loadProcessedByTxnIds(batch, txnIds);
        compensationPolicy.resetProcessed(processed);
        files.updateBillingAmounts(batch, corrections);
        files.updateProcessedMoney(batch, processed);
        files.markDiscrepanciesCompensated(batch, txnIds);
        Heartbeats.beat("compensate-done-" + corrections.size());
        files.logAudit(batch.getFileId(), "COMPENSATE",
                "Aligned billing amounts to GL on " + corrections.size() + " problem ids: " + txnIds);
        return new StepResult("COMPENSATE", corrections.size(), "Compensated " + txnIds);
    }
}
