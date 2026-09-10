package com.billing.reconciliation.config;

import com.billing.reconciliation.model.CoordinatorState;
import com.billing.reconciliation.workflow.BillingReconciliationWorkflow;
import io.temporal.api.enums.v1.WorkflowIdReusePolicy;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Component
public class CoordinatorStarter {

    private static final Logger log = LoggerFactory.getLogger(CoordinatorStarter.class);

    private final WorkflowClient workflowClient;
    private final BillingProperties properties;

    public CoordinatorStarter(WorkflowClient workflowClient, BillingProperties properties) {
        this.workflowClient = workflowClient;
        this.properties = properties;
    }

    @PostConstruct
    public void startCoordinator() {
        if (!properties.getCoordinator().isAutoStart()) {
            log.info("File coordinator auto-start is disabled");
            return;
        }
        String workflowId = properties.getCoordinator().getWorkflowId();
        CoordinatorState state = new CoordinatorState();
        state.setRequest(properties.toWorkflowRequest());
        WorkflowOptions.Builder options = WorkflowOptions.newBuilder()
                .setWorkflowId(workflowId)
                .setTaskQueue(properties.getTaskQueue())
                .setWorkflowIdReusePolicy(WorkflowIdReusePolicy.WORKFLOW_ID_REUSE_POLICY_ALLOW_DUPLICATE);
        Duration executionTimeout = properties.getWorkflow().getExecutionTimeout();
        if (executionTimeout != null && !executionTimeout.isZero()) {
            options.setWorkflowExecutionTimeout(executionTimeout);
        }
        try {
            BillingReconciliationWorkflow stub = workflowClient.newWorkflowStub(
                    BillingReconciliationWorkflow.class, options.build());
            WorkflowClient.start(stub::run, state);
            log.info("Started long-running file coordinator {}", workflowId);
        } catch (Exception ex) {
            String message = ex.getMessage() == null ? "" : ex.getMessage();
            if (message.contains("already started") || message.contains("AlreadyStarted")) {
                log.info("File coordinator {} already running", workflowId);
            } else {
                log.warn("Could not auto-start file coordinator {}: {}", workflowId, ex.getMessage());
            }
        }
    }
}
