package com.billing.reconciliation.model;

public class FileRunSummary {

    private String fileId;
    private String fileName;
    private String runId;
    private String status;
    private long totalTxns;
    private long validTxns;
    private long invalidTxns;
    private long discrepancyCount;
    private boolean compensated;

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

    public String getRunId() {
        return runId;
    }

    public void setRunId(String runId) {
        this.runId = runId;
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

    public boolean isCompensated() {
        return compensated;
    }

    public void setCompensated(boolean compensated) {
        this.compensated = compensated;
    }
}
