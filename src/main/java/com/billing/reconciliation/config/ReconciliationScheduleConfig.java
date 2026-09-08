package com.billing.reconciliation.config;

import com.billing.reconciliation.workflow.BillingReconciliationWorkflow;
import io.temporal.client.WorkflowOptions;
import io.temporal.client.schedules.Schedule;
import io.temporal.client.schedules.ScheduleActionStartWorkflow;
import io.temporal.client.schedules.ScheduleClient;
import io.temporal.client.schedules.ScheduleHandle;
import io.temporal.client.schedules.ScheduleOptions;
import io.temporal.client.schedules.SchedulePolicy;
import io.temporal.client.schedules.ScheduleSpec;
import io.temporal.client.schedules.ScheduleUpdate;
import io.temporal.api.enums.v1.ScheduleOverlapPolicy;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;

@Component
public class ReconciliationScheduleConfig {

    private static final Logger log = LoggerFactory.getLogger(ReconciliationScheduleConfig.class);

    private final ScheduleClient scheduleClient;
    private final BillingProperties properties;

    public ReconciliationScheduleConfig(ScheduleClient scheduleClient, BillingProperties properties) {
        this.scheduleClient = scheduleClient;
        this.properties = properties;
    }

    @PostConstruct
    public void registerDailySchedule() {
        BillingProperties.Schedule scheduleProps = properties.getSchedule();
        if (!scheduleProps.isEnabled()) {
            log.info("Daily billing reconciliation schedule is disabled");
            return;
        }

        WorkflowOptions.Builder startOptions = WorkflowOptions.newBuilder()
                .setWorkflowId(scheduleProps.getWorkflowIdPrefix())
                .setTaskQueue(properties.getTaskQueue());
        Duration executionTimeout = properties.getWorkflow().getExecutionTimeout();
        if (executionTimeout != null && !executionTimeout.isZero()) {
            startOptions.setWorkflowExecutionTimeout(executionTimeout);
        }

        Schedule schedule = Schedule.newBuilder()
                .setAction(
                        ScheduleActionStartWorkflow.newBuilder()
                                .setWorkflowType(BillingReconciliationWorkflow.class)
                                .setArguments(properties.toWorkflowRequest())
                                .setOptions(startOptions.build())
                                .build())
                .setSpec(
                        ScheduleSpec.newBuilder()
                                .setCronExpressions(List.of(scheduleProps.getCron()))
                                .setTimeZoneName(scheduleProps.getTimezone())
                                .build())
                .setPolicy(
                        SchedulePolicy.newBuilder()
                                .setOverlap(overlapPolicy(scheduleProps.getOverlapPolicy()))
                                .build())
                .build();

        try {
            scheduleClient.createSchedule(scheduleProps.getId(), schedule, ScheduleOptions.newBuilder().build());
            log.info("Created Temporal schedule {} cron={} tz={}",
                    scheduleProps.getId(), scheduleProps.getCron(), scheduleProps.getTimezone());
        } catch (Exception createError) {
            try {
                ScheduleHandle handle = scheduleClient.getHandle(scheduleProps.getId());
                handle.update(input -> new ScheduleUpdate(schedule));
                log.info("Updated existing Temporal schedule {}", scheduleProps.getId());
            } catch (Exception updateError) {
                log.warn("Could not register Temporal schedule {}: {}",
                        scheduleProps.getId(), updateError.getMessage());
            }
        }
    }

    private ScheduleOverlapPolicy overlapPolicy(String name) {
        try {
            return ScheduleOverlapPolicy.valueOf(
                    "SCHEDULE_OVERLAP_POLICY_" + name.trim().toUpperCase());
        } catch (RuntimeException ex) {
            log.warn("Unknown schedule overlap policy '{}', defaulting to BUFFER_ONE", name);
            return ScheduleOverlapPolicy.SCHEDULE_OVERLAP_POLICY_BUFFER_ONE;
        }
    }
}
