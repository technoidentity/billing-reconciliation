package com.billing.reconciliation.engine;

import com.billing.reconciliation.dto.DiscrepancyDto;
import com.billing.reconciliation.dto.ProcessedTransactionDto;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class GlMatcherTest {

    private final GlMatcher matcher = new GlMatcher();

    @Test
    void missingGlIsReported() {
        List<DiscrepancyDto> found = matcher.match(
                List.of(billed("TXN1", "10.00")),
                Map.of(),
                0.01,
                null);
        assertEquals(1, found.size());
        assertEquals(GlMatcher.MISSING_GL, found.get(0).getReason());
        assertEquals(new BigDecimal("10.00"), found.get(0).getDifference());
    }

    @Test
    void amountMismatchAboveToleranceIsReported() {
        List<DiscrepancyDto> found = matcher.match(
                List.of(billed("TXN1", "10.00")),
                Map.of("TXN1", new BigDecimal("9.50")),
                0.01,
                null);
        assertEquals(1, found.size());
        assertEquals(GlMatcher.AMOUNT_MISMATCH, found.get(0).getReason());
        assertEquals(new BigDecimal("0.50"), found.get(0).getDifference());
    }

    @Test
    void withinToleranceIsNotADiscrepancy() {
        List<DiscrepancyDto> found = matcher.match(
                List.of(billed("TXN1", "10.00")),
                Map.of("TXN1", new BigDecimal("10.00")),
                0.01,
                null);
        assertEquals(0, found.size());
    }

    private static ProcessedTransactionDto billed(String txnId, String original) {
        BigDecimal amount = new BigDecimal(original);
        return new ProcessedTransactionDto(
                txnId, "Acme", "Cust", "USD", amount,
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, amount, 1, null);
    }
}
