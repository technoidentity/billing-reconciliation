package com.billing.reconciliation.engine;

import com.billing.reconciliation.dto.BillingAmountCorrection;
import com.billing.reconciliation.dto.ProcessedTransactionDto;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CompensationPolicyTest {

    private final CompensationPolicy policy = new CompensationPolicy();

    @Test
    void billingCorrectionsUseGlAmountWhenPresent() {
        List<BillingAmountCorrection> corrections = policy.billingCorrections(
                List.of("TXN1", "TXN2"),
                Map.of("TXN1", new BigDecimal("99.50")));
        assertEquals(1, corrections.size());
        assertEquals("TXN1", corrections.get(0).getTxnId());
        assertEquals(new BigDecimal("99.50"), corrections.get(0).getAmount());
    }

    @Test
    void resetProcessedClearsMoneyFieldsBackToOriginal() {
        ProcessedTransactionDto row = new ProcessedTransactionDto(
                "TXN1", "Acme", "Cust", "USD",
                new BigDecimal("100.00"),
                new BigDecimal("-5.00"),
                new BigDecimal("5.00"),
                new BigDecimal("2.00"),
                new BigDecimal("97.00"),
                1, null);
        policy.resetProcessed(List.of(row));
        assertEquals(BigDecimal.ZERO, row.getAdjustment());
        assertEquals(BigDecimal.ZERO, row.getDiscount());
        assertEquals(BigDecimal.ZERO, row.getPenalty());
        assertEquals(new BigDecimal("100.00"), row.getFinalAmount());
    }
}
