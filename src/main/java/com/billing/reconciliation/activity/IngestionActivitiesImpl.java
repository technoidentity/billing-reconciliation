package com.billing.reconciliation.activity;

import com.billing.reconciliation.config.TaskQueues;
import com.billing.reconciliation.db.BillingJdbc;
import com.billing.reconciliation.dto.BillingTransactionDto;
import com.billing.reconciliation.dto.ValidationErrorDto;
import com.billing.reconciliation.model.BatchRef;
import com.billing.reconciliation.model.StepResult;
import com.billing.reconciliation.engine.SchemaValidationResult;
import com.billing.reconciliation.engine.SchemaValidator;
import io.temporal.spring.boot.ActivityImpl;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
@ActivityImpl(taskQueues = TaskQueues.BILLING)
public class IngestionActivitiesImpl implements IngestionActivities {

    private final BillingJdbc jdbc;
    private final SchemaValidator schemaValidator;

    public IngestionActivitiesImpl(BillingJdbc jdbc, SchemaValidator schemaValidator) {
        this.jdbc = jdbc;
        this.schemaValidator = schemaValidator;
    }

    @Override
    public void startRun(String runId, String workflowId) {
        Heartbeats.beat("start-run");
        jdbc.startRun(runId, workflowId);
        jdbc.logAudit(runId, "START_RUN", "Run created for workflow " + workflowId);
    }

    @Override
    public List<BatchRef> listBatches(int batchSize) {
        Heartbeats.beat("list-batches");
        return jdbc.listBatches(batchSize);
    }

    @Override
    public StepResult validateSchema(String runId, BatchRef batch) {
        Heartbeats.beat("validate-batch-" + batch.getBatchNo());
        List<BillingTransactionDto> rows = jdbc.loadBatchForValidation(batch.getFromId(), batch.getToId());
        SchemaValidationResult result = classifyWithHeartbeats(rows, "validate-chunk-");
        jdbc.updateStatuses(result.getValidIds(), List.of());
        Heartbeats.beat("validate-persisted-" + result.getValidIds().size());
        return new StepResult("VALIDATE_SCHEMA", result.getValidIds().size(),
                "Valid rows in batch " + batch.getBatchNo());
    }

    @Override
    public StepResult handleValidationErrors(String runId, BatchRef batch) {
        Heartbeats.beat("validation-errors-batch-" + batch.getBatchNo());
        List<BillingTransactionDto> rows = jdbc.loadBatchForValidation(batch.getFromId(), batch.getToId());
        SchemaValidationResult result = classifyWithHeartbeats(rows, "invalid-chunk-");
        jdbc.updateStatuses(List.of(), result.getInvalidIds());
        jdbc.replaceValidationErrors(runId, batch, result.getErrors());
        Heartbeats.beat("invalid-persisted-" + result.getInvalidIds().size());
        return new StepResult("HANDLE_VALIDATION_ERRORS", result.getInvalidIds().size(),
                "Invalid rows in batch " + batch.getBatchNo());
    }

    /**
     * Re-runs the Java validator in ~5k chunks so long 100K batches keep heartbeating.
     */
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
