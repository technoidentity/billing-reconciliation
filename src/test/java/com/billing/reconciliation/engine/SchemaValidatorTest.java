package com.billing.reconciliation.engine;

import com.billing.reconciliation.dto.BillingTransactionDto;
import com.billing.reconciliation.dto.ValidationErrorDto;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SchemaValidatorTest {

    private final SchemaValidator validator = new SchemaValidator();

    @Test
    void validRowHasNoErrors() {
        List<ValidationErrorDto> errors = validator.validate(validRow(1L, "TXN1"));
        assertTrue(errors.isEmpty());
    }

    @Test
    void blankTxnIdIsReported() {
        BillingTransactionDto row = validRow(1L, "");
        List<ValidationErrorDto> errors = validator.validate(row);
        assertEquals(List.of(SchemaValidator.REASON_TXN_ID), reasons(errors));
    }

    @Test
    void nullTxnIdIsReported() {
        BillingTransactionDto row = validRow(1L, "TXN1");
        row.setTxnId(null);
        List<ValidationErrorDto> errors = validator.validate(row);
        assertEquals(List.of(SchemaValidator.REASON_TXN_ID), reasons(errors));
    }

    @Test
    void zeroAmountIsReported() {
        BillingTransactionDto row = validRow(1L, "TXN1");
        row.setAmount(BigDecimal.ZERO);
        List<ValidationErrorDto> errors = validator.validate(row);
        assertEquals(List.of(SchemaValidator.REASON_AMOUNT), reasons(errors));
    }

    @Test
    void negativeAmountIsReported() {
        BillingTransactionDto row = validRow(1L, "TXN1");
        row.setAmount(new BigDecimal("-0.01"));
        List<ValidationErrorDto> errors = validator.validate(row);
        assertEquals(List.of(SchemaValidator.REASON_AMOUNT), reasons(errors));
    }

    @Test
    void nullAmountIsReported() {
        BillingTransactionDto row = validRow(1L, "TXN1");
        row.setAmount(null);
        List<ValidationErrorDto> errors = validator.validate(row);
        assertEquals(List.of(SchemaValidator.REASON_AMOUNT), reasons(errors));
    }

    @Test
    void blankVendorIdIsReported() {
        BillingTransactionDto row = validRow(1L, "TXN1");
        row.setVendorId("");
        List<ValidationErrorDto> errors = validator.validate(row);
        assertEquals(List.of(SchemaValidator.REASON_VENDOR_ID), reasons(errors));
    }

    @Test
    void blankCustomerIdIsReported() {
        BillingTransactionDto row = validRow(1L, "TXN1");
        row.setCustomerId(null);
        List<ValidationErrorDto> errors = validator.validate(row);
        assertEquals(List.of(SchemaValidator.REASON_CUSTOMER_ID), reasons(errors));
    }

    @Test
    void blankCurrencyIsReported() {
        BillingTransactionDto row = validRow(1L, "TXN1");
        row.setCurrency("");
        List<ValidationErrorDto> errors = validator.validate(row);
        assertEquals(List.of(SchemaValidator.REASON_CURRENCY), reasons(errors));
    }

    @Test
    void multipleFieldFailuresAreAllReported() {
        BillingTransactionDto row = new BillingTransactionDto(
                9L, "", "", null, BigDecimal.ZERO, null);
        List<ValidationErrorDto> errors = validator.validate(row);
        assertEquals(List.of(
                SchemaValidator.REASON_TXN_ID,
                SchemaValidator.REASON_AMOUNT,
                SchemaValidator.REASON_VENDOR_ID,
                SchemaValidator.REASON_CUSTOMER_ID,
                SchemaValidator.REASON_CURRENCY), reasons(errors));
    }

    @Test
    void classifySeparatesValidAndInvalidRows() {
        BillingTransactionDto valid = validRow(1L, "TXN1");
        BillingTransactionDto invalid = validRow(2L, "TXN2");
        invalid.setAmount(BigDecimal.ZERO);
        SchemaValidationResult result = validator.classify(List.of(valid, invalid));
        assertEquals(List.of(1L), result.getValidIds());
        assertEquals(List.of(2L), result.getInvalidIds());
        assertEquals(1, result.getErrors().size());
        assertEquals(SchemaValidator.REASON_AMOUNT, result.getErrors().get(0).getReason());
        assertEquals("TXN2", result.getErrors().get(0).getTxnId());
    }

    private static BillingTransactionDto validRow(long id, String txnId) {
        return new BillingTransactionDto(
                id, txnId, "V1", "C1", new BigDecimal("10.00"), "USD");
    }

    private static List<String> reasons(List<ValidationErrorDto> errors) {
        return errors.stream().map(ValidationErrorDto::getReason).toList();
    }
}
