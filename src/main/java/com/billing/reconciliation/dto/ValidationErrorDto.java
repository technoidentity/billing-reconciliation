package com.billing.reconciliation.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * One per-field schema failure written to {@code validation-errors-batch-n.csv}.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class ValidationErrorDto {

    private String txnId;
    private String reason;
}
