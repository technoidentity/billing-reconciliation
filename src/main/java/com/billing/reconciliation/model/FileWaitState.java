package com.billing.reconciliation.model;

/**
 * A file that failed integrity validation and is waiting for Signal 2 (retry corrected file).
 */
public class FileWaitState {

    private FileNotification notification;
    private String lastError;

    public FileWaitState() {
    }

    public FileWaitState(FileNotification notification, String lastError) {
        this.notification = notification;
        this.lastError = lastError;
    }

    public FileNotification getNotification() {
        return notification;
    }

    public void setNotification(FileNotification notification) {
        this.notification = notification;
    }

    public String getLastError() {
        return lastError;
    }

    public void setLastError(String lastError) {
        this.lastError = lastError;
    }
}
