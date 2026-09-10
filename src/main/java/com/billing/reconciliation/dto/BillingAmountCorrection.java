package com.billing.reconciliation.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;

/**
 * Align a billing CSV amount to the matching GL amount (COMPENSATE).
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class BillingAmountCorrection {

    private String txnId;
    private BigDecimal amount;
}
