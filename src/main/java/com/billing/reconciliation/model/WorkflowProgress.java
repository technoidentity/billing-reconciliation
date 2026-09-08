package com.billing.reconciliation.model;

import java.util.ArrayList;
import java.util.List;

public class WorkflowProgress {

    private String runId;
    private String currentStep;
    private int completedBatches;
    private int totalBatches;
    private List<String> childWorkflowIds = new ArrayList<>();
    private List<String> problemTxnIds = new ArrayList<>();
    private boolean waitingForSignal;

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

    public boolean isWaitingForSignal() {
        return waitingForSignal;
    }

    public void setWaitingForSignal(boolean waitingForSignal) {
        this.waitingForSignal = waitingForSignal;
    }
}
