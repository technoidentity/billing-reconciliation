package com.billing.reconciliation.activity;

import io.temporal.activity.Activity;
import io.temporal.activity.ActivityExecutionContext;

final class Heartbeats {

    static final int CHUNK = 5_000;

    private Heartbeats() {
    }

    static void beat(String detail) {
        try {
            ActivityExecutionContext ctx = Activity.getExecutionContext();
            ctx.heartbeat(detail);
        } catch (Exception ignored) {
            // No activity context in unit tests.
        }
    }
}
