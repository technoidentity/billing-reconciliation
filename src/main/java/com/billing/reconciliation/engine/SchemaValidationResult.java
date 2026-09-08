package com.billing.reconciliation.engine;

import com.billing.reconciliation.dto.ValidationErrorDto;
import lombok.Getter;

import java.util.ArrayList;
import java.util.List;

/**
 * Classification of a batch: valid ids, invalid ids, and one error DTO per failed field.
 */
@Getter
public class SchemaValidationResult {

    private final List<Long> validIds;
    private final List<Long> invalidIds;
    private final List<ValidationErrorDto> errors;

    public SchemaValidationResult(List<Long> validIds, List<Long> invalidIds, List<ValidationErrorDto> errors) {
        this.validIds = validIds == null ? new ArrayList<>() : validIds;
        this.invalidIds = invalidIds == null ? new ArrayList<>() : invalidIds;
        this.errors = errors == null ? new ArrayList<>() : errors;
    }
}
