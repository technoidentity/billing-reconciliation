package com.billing.reconciliation.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;

/**
 * One billing-vs-GL mismatch to persist in {@code discrepancies}.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class DiscrepancyDto {

    private String txnId;
    private BigDecimal billingAmount;
    private BigDecimal glAmount;
    private BigDecimal difference;
    private String reason;
}
