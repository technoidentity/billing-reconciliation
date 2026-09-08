package com.billing.reconciliation.engine;

import com.billing.reconciliation.dto.DiscrepancyDto;
import com.billing.reconciliation.dto.ProcessedTransactionDto;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.IntConsumer;

/**
 * Compares processed original amounts to GL amounts in memory. No JDBC — callers persist the result.
 */
@Component
public class GlMatcher {

    static final String MISSING_GL = "MISSING_GL";
    static final String AMOUNT_MISMATCH = "AMOUNT_MISMATCH";

    public List<DiscrepancyDto> match(
            List<ProcessedTransactionDto> billing,
            Map<String, BigDecimal> glByTxn,
            double tolerance,
            IntConsumer onProgress) {
        List<DiscrepancyDto> discrepancies = new ArrayList<>();
        if (billing == null || billing.isEmpty()) {
            return discrepancies;
        }
        Map<String, BigDecimal> ledger = glByTxn == null ? Map.of() : glByTxn;
        BigDecimal maxDelta = BigDecimal.valueOf(tolerance);
        int scanned = 0;
        for (ProcessedTransactionDto billed : billing) {
            scanned++;
            if (scanned % 10_000 == 0 && onProgress != null) {
                onProgress.accept(scanned);
            }
            BigDecimal billingAmount = billed.getOriginalAmount() == null
                    ? BigDecimal.ZERO : billed.getOriginalAmount();
            BigDecimal glAmount = ledger.get(billed.getTxnId());
            if (glAmount == null) {
                discrepancies.add(new DiscrepancyDto(
                        billed.getTxnId(), billingAmount, BigDecimal.ZERO, billingAmount, MISSING_GL));
                continue;
            }
            BigDecimal difference = billingAmount.subtract(glAmount).setScale(2, RoundingMode.HALF_UP);
            if (difference.abs().compareTo(maxDelta) > 0) {
                discrepancies.add(new DiscrepancyDto(
                        billed.getTxnId(), billingAmount, glAmount, difference, AMOUNT_MISMATCH));
            }
        }
        return discrepancies;
    }
}
