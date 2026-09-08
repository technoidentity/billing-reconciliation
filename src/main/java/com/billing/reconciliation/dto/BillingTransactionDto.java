package com.billing.reconciliation.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;

/**
 * One {@code billing_transactions} row loaded for Java schema validation.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class BillingTransactionDto {

    private long id;
    private String txnId;
    private String vendorId;
    private String customerId;
    private BigDecimal amount;
    private String currency;
}
