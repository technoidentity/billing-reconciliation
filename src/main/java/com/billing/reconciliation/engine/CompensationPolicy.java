package com.billing.reconciliation.engine;

import com.billing.reconciliation.dto.BillingAmountCorrection;
import com.billing.reconciliation.dto.ProcessedTransactionDto;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Aligns flagged billing amounts to the GL (system of record) and resets processed money fields.
 * Callers write the result back to the batch CSV.
 */
@Component
public class CompensationPolicy {

    public List<BillingAmountCorrection> billingCorrections(
            List<String> txnIds, Map<String, BigDecimal> glByTxn) {
        List<BillingAmountCorrection> corrections = new ArrayList<>();
        if (txnIds == null || txnIds.isEmpty() || glByTxn == null || glByTxn.isEmpty()) {
            return corrections;
        }
        for (String txnId : txnIds) {
            BigDecimal glAmount = glByTxn.get(txnId);
            if (glAmount != null) {
                corrections.add(new BillingAmountCorrection(txnId, glAmount));
            }
        }
        return corrections;
    }

    public void resetProcessed(List<ProcessedTransactionDto> rows) {
        if (rows == null) {
            return;
        }
        for (ProcessedTransactionDto row : rows) {
            row.setAdjustment(BigDecimal.ZERO);
            row.setDiscount(BigDecimal.ZERO);
            row.setPenalty(BigDecimal.ZERO);
            row.setFinalAmount(amount(row.getOriginalAmount()));
        }
    }

    private static BigDecimal amount(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }
}
