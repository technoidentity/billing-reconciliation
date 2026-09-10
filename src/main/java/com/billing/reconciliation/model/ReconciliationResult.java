package com.billing.reconciliation.model;

import java.util.ArrayList;
import java.util.List;

public class ReconciliationResult {

    private String runId;
    private String fileId;
    private String fileName;
    private String status;
    private long totalTxns;
    private long validTxns;
    private long invalidTxns;
    private long discrepancyCount;
    private double discrepancyAmount;
    private boolean compensated;
    private List<BatchResult> batches = new ArrayList<>();

    public String getRunId() {
        return runId;
    }

    public void setRunId(String runId) {
        this.runId = runId;
    }

    public String getFileId() {
        return fileId;
    }

    public void setFileId(String fileId) {
        this.fileId = fileId;
    }

    public String getFileName() {
        return fileName;
    }

    public void setFileName(String fileName) {
        this.fileName = fileName;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public long getTotalTxns() {
        return totalTxns;
    }

    public void setTotalTxns(long totalTxns) {
        this.totalTxns = totalTxns;
    }

    public long getValidTxns() {
        return validTxns;
    }

    public void setValidTxns(long validTxns) {
        this.validTxns = validTxns;
    }

    public long getInvalidTxns() {
        return invalidTxns;
    }

    public void setInvalidTxns(long invalidTxns) {
        this.invalidTxns = invalidTxns;
    }

    public long getDiscrepancyCount() {
        return discrepancyCount;
    }

    public void setDiscrepancyCount(long discrepancyCount) {
        this.discrepancyCount = discrepancyCount;
    }

    public double getDiscrepancyAmount() {
        return discrepancyAmount;
    }

    public void setDiscrepancyAmount(double discrepancyAmount) {
        this.discrepancyAmount = discrepancyAmount;
    }

    public boolean isCompensated() {
        return compensated;
    }

    public void setCompensated(boolean compensated) {
        this.compensated = compensated;
    }

    public List<BatchResult> getBatches() {
        return batches;
    }

    public void setBatches(List<BatchResult> batches) {
        this.batches = batches;
    }
}
