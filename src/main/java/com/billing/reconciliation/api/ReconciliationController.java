package com.billing.reconciliation.api;

import com.billing.reconciliation.config.BillingProperties;
import com.billing.reconciliation.db.BillingJdbc;
import com.billing.reconciliation.model.DiscrepancySummary;
import com.billing.reconciliation.model.ReconciliationRequest;
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
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/reconciliation")
public class ReconciliationController {

    private final WorkflowClient workflowClient;
    private final BillingProperties properties;
    private final BillingJdbc jdbc;

    public ReconciliationController(WorkflowClient workflowClient, BillingProperties properties, BillingJdbc jdbc) {
        this.workflowClient = workflowClient;
        this.properties = properties;
        this.jdbc = jdbc;
    }

    @PostMapping("/start")
    public ResponseEntity<Map<String, Object>> start() {
        long txnCount = jdbc.countTransactions();
        int batchSize = Math.max(1, properties.getBatchSize());
        long expectedBatches = txnCount == 0 ? 0 : ((txnCount + batchSize - 1) / batchSize);
        if (txnCount == 0) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("status", "NO_TRANSACTIONS");
            body.put("txnCount", 0);
            body.put("expectedBatches", 0);
            body.put("message", "billing_transactions is empty. Run ./scripts/insert-dummy-data.sh, wait for it to finish, then start again.");
            return ResponseEntity.badRequest().body(body);
        }
        String workflowId = properties.getSchedule().getWorkflowIdPrefix()
                + "-" + LocalDate.now() + "-" + UUID.randomUUID().toString().substring(0, 8);
        ReconciliationRequest request = properties.toWorkflowRequest();
        WorkflowOptions.Builder options = WorkflowOptions.newBuilder()
                .setWorkflowId(workflowId)
                .setTaskQueue(properties.getTaskQueue())
                .setWorkflowIdReusePolicy(WorkflowIdReusePolicy.WORKFLOW_ID_REUSE_POLICY_ALLOW_DUPLICATE);
        if (request.getWorkflowExecutionTimeoutSeconds() > 0) {
            options.setWorkflowExecutionTimeout(Duration.ofSeconds(request.getWorkflowExecutionTimeoutSeconds()));
        }
        BillingReconciliationWorkflow stub = workflowClient.newWorkflowStub(
                BillingReconciliationWorkflow.class, options.build());
        WorkflowClient.start(stub::run, request);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("workflowId", workflowId);
        body.put("status", "STARTED");
        body.put("txnCount", txnCount);
        body.put("expectedBatches", expectedBatches);
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
        try {
            BillingReconciliationWorkflow stub = workflowClient.newWorkflowStub(
                    BillingReconciliationWorkflow.class, workflowId);
            stub.resolveDiscrepancies(decision);
        } catch (WorkflowNotFoundException ex) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(closedWorkflow(workflowId, decision));
        }
        Map<String, String> response = new LinkedHashMap<>();
        response.put("workflowId", workflowId);
        response.put("decision", decision);
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
        String decision = body == null ? "COMPENSATE" : body.getDecision();
        try {
            BatchReconciliationWorkflow stub = workflowClient.newWorkflowStub(
                    BatchReconciliationWorkflow.class, childWorkflowId);
            if (body != null && body.getTxnId() != null && !body.getTxnId().isBlank()) {
                stub.resolveDiscrepancy(body.getTxnId(), decision);
            } else {
                stub.resolveDiscrepancies(decision);
            }
        } catch (WorkflowNotFoundException ex) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(closedWorkflow(childWorkflowId, decision));
        }
        Map<String, String> response = new LinkedHashMap<>();
        response.put("childWorkflowId", childWorkflowId);
        response.put("decision", decision);
        if (body != null && body.getTxnId() != null) {
            response.put("txnId", body.getTxnId());
        }
        response.put("status", "SIGNALED");
        return ResponseEntity.ok(response);
    }

    private static Map<String, String> closedWorkflow(String workflowId, String decision) {
        Map<String, String> body = new LinkedHashMap<>();
        body.put("workflowId", workflowId);
        body.put("decision", decision);
        body.put("status", "NOT_RUNNING");
        body.put("message", "This workflow is not running (Failed, Completed, or Terminated). Temporal can only receive a signal while status is Running. Start a new parent and wait until the child shows Running / WAITING_FOR_SIGNAL.");
        return body;
    }

    @GetMapping("/{workflowId}/result")
    public ReconciliationResult result(@PathVariable String workflowId) {
        WorkflowStub untyped = workflowClient.newUntypedWorkflowStub(workflowId);
        return untyped.getResult(ReconciliationResult.class);
    }

    @GetMapping("/runs/{runId}")
    public ResponseEntity<ReconciliationResult> run(@PathVariable String runId) {
        ReconciliationResult result = jdbc.findRun(runId);
        if (result == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(result);
    }

    /** Demo: inspect a transaction's billing vs GL amount (to see the mismatch). */
    @GetMapping("/txns/{txnId}")
    public ResponseEntity<Map<String, Object>> viewTxn(@PathVariable String txnId) {
        Map<String, Object> view = jdbc.transactionView(txnId);
        if (view == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(view);
    }

    /**
     * Demo: correct a transaction in the DB, then signal the batch child with CONTINUE to re-process.
     * Body is optional: {"amount": 123.45} sets an explicit value; omitted aligns billing to the GL.
     */
    @PostMapping("/txns/{txnId}/correct")
    public ResponseEntity<Map<String, Object>> correctTxn(
            @PathVariable String txnId,
            @RequestBody(required = false) Map<String, Object> body) {
        Map<String, Object> before = jdbc.transactionView(txnId);
        if (before == null) {
            return ResponseEntity.notFound().build();
        }
        Double amount = null;
        if (body != null && body.get("amount") != null) {
            amount = Double.parseDouble(body.get("amount").toString());
        }
        int updated = jdbc.correctTransactionAmount(txnId, amount);
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("txnId", txnId);
        response.put("rowsUpdated", updated);
        response.put("before", before);
        response.put("after", jdbc.transactionView(txnId));
        response.put("next", "POST /api/reconciliation/batches/{childWorkflowId}/resolve with {\"decision\":\"CONTINUE\"}");
        return ResponseEntity.ok(response);
    }
}
