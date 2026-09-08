package com.billing.reconciliation.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;

/**
 * Align a billing source amount to the GL (demo compensation policy).
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class BillingAmountCorrection {

    private String txnId;
    private BigDecimal amount;
}
