package com.billing.reconciliation.engine;

import com.billing.reconciliation.model.ReconciliationRequest;

import java.math.BigDecimal;

/**
 * Snapshot of surcharge / discount / late-fee knobs for Java billing rules.
 */
public record BillingRuleParams(
        BigDecimal surchargeThreshold,
        BigDecimal surchargeRate,
        BigDecimal midTierThreshold,
        BigDecimal midTierFlatFee,
        BigDecimal discountThreshold,
        BigDecimal discountRate,
        BigDecimal lateFeeRate) {

    public static BillingRuleParams from(ReconciliationRequest request) {
        return new BillingRuleParams(
                BigDecimal.valueOf(request.getSurchargeThreshold()),
                BigDecimal.valueOf(request.getSurchargeRate()),
                BigDecimal.valueOf(request.getMidTierThreshold()),
                BigDecimal.valueOf(request.getMidTierFlatFee()),
                BigDecimal.valueOf(request.getDiscountThreshold()),
                BigDecimal.valueOf(request.getDiscountRate()),
                BigDecimal.valueOf(request.getLateFeeRate()));
    }
}
