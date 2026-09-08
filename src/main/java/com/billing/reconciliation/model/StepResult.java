package com.billing.reconciliation.model;

public class StepResult {

    private String stepName;
    private long count;
    private String message;

    public StepResult() {
    }

    public StepResult(String stepName, long count, String message) {
        this.stepName = stepName;
        this.count = count;
        this.message = message;
    }

    public String getStepName() {
        return stepName;
    }

    public void setStepName(String stepName) {
        this.stepName = stepName;
    }

    public long getCount() {
        return count;
    }

    public void setCount(long count) {
        this.count = count;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }
}
