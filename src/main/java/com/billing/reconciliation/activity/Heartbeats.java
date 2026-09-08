package com.billing.reconciliation.activity;

import io.temporal.activity.Activity;
import io.temporal.activity.ActivityExecutionContext;

final class Heartbeats {

    static final int CHUNK = 5_000;

    private Heartbeats() {
    }

    static void beat(String detail) {
        ActivityExecutionContext ctx = Activity.getExecutionContext();
        ctx.heartbeat(detail);
    }
}
