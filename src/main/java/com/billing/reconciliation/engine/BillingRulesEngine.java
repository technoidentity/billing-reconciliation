package com.billing.reconciliation.engine;

import com.billing.reconciliation.dto.ProcessedTransactionDto;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;

/**
 * Tiered surcharge, volume discount, and late-fee math. Callers write the result to CSV.
 */
@Component
public class BillingRulesEngine {

    /**
     * Sets {@code finalAmount} from {@code originalAmount}: percent surcharge above the high
     * threshold, otherwise a flat fee above the mid threshold, otherwise pass-through.
     *
     * @return number of rows inspected
     */
    public int applySurcharge(List<ProcessedTransactionDto> rows, BillingRuleParams params) {
        if (rows == null || rows.isEmpty()) {
            return 0;
        }
        for (ProcessedTransactionDto row : rows) {
            BigDecimal original = amount(row.getOriginalAmount());
            if (original.compareTo(params.surchargeThreshold()) > 0) {
                row.setFinalAmount(round(original.multiply(BigDecimal.ONE.add(params.surchargeRate()))));
            } else if (original.compareTo(params.midTierThreshold()) > 0) {
                row.setFinalAmount(round(original.add(params.midTierFlatFee())));
            } else {
                row.setFinalAmount(original);
            }
        }
        return rows.size();
    }

    /**
     * Applies a percent discount when {@code originalAmount} is above the discount threshold.
     *
     * @return number of rows that received a discount
     */
    public int applyDiscount(List<ProcessedTransactionDto> rows, BillingRuleParams params) {
        if (rows == null || rows.isEmpty()) {
            return 0;
        }
        int changed = 0;
        for (ProcessedTransactionDto row : rows) {
            BigDecimal original = amount(row.getOriginalAmount());
            if (original.compareTo(params.discountThreshold()) > 0) {
                BigDecimal product = original.multiply(params.discountRate());
                row.setDiscount(round(product));
                row.setAdjustment(round(product).negate());
                row.setFinalAmount(round(amount(row.getFinalAmount()).subtract(product)));
                changed++;
            }
        }
        return changed;
    }

    /**
     * Applies a percent late fee when {@code dueDate} is strictly before {@code cutoff}.
     *
     * @return number of rows that received a penalty
     */
    public int applyLateFees(List<ProcessedTransactionDto> rows, BillingRuleParams params, LocalDate cutoff) {
        if (rows == null || rows.isEmpty()) {
            return 0;
        }
        int changed = 0;
        for (ProcessedTransactionDto row : rows) {
            LocalDate dueDate = row.getDueDate();
            if (dueDate != null && cutoff != null && dueDate.isBefore(cutoff)) {
                BigDecimal product = amount(row.getOriginalAmount()).multiply(params.lateFeeRate());
                row.setPenalty(round(product));
                row.setFinalAmount(round(amount(row.getFinalAmount()).add(product)));
                changed++;
            }
        }
        return changed;
    }

    private static BigDecimal amount(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    static BigDecimal round(BigDecimal value) {
        return value.setScale(2, RoundingMode.HALF_UP);
    }
}
