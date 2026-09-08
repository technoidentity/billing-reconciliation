package com.billing.reconciliation.workflow;

import com.billing.reconciliation.model.ActivityPolicyConfig;
import com.billing.reconciliation.model.RetryPolicyConfig;
import io.temporal.activity.ActivityOptions;
import io.temporal.common.RetryOptions;

import java.time.Duration;
import java.util.List;

final class ActivityStubs {

    private ActivityStubs() {
    }

    static ActivityOptions options(String taskQueue, ActivityPolicyConfig policy) {
        ActivityOptions.Builder builder = ActivityOptions.newBuilder()
                .setTaskQueue(taskQueue)
                .setStartToCloseTimeout(Duration.ofSeconds(policy.getStartToCloseSeconds()));
        if (policy.getHeartbeatSeconds() > 0) {
            builder.setHeartbeatTimeout(Duration.ofSeconds(policy.getHeartbeatSeconds()));
        }
        RetryPolicyConfig retry = policy.getRetry();
        RetryOptions.Builder retryBuilder = RetryOptions.newBuilder()
                .setInitialInterval(Duration.ofSeconds(retry.getInitialIntervalSeconds()))
                .setBackoffCoefficient(retry.getBackoffCoefficient())
                .setMaximumInterval(Duration.ofSeconds(retry.getMaximumIntervalSeconds()))
                .setMaximumAttempts(retry.getMaximumAttempts());
        List<String> doNotRetry = retry.getDoNotRetry();
        if (doNotRetry != null && !doNotRetry.isEmpty()) {
            retryBuilder.setDoNotRetry(doNotRetry.toArray(String[]::new));
        }
        builder.setRetryOptions(retryBuilder.build());
        return builder.build();
    }
}
