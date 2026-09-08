package com.billing.reconciliation.activity;

import com.billing.reconciliation.config.TaskQueues;
import com.billing.reconciliation.db.BillingJdbc;
import com.billing.reconciliation.dto.DiscrepancyDto;
import com.billing.reconciliation.dto.ProcessedTransactionDto;
import com.billing.reconciliation.engine.GlMatcher;
import com.billing.reconciliation.model.BatchRef;
import com.billing.reconciliation.model.DiscrepancySummary;
import com.billing.reconciliation.model.StepResult;
import io.temporal.spring.boot.ActivityImpl;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

@Component
@ActivityImpl(taskQueues = TaskQueues.BILLING)
public class ReconciliationActivitiesImpl implements ReconciliationActivities {

    private final BillingJdbc jdbc;
    private final GlMatcher glMatcher;

    public ReconciliationActivitiesImpl(BillingJdbc jdbc, GlMatcher glMatcher) {
        this.jdbc = jdbc;
        this.glMatcher = glMatcher;
    }

    @Override
    public StepResult queryGeneralLedger(String runId, BatchRef batch) {
        Heartbeats.beat("query-gl-batch-" + batch.getBatchNo());
        long count = jdbc.countGlEntries(batch);
        jdbc.logAudit(runId, "QUERY_GL", "Loaded " + count + " GL rows for batch " + batch.getBatchNo());
        return new StepResult("QUERY_GL", count, "GL rows available for batch " + batch.getBatchNo());
    }

    @Override
    public StepResult matchTransactions(String runId, BatchRef batch, double amountTolerance) {
        Heartbeats.beat("match-batch-" + batch.getBatchNo());
        jdbc.deleteDiscrepanciesForBatch(runId, batch);
        List<ProcessedTransactionDto> billing = jdbc.loadProcessedBatch(runId, batch);
        Map<String, BigDecimal> glByTxn = jdbc.loadGlAmountsForBatch(batch);
        List<DiscrepancyDto> discrepancies = glMatcher.match(
                billing, glByTxn, amountTolerance, scanned -> Heartbeats.beat("match-scanned-" + scanned));
        jdbc.insertDiscrepancies(runId, discrepancies);
        return new StepResult("MATCH_TRANSACTIONS", discrepancies.size(),
                "In-memory match for batch " + batch.getBatchNo() + " found " + discrepancies.size() + " problem ids");
    }

    @Override
    public DiscrepancySummary identifyDiscrepancies(String runId, BatchRef batch) {
        Heartbeats.beat("identify-discrepancies-batch-" + batch.getBatchNo());
        DiscrepancySummary summary = jdbc.discrepancySummary(runId, batch);
        jdbc.logAudit(runId, "IDENTIFY_DISCREPANCIES",
                "Batch " + batch.getBatchNo() + " problem txn ids: " + summary.getTxnIds()
                        + " amount=" + summary.getAmount());
        return summary;
    }
}
