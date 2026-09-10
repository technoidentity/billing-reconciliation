package com.billing.reconciliation.engine;

import com.billing.reconciliation.dto.BillingTransactionDto;
import com.billing.reconciliation.dto.ValidationErrorDto;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * Per-field schema rules for a billing transaction. Callers write failures to CSV.
 */
@Component
public class SchemaValidator {

    static final String REASON_TXN_ID = "txn_id is blank";
    static final String REASON_AMOUNT = "amount must be greater than 0";
    static final String REASON_VENDOR_ID = "vendor_id is blank";
    static final String REASON_CUSTOMER_ID = "customer_id is blank";
    static final String REASON_CURRENCY = "currency is blank";

    /**
     * Returns one error DTO per failed field. An empty list means the row is VALID.
     */
    public List<ValidationErrorDto> validate(BillingTransactionDto row) {
        List<ValidationErrorDto> errors = new ArrayList<>();
        String txnId = row.getTxnId();
        if (isBlank(txnId)) {
            errors.add(new ValidationErrorDto(txnId, REASON_TXN_ID));
        }
        if (!isPositive(row.getAmount())) {
            errors.add(new ValidationErrorDto(txnId, REASON_AMOUNT));
        }
        if (isBlank(row.getVendorId())) {
            errors.add(new ValidationErrorDto(txnId, REASON_VENDOR_ID));
        }
        if (isBlank(row.getCustomerId())) {
            errors.add(new ValidationErrorDto(txnId, REASON_CUSTOMER_ID));
        }
        if (isBlank(row.getCurrency())) {
            errors.add(new ValidationErrorDto(txnId, REASON_CURRENCY));
        }
        return errors;
    }

    /**
     * Classifies every row: VALID only when all fields pass. Invalid rows contribute one error per failure.
     */
    public SchemaValidationResult classify(List<BillingTransactionDto> rows) {
        List<Long> validIds = new ArrayList<>();
        List<Long> invalidIds = new ArrayList<>();
        List<ValidationErrorDto> errors = new ArrayList<>();
        if (rows == null || rows.isEmpty()) {
            return new SchemaValidationResult(validIds, invalidIds, errors);
        }
        for (BillingTransactionDto row : rows) {
            List<ValidationErrorDto> rowErrors = validate(row);
            if (rowErrors.isEmpty()) {
                validIds.add(row.getId());
            } else {
                invalidIds.add(row.getId());
                errors.addAll(rowErrors);
            }
        }
        return new SchemaValidationResult(validIds, invalidIds, errors);
    }

    private static boolean isBlank(String value) {
        return value == null || value.isEmpty();
    }

    private static boolean isPositive(BigDecimal amount) {
        return amount != null && amount.compareTo(BigDecimal.ZERO) > 0;
    }
}
