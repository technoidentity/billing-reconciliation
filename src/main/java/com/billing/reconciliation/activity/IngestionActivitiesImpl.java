package com.billing.reconciliation.activity;

import com.billing.reconciliation.config.TaskQueues;
import com.billing.reconciliation.dto.BillingTransactionDto;
import com.billing.reconciliation.dto.ValidationErrorDto;
import com.billing.reconciliation.engine.SchemaValidationResult;
import com.billing.reconciliation.engine.SchemaValidator;
import com.billing.reconciliation.file.ReconciliationFileStore;
import com.billing.reconciliation.model.BatchRef;
import com.billing.reconciliation.model.StepResult;
import io.temporal.spring.boot.ActivityImpl;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Component
@ActivityImpl(taskQueues = TaskQueues.BILLING)
public class IngestionActivitiesImpl implements IngestionActivities {

    private final ReconciliationFileStore files;
    private final SchemaValidator schemaValidator;

    public IngestionActivitiesImpl(ReconciliationFileStore files, SchemaValidator schemaValidator) {
        this.files = files;
        this.schemaValidator = schemaValidator;
    }

    @Override
    public void startRun(String runId, String fileId, String workflowId) {
        Heartbeats.beat("start-run");
        files.ensureWorkDir(fileId);
        files.logAudit(fileId, "START_RUN", "Run " + runId + " created for workflow " + workflowId);
    }

    @Override
    public StepResult validateSchema(String runId, BatchRef batch) {
        Heartbeats.beat("validate-batch-" + batch.getBatchNo());
        List<BillingTransactionDto> rows = files.loadBatch(batch);
        SchemaValidationResult result = classifyWithHeartbeats(rows, "validate-chunk-");
        Set<Long> validIds = new HashSet<>(result.getValidIds());
        List<BillingTransactionDto> validRows = new ArrayList<>();
        for (BillingTransactionDto row : rows) {
            if (validIds.contains(row.getId())) {
                validRows.add(row);
            }
        }
        files.writeValidBatch(batch, validRows);
        Heartbeats.beat("validate-written-" + validRows.size());
        return new StepResult("VALIDATE_SCHEMA", validRows.size(),
                "Valid rows in batch " + batch.getBatchNo());
    }

    @Override
    public StepResult handleValidationErrors(String runId, BatchRef batch) {
        Heartbeats.beat("validation-errors-batch-" + batch.getBatchNo());
        List<BillingTransactionDto> rows = files.loadBatch(batch);
        SchemaValidationResult result = classifyWithHeartbeats(rows, "invalid-chunk-");
        files.writeValidationErrors(batch, result.getErrors());
        Heartbeats.beat("invalid-written-" + result.getInvalidIds().size());
        return new StepResult("HANDLE_VALIDATION_ERRORS", result.getInvalidIds().size(),
                "Invalid rows in batch " + batch.getBatchNo());
    }

    private SchemaValidationResult classifyWithHeartbeats(List<BillingTransactionDto> rows, String beatPrefix) {
        List<Long> validIds = new ArrayList<>();
        List<Long> invalidIds = new ArrayList<>();
        List<ValidationErrorDto> errors = new ArrayList<>();
        int total = rows.size();
        for (int i = 0; i < total; i += Heartbeats.CHUNK) {
            List<BillingTransactionDto> chunk = rows.subList(i, Math.min(i + Heartbeats.CHUNK, total));
            SchemaValidationResult classified = schemaValidator.classify(chunk);
            validIds.addAll(classified.getValidIds());
            invalidIds.addAll(classified.getInvalidIds());
            errors.addAll(classified.getErrors());
            Heartbeats.beat(beatPrefix + Math.min(i + Heartbeats.CHUNK, total) + "/" + total);
        }
        if (total == 0) {
            Heartbeats.beat(beatPrefix + "0/0");
        }
        return new SchemaValidationResult(validIds, invalidIds, errors);
    }
}
