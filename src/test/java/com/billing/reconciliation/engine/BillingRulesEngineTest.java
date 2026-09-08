package com.billing.reconciliation.engine;

import com.billing.reconciliation.dto.ProcessedTransactionDto;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BillingRulesEngineTest {

    private final BillingRulesEngine engine = new BillingRulesEngine();
    private final BillingRuleParams params = new BillingRuleParams(
            new BigDecimal("10000"),
            new BigDecimal("0.02"),
            new BigDecimal("1000"),
            new BigDecimal("5"),
            new BigDecimal("5000"),
            new BigDecimal("0.05"),
            new BigDecimal("0.02"));

    @Test
    void surchargeAppliesPercentAboveHighThreshold() {
        ProcessedTransactionDto row = processed("12000.00");
        assertEquals(1, engine.applySurcharge(List.of(row), params));
        assertEquals(new BigDecimal("12240.00"), row.getFinalAmount());
    }

    @Test
    void midTierFlatFeeAppliesBetweenThresholds() {
        ProcessedTransactionDto row = processed("1500.00");
        engine.applySurcharge(List.of(row), params);
        assertEquals(new BigDecimal("1505.00"), row.getFinalAmount());
    }

    @Test
    void passThroughBelowMidTier() {
        ProcessedTransactionDto row = processed("500.00");
        engine.applySurcharge(List.of(row), params);
        assertEquals(new BigDecimal("500.00"), row.getFinalAmount());
    }

    @Test
    void discountAppliesAboveThresholdUsingCurrentFinalAmount() {
        ProcessedTransactionDto row = processed("6000.00");
        row.setFinalAmount(new BigDecimal("6000.00"));
        assertEquals(1, engine.applyDiscount(List.of(row), params));
        assertEquals(new BigDecimal("300.00"), row.getDiscount());
        assertEquals(new BigDecimal("-300.00"), row.getAdjustment());
        assertEquals(new BigDecimal("5700.00"), row.getFinalAmount());
    }

    @Test
    void discountSkipsRowsAtOrBelowThreshold() {
        ProcessedTransactionDto row = processed("5000.00");
        row.setFinalAmount(new BigDecimal("5000.00"));
        assertEquals(0, engine.applyDiscount(List.of(row), params));
        assertEquals(BigDecimal.ZERO, row.getDiscount());
    }

    @Test
    void lateFeeAppliesWhenDueDateIsBeforeCutoff() {
        ProcessedTransactionDto row = processed("1000.00");
        row.setFinalAmount(new BigDecimal("1000.00"));
        row.setDueDate(LocalDate.of(2026, 1, 1));
        assertEquals(1, engine.applyLateFees(List.of(row), params, LocalDate.of(2026, 2, 1)));
        assertEquals(new BigDecimal("20.00"), row.getPenalty());
        assertEquals(new BigDecimal("1020.00"), row.getFinalAmount());
    }

    @Test
    void lateFeeSkipsDueOnOrAfterCutoff() {
        ProcessedTransactionDto row = processed("1000.00");
        row.setDueDate(LocalDate.of(2026, 2, 1));
        assertEquals(0, engine.applyLateFees(List.of(row), params, LocalDate.of(2026, 2, 1)));
        assertEquals(BigDecimal.ZERO, row.getPenalty());
    }

    private static ProcessedTransactionDto processed(String original) {
        BigDecimal amount = new BigDecimal(original);
        return new ProcessedTransactionDto(
                "TXN1", "Acme", "Cust", "USD", amount,
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, amount, 1, null);
    }
}
