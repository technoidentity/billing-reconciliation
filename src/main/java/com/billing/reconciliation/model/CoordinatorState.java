package com.billing.reconciliation.model;

import java.util.ArrayList;
import java.util.List;

/**
 * Carried across Continue-As-New of the long-running parent coordinator.
 */
public class CoordinatorState {

    private ReconciliationRequest request = new ReconciliationRequest();
    private List<FileNotification> pendingFiles = new ArrayList<>();
    private List<FileNotification> pendingRetries = new ArrayList<>();
    private List<FileWaitState> waitingForCorrection = new ArrayList<>();
    private List<FileRunSummary> recentResults = new ArrayList<>();
    private int completedFileCount;

    public ReconciliationRequest getRequest() {
        return request;
    }

    public void setRequest(ReconciliationRequest request) {
        this.request = request == null ? new ReconciliationRequest() : request;
    }

    public List<FileNotification> getPendingFiles() {
        return pendingFiles;
    }

    public void setPendingFiles(List<FileNotification> pendingFiles) {
        this.pendingFiles = pendingFiles == null ? new ArrayList<>() : pendingFiles;
    }

    public List<FileNotification> getPendingRetries() {
        return pendingRetries;
    }

    public void setPendingRetries(List<FileNotification> pendingRetries) {
        this.pendingRetries = pendingRetries == null ? new ArrayList<>() : pendingRetries;
    }

    public List<FileWaitState> getWaitingForCorrection() {
        return waitingForCorrection;
    }

    public void setWaitingForCorrection(List<FileWaitState> waitingForCorrection) {
        this.waitingForCorrection = waitingForCorrection == null ? new ArrayList<>() : waitingForCorrection;
    }

    public List<FileRunSummary> getRecentResults() {
        return recentResults;
    }

    public void setRecentResults(List<FileRunSummary> recentResults) {
        this.recentResults = recentResults == null ? new ArrayList<>() : recentResults;
    }

    public int getCompletedFileCount() {
        return completedFileCount;
    }

    public void setCompletedFileCount(int completedFileCount) {
        this.completedFileCount = completedFileCount;
    }
}
