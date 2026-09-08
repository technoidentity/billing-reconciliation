package com.billing.reconciliation.activity;

import com.billing.reconciliation.config.TaskQueues;
import com.billing.reconciliation.db.BillingJdbc;
import com.billing.reconciliation.model.BatchRef;
import com.billing.reconciliation.model.StepResult;
import io.temporal.spring.boot.ActivityImpl;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@ActivityImpl(taskQueues = TaskQueues.BILLING)
public class IngestionActivitiesImpl implements IngestionActivities {

    private final BillingJdbc jdbc;

    public IngestionActivitiesImpl(BillingJdbc jdbc) {
        this.jdbc = jdbc;
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
        jdbc.markValid(batch.getFromId(), batch.getToId());
        long valid = jdbc.countByStatus(batch.getFromId(), batch.getToId(), "VALID");
        return new StepResult("VALIDATE_SCHEMA", valid, "Valid rows in batch " + batch.getBatchNo());
    }

    @Override
    public StepResult handleValidationErrors(String runId, BatchRef batch) {
        Heartbeats.beat("validation-errors-batch-" + batch.getBatchNo());
        jdbc.markInvalid(runId, batch.getFromId(), batch.getToId());
        long invalid = jdbc.countByStatus(batch.getFromId(), batch.getToId(), "INVALID");
        return new StepResult("HANDLE_VALIDATION_ERRORS", invalid, "Invalid rows in batch " + batch.getBatchNo());
    }
}
