package com.billing.reconciliation.api;

import com.billing.reconciliation.config.BillingProperties;
import com.billing.reconciliation.file.FileIds;
import com.billing.reconciliation.file.InboundFileTransfer;
import com.billing.reconciliation.file.ReconciliationFileStore;
import com.billing.reconciliation.model.CoordinatorState;
import com.billing.reconciliation.model.DiscrepancySummary;
import com.billing.reconciliation.model.FileNotification;
import com.billing.reconciliation.model.ReconciliationResult;
import com.billing.reconciliation.model.ResolveRequest;
import com.billing.reconciliation.model.WorkflowProgress;
import com.billing.reconciliation.workflow.BatchReconciliationWorkflow;
import com.billing.reconciliation.workflow.BillingReconciliationWorkflow;
import io.temporal.api.enums.v1.WorkflowIdReusePolicy;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowNotFoundException;
import io.temporal.client.WorkflowOptions;
import io.temporal.client.WorkflowStub;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@RestController
@RequestMapping("/api/reconciliation")
public class ReconciliationController {

    private final WorkflowClient workflowClient;
    private final BillingProperties properties;
    private final ReconciliationFileStore files;
    private final InboundFileTransfer transfer;
    private final ExecutorService copyExecutor = Executors.newCachedThreadPool();

    public ReconciliationController(WorkflowClient workflowClient, BillingProperties properties,
                                    ReconciliationFileStore files, InboundFileTransfer transfer) {
        this.workflowClient = workflowClient;
        this.properties = properties;
        this.files = files;
        this.transfer = transfer;
    }

    @PostMapping("/start")
    public ResponseEntity<Map<String, Object>> start() {
        String workflowId = startCoordinator();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("workflowId", workflowId);
        body.put("status", "RUNNING");
        body.put("message", "Long-running coordinator is active. Signal a file with POST /files/available.");
        return ResponseEntity.ok(body);
    }

    @PostMapping("/files/available")
    public ResponseEntity<Map<String, Object>> fileAvailable(@RequestBody FileNotification notification) {
        return copyThenSignal("fileAvailable", notification);
    }

    @PostMapping("/files/retry")
    public ResponseEntity<Map<String, Object>> retryCorrectedFile(@RequestBody FileNotification notification) {
        return copyThenSignal("retryCorrectedFile", notification);
    }

    private ResponseEntity<Map<String, Object>> copyThenSignal(String signalName, FileNotification notification) {
        if (notification == null || notification.getFileName() == null || notification.getFileName().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("status", "INVALID", "message", "fileName is required"));
        }
        boolean async = notification.getAsync() != null
                ? notification.getAsync() : properties.getFiles().isAsyncCopy();

        // Async: start the copy in the background and signal at the same time. The workflow's locate
        // activity retries until the (large) file lands. A missing/mistyped file surfaces as the locate
        // activity failing (red), and the coordinator parks it — a good failure demo.
        if (async) {
            final FileNotification copyRequest = notification;
            final long delaySeconds = properties.getFiles().getCopyDelaySeconds();
            copyExecutor.submit(() -> {
                try {
                    if (delaySeconds > 0) {
                        Thread.sleep(delaySeconds * 1000L);
                    }
                    transfer.copyHomeToDestination(copyRequest);
                } catch (Exception ignored) {
                    // Copy failure is intentional signal-noise here: the workflow's locate activity will
                    // fail (red) and the file will wait for correction.
                }
            });
            String workflowId = signalWithStart(signalName, notification);
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("workflowId", workflowId);
            body.put("fileName", notification.getFileName());
            body.put("status", "SIGNALED");
            body.put("signal", signalName);
            body.put("mode", "async");
            body.put("message", "Copy started in background; coordinator signaled concurrently. "
                    + "locate/validate retries until the file lands.");
            return ResponseEntity.ok(body);
        }

        // Sync (default): copy fully, then signal → locate finds the file immediately.
        var copied = transfer.copyHomeToDestination(notification);
        if (!copied.isValid()) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("status", "COPY_FAILED");
            body.put("fileName", notification.getFileName());
            body.put("message", copied.getMessage());
            return ResponseEntity.badRequest().body(body);
        }
        if (copied.getGlFileName() != null && !copied.getGlFileName().isBlank()) {
            notification.setGlFileName(copied.getGlFileName());
        }
        // Do NOT set expectedSha256 from the copied file — integrity is validated against the producer's
        // sidecar (independent reference), not a hash the API recomputed from the same bytes.
        String workflowId = signalWithStart(signalName, notification);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("workflowId", workflowId);
        body.put("fileName", notification.getFileName());
        body.put("sha256", copied.getComputedSha256());
        body.put("fileId", FileIds.fromSha256(copied.getComputedSha256()));
        body.put("status", "SIGNALED");
        body.put("signal", signalName);
        body.put("mode", "sync");
        body.put("message", "Copied home → destination, then signaled. Integrity is validated against the sidecar.");
        return ResponseEntity.ok(body);
    }

    @GetMapping("/{workflowId}/progress")
    public WorkflowProgress progress(@PathVariable String workflowId) {
        BillingReconciliationWorkflow stub = workflowClient.newWorkflowStub(
                BillingReconciliationWorkflow.class, workflowId);
        return stub.getProgress();
    }

    @PostMapping("/{workflowId}/resolve")
    public ResponseEntity<Map<String, String>> resolveParent(
            @PathVariable String workflowId,
            @RequestBody(required = false) ResolveRequest body) {
        String decision = body == null ? "COMPENSATE" : body.getDecision();
        if (!"COMPENSATE".equalsIgnoreCase(decision)) {
            return ResponseEntity.badRequest().body(Map.of(
                    "status", "REJECTED",
                    "message", "Discrepancy resolution uses COMPENSATE only."));
        }
        try {
            BillingReconciliationWorkflow stub = workflowClient.newWorkflowStub(
                    BillingReconciliationWorkflow.class, workflowId);
            stub.resolveDiscrepancies("COMPENSATE");
        } catch (WorkflowNotFoundException ex) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(closedWorkflow(workflowId, decision));
        }
        Map<String, String> response = new LinkedHashMap<>();
        response.put("workflowId", workflowId);
        response.put("decision", "COMPENSATE");
        response.put("status", "SIGNALED");
        return ResponseEntity.ok(response);
    }

    @GetMapping("/batches/{childWorkflowId}/problems")
    public DiscrepancySummary batchProblems(@PathVariable String childWorkflowId) {
        BatchReconciliationWorkflow stub = workflowClient.newWorkflowStub(
                BatchReconciliationWorkflow.class, childWorkflowId);
        return stub.getProblems();
    }

    @GetMapping("/batches/{childWorkflowId}/step")
    public Map<String, String> batchStep(@PathVariable String childWorkflowId) {
        BatchReconciliationWorkflow stub = workflowClient.newWorkflowStub(
                BatchReconciliationWorkflow.class, childWorkflowId);
        Map<String, String> body = new LinkedHashMap<>();
        body.put("childWorkflowId", childWorkflowId);
        body.put("currentStep", stub.getCurrentStep());
        return body;
    }

    @PostMapping("/batches/{childWorkflowId}/resolve")
    public ResponseEntity<Map<String, String>> resolveBatch(
            @PathVariable String childWorkflowId,
            @RequestBody(required = false) ResolveRequest body) {
        String decision = body == null || body.getDecision() == null ? "COMPENSATE" : body.getDecision();
        if (!"COMPENSATE".equalsIgnoreCase(decision)) {
            return ResponseEntity.badRequest().body(Map.of(
                    "status", "REJECTED",
                    "message", "Discrepancy resolution uses COMPENSATE only."));
        }
        try {
            BatchReconciliationWorkflow stub = workflowClient.newWorkflowStub(
                    BatchReconciliationWorkflow.class, childWorkflowId);
            if (body != null && body.getTxnId() != null && !body.getTxnId().isBlank()) {
                stub.resolveDiscrepancy(body.getTxnId(), "COMPENSATE");
            } else {
                stub.resolveDiscrepancies("COMPENSATE");
            }
        } catch (WorkflowNotFoundException ex) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(closedWorkflow(childWorkflowId, decision));
        }
        Map<String, String> response = new LinkedHashMap<>();
        response.put("childWorkflowId", childWorkflowId);
        response.put("decision", "COMPENSATE");
        if (body != null && body.getTxnId() != null) {
            response.put("txnId", body.getTxnId());
        }
        response.put("status", "SIGNALED");
        return ResponseEntity.ok(response);
    }

    @GetMapping("/{workflowId}/result")
    public ResponseEntity<?> result(@PathVariable String workflowId) {
        BillingReconciliationWorkflow stub = workflowClient.newWorkflowStub(
                BillingReconciliationWorkflow.class, workflowId);
        WorkflowProgress progress = stub.getProgress();
        if (progress.getLastResult() == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of(
                    "status", "NO_RESULT",
                    "message", "Coordinator is running; no file has completed yet."));
        }
        return ResponseEntity.ok(progress.getLastResult());
    }

    @GetMapping("/files/{fileId}/result")
    public ResponseEntity<ReconciliationResult> fileResult(@PathVariable String fileId) {
        ReconciliationResult result = files.readResult(fileId);
        if (result == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(result);
    }

    private String startCoordinator() {
        String workflowId = properties.getCoordinator().getWorkflowId();
        CoordinatorState state = new CoordinatorState();
        state.setRequest(properties.toWorkflowRequest());
        BillingReconciliationWorkflow stub = workflowClient.newWorkflowStub(
                BillingReconciliationWorkflow.class, coordinatorOptions(workflowId, state.getRequest()));
        try {
            WorkflowClient.start(stub::run, state);
        } catch (Exception ex) {
            if (!alreadyStarted(ex)) {
                throw ex;
            }
        }
        return workflowId;
    }

    private String signalWithStart(String signalName, FileNotification notification) {
        String workflowId = properties.getCoordinator().getWorkflowId();
        CoordinatorState state = new CoordinatorState();
        state.setRequest(properties.toWorkflowRequest());
        BillingReconciliationWorkflow stub = workflowClient.newWorkflowStub(
                BillingReconciliationWorkflow.class, coordinatorOptions(workflowId, state.getRequest()));
        WorkflowStub.fromTyped(stub).signalWithStart(signalName, new Object[]{notification}, new Object[]{state});
        return workflowId;
    }

    private WorkflowOptions coordinatorOptions(String workflowId, com.billing.reconciliation.model.ReconciliationRequest request) {
        WorkflowOptions.Builder options = WorkflowOptions.newBuilder()
                .setWorkflowId(workflowId)
                .setTaskQueue(properties.getTaskQueue())
                .setWorkflowIdReusePolicy(WorkflowIdReusePolicy.WORKFLOW_ID_REUSE_POLICY_ALLOW_DUPLICATE);
        if (request.getWorkflowExecutionTimeoutSeconds() > 0) {
            options.setWorkflowExecutionTimeout(Duration.ofSeconds(request.getWorkflowExecutionTimeoutSeconds()));
        }
        return options.build();
    }

    private static boolean alreadyStarted(Exception ex) {
        String message = ex.getMessage() == null ? "" : ex.getMessage();
        return message.contains("already started") || message.contains("AlreadyStarted");
    }

    private static Map<String, String> closedWorkflow(String workflowId, String decision) {
        Map<String, String> body = new LinkedHashMap<>();
        body.put("workflowId", workflowId);
        body.put("decision", decision);
        body.put("status", "NOT_RUNNING");
        body.put("message", "This workflow is not running. Start the coordinator, then signal while status is Running.");
        return body;
    }
}
