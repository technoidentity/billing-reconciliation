package com.billing.reconciliation.model;

import java.util.ArrayList;
import java.util.List;

public class RetryPolicyConfig {

    private long initialIntervalSeconds = 1;
    private double backoffCoefficient = 2.0;
    private long maximumIntervalSeconds = 16;
    private int maximumAttempts = 5;
    private List<String> doNotRetry = new ArrayList<>();

    public long getInitialIntervalSeconds() {
        return initialIntervalSeconds;
    }

    public void setInitialIntervalSeconds(long initialIntervalSeconds) {
        this.initialIntervalSeconds = initialIntervalSeconds;
    }

    public double getBackoffCoefficient() {
        return backoffCoefficient;
    }

    public void setBackoffCoefficient(double backoffCoefficient) {
        this.backoffCoefficient = backoffCoefficient;
    }

    public long getMaximumIntervalSeconds() {
        return maximumIntervalSeconds;
    }

    public void setMaximumIntervalSeconds(long maximumIntervalSeconds) {
        this.maximumIntervalSeconds = maximumIntervalSeconds;
    }

    public int getMaximumAttempts() {
        return maximumAttempts;
    }

    public void setMaximumAttempts(int maximumAttempts) {
        this.maximumAttempts = maximumAttempts;
    }

    public List<String> getDoNotRetry() {
        return doNotRetry;
    }

    public void setDoNotRetry(List<String> doNotRetry) {
        this.doNotRetry = doNotRetry;
    }
}
