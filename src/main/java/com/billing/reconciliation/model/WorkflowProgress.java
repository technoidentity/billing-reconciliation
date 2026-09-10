package com.billing.reconciliation.model;

import java.util.ArrayList;
import java.util.List;

public class WorkflowProgress {

    private String runId;
    private String currentStep;
    private String currentFileId;
    private String currentFileName;
    private int completedBatches;
    private int totalBatches;
    private int completedFileCount;
    private List<String> childWorkflowIds = new ArrayList<>();
    private List<String> problemTxnIds = new ArrayList<>();
    private List<String> pendingFiles = new ArrayList<>();
    private List<String> waitingForCorrection = new ArrayList<>();
    private String lastValidationError;
    private FileRunSummary lastResult;
    private boolean waitingForSignal;
    private long historySizeBytes;

    public WorkflowProgress() {
    }

    public String getRunId() {
        return runId;
    }

    public void setRunId(String runId) {
        this.runId = runId;
    }

    public String getCurrentStep() {
        return currentStep;
    }

    public void setCurrentStep(String currentStep) {
        this.currentStep = currentStep;
    }

    public String getCurrentFileId() {
        return currentFileId;
    }

    public void setCurrentFileId(String currentFileId) {
        this.currentFileId = currentFileId;
    }

    public String getCurrentFileName() {
        return currentFileName;
    }

    public void setCurrentFileName(String currentFileName) {
        this.currentFileName = currentFileName;
    }

    public int getCompletedBatches() {
        return completedBatches;
    }

    public void setCompletedBatches(int completedBatches) {
        this.completedBatches = completedBatches;
    }

    public int getTotalBatches() {
        return totalBatches;
    }

    public void setTotalBatches(int totalBatches) {
        this.totalBatches = totalBatches;
    }

    public int getCompletedFileCount() {
        return completedFileCount;
    }

    public void setCompletedFileCount(int completedFileCount) {
        this.completedFileCount = completedFileCount;
    }

    public List<String> getChildWorkflowIds() {
        return childWorkflowIds;
    }

    public void setChildWorkflowIds(List<String> childWorkflowIds) {
        this.childWorkflowIds = childWorkflowIds;
    }

    public List<String> getProblemTxnIds() {
        return problemTxnIds;
    }

    public void setProblemTxnIds(List<String> problemTxnIds) {
        this.problemTxnIds = problemTxnIds;
    }

    public List<String> getPendingFiles() {
        return pendingFiles;
    }

    public void setPendingFiles(List<String> pendingFiles) {
        this.pendingFiles = pendingFiles;
    }

    public List<String> getWaitingForCorrection() {
        return waitingForCorrection;
    }

    public void setWaitingForCorrection(List<String> waitingForCorrection) {
        this.waitingForCorrection = waitingForCorrection;
    }

    public String getLastValidationError() {
        return lastValidationError;
    }

    public void setLastValidationError(String lastValidationError) {
        this.lastValidationError = lastValidationError;
    }

    public FileRunSummary getLastResult() {
        return lastResult;
    }

    public void setLastResult(FileRunSummary lastResult) {
        this.lastResult = lastResult;
    }

    public boolean isWaitingForSignal() {
        return waitingForSignal;
    }

    public void setWaitingForSignal(boolean waitingForSignal) {
        this.waitingForSignal = waitingForSignal;
    }

    public long getHistorySizeBytes() {
        return historySizeBytes;
    }

    public void setHistorySizeBytes(long historySizeBytes) {
        this.historySizeBytes = historySizeBytes;
    }
}
