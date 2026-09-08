package com.billing.reconciliation.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * One {@code processed_transactions} row for Java enrichment and billing-rule calculation.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class ProcessedTransactionDto {

    private String txnId;
    private String vendorName;
    private String customerName;
    private String currency;
    private BigDecimal originalAmount;
    private BigDecimal adjustment;
    private BigDecimal discount;
    private BigDecimal penalty;
    private BigDecimal finalAmount;
    private int batchNo;
    private LocalDate dueDate;
}
