package com.billing.reconciliation.dto;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * One billing CSV row loaded for Java schema validation.
 */
@Getter
@Setter
@NoArgsConstructor
public class BillingTransactionDto {

    private long id;
    private String txnId;
    private String vendorId;
    private String customerId;
    private BigDecimal amount;
    private String currency;
    private LocalDate txnDate;
    private LocalDate dueDate;

    public BillingTransactionDto(long id, String txnId, String vendorId, String customerId,
                                 BigDecimal amount, String currency) {
        this(id, txnId, vendorId, customerId, amount, currency, null, null);
    }

    public BillingTransactionDto(long id, String txnId, String vendorId, String customerId,
                                 BigDecimal amount, String currency, LocalDate txnDate, LocalDate dueDate) {
        this.id = id;
        this.txnId = txnId;
        this.vendorId = vendorId;
        this.customerId = customerId;
        this.amount = amount;
        this.currency = currency;
        this.txnDate = txnDate;
        this.dueDate = dueDate;
    }
}
