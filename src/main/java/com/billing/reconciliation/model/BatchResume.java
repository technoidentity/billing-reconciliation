package com.billing.reconciliation.model;

import java.util.ArrayList;
import java.util.List;

/**
 * Carried across a Continue-As-New of the batch reconciliation workflow.
 * <p>
 * Each Continue-As-New starts a fresh run that re-executes the identical pipeline against the
 * (now corrected) data, so no per-row state is carried — only the round counter (loop guard),
 * whether a compensation has been applied, and the txn ids first flagged (for the final report).
 */
public class BatchResume {

    private int round = 1;
    private boolean compensated;
    private List<String> originalTxnIds = new ArrayList<>();

    public int getRound() {
        return round;
    }

    public void setRound(int round) {
        this.round = round;
    }

    public boolean isCompensated() {
        return compensated;
    }

    public void setCompensated(boolean compensated) {
        this.compensated = compensated;
    }

    public List<String> getOriginalTxnIds() {
        return originalTxnIds;
    }

    public void setOriginalTxnIds(List<String> originalTxnIds) {
        this.originalTxnIds = originalTxnIds == null ? new ArrayList<>() : originalTxnIds;
    }
}
