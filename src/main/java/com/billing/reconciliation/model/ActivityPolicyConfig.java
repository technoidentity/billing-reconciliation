package com.billing.reconciliation.model;

public class ActivityPolicyConfig {

    private long startToCloseSeconds;
    private long heartbeatSeconds;
    private RetryPolicyConfig retry = new RetryPolicyConfig();

    public long getStartToCloseSeconds() {
        return startToCloseSeconds;
    }

    public void setStartToCloseSeconds(long startToCloseSeconds) {
        this.startToCloseSeconds = startToCloseSeconds;
    }

    public long getHeartbeatSeconds() {
        return heartbeatSeconds;
    }

    public void setHeartbeatSeconds(long heartbeatSeconds) {
        this.heartbeatSeconds = heartbeatSeconds;
    }

    public RetryPolicyConfig getRetry() {
        return retry;
    }

    public void setRetry(RetryPolicyConfig retry) {
        this.retry = retry;
    }
}
