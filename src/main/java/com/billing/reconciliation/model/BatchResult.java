package com.billing.reconciliation.model;

import java.util.ArrayList;
import java.util.List;

public class BatchResult {

    private int batchNo;
    private long total;
    private long valid;
    private long invalid;
    private long processed;
    private List<String> problemTxnIds = new ArrayList<>();
    private boolean compensated;

    public int getBatchNo() {
        return batchNo;
    }

    public void setBatchNo(int batchNo) {
        this.batchNo = batchNo;
    }

    public long getTotal() {
        return total;
    }

    public void setTotal(long total) {
        this.total = total;
    }

    public long getValid() {
        return valid;
    }

    public void setValid(long valid) {
        this.valid = valid;
    }

    public long getInvalid() {
        return invalid;
    }

    public void setInvalid(long invalid) {
        this.invalid = invalid;
    }

    public long getProcessed() {
        return processed;
    }

    public void setProcessed(long processed) {
        this.processed = processed;
    }

    public List<String> getProblemTxnIds() {
        return problemTxnIds;
    }

    public void setProblemTxnIds(List<String> problemTxnIds) {
        this.problemTxnIds = problemTxnIds;
    }

    public boolean isCompensated() {
        return compensated;
    }

    public void setCompensated(boolean compensated) {
        this.compensated = compensated;
    }
}
