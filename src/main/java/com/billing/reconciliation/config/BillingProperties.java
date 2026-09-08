package com.billing.reconciliation.config;

import com.billing.reconciliation.model.ActivityPolicyConfig;
import com.billing.reconciliation.model.ReconciliationRequest;
import com.billing.reconciliation.model.RetryPolicyConfig;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

@ConfigurationProperties(prefix = "billing")
public class BillingProperties {

    private String taskQueue = TaskQueues.BILLING;
    private int batchSize = 100_000;
    private int maxParallelBatches = 12;
    private Schedule schedule = new Schedule();
    private ApiEndpoint vendorApi = new ApiEndpoint();
    private ApiEndpoint customerApi = new ApiEndpoint();
    private Discrepancy discrepancy = new Discrepancy();
    private Rules rules = new Rules();
    private Notifications notifications = new Notifications();
    private WorkflowTimeouts workflow = new WorkflowTimeouts();
    private Activities activities = new Activities();

    public ReconciliationRequest toWorkflowRequest() {
        ReconciliationRequest request = new ReconciliationRequest();
        request.setTaskQueue(taskQueue);
        request.setBatchSize(batchSize);
        request.setMaxParallelBatches(maxParallelBatches);
        request.setAmountTolerance(discrepancy.getAmountTolerance());
        request.setMaxDiscrepancyRounds(discrepancy.getMaxResolutionRounds());
        request.setSurchargeThreshold(rules.getSurchargeThreshold());
        request.setSurchargeRate(rules.getSurchargeRate());
        request.setMidTierThreshold(rules.getMidTierThreshold());
        request.setMidTierFlatFee(rules.getMidTierFlatFee());
        request.setDiscountThreshold(rules.getDiscountThreshold());
        request.setDiscountRate(rules.getDiscountRate());
        request.setLateDays(rules.getLateDays());
        request.setLateFeeRate(rules.getLateFeeRate());
        request.setRecipients(new ArrayList<>(notifications.getRecipients()));
        request.setTeamsChannel(notifications.getTeamsChannel());
        request.setWorkflowExecutionTimeoutSeconds(workflow.getExecutionTimeout().toSeconds());
        request.setChildExecutionTimeoutSeconds(workflow.getChildExecutionTimeout().toSeconds());
        request.setIngestion(toPolicy(activities.getIngestion()));
        request.setEnrichment(toPolicy(activities.getEnrichment()));
        request.setBillingRules(toPolicy(activities.getBillingRules()));
        request.setReconciliation(toPolicy(activities.getReconciliation()));
        request.setReporting(toPolicy(activities.getReporting()));
        request.setCompensation(toPolicy(activities.getCompensation()));
        return request;
    }

    private ActivityPolicyConfig toPolicy(ActivityTimeouts source) {
        ActivityPolicyConfig policy = new ActivityPolicyConfig();
        policy.setStartToCloseSeconds(source.getStartToCloseTimeout().toSeconds());
        if (source.getHeartbeatTimeout() != null) {
            policy.setHeartbeatSeconds(source.getHeartbeatTimeout().toSeconds());
        }
        Retry retry = source.getRetry();
        RetryPolicyConfig retryConfig = new RetryPolicyConfig();
        retryConfig.setInitialIntervalSeconds(retry.getInitialInterval().toSeconds());
        retryConfig.setBackoffCoefficient(retry.getBackoffCoefficient());
        retryConfig.setMaximumIntervalSeconds(retry.getMaximumInterval().toSeconds());
        retryConfig.setMaximumAttempts(retry.getMaximumAttempts());
        retryConfig.setDoNotRetry(new ArrayList<>(retry.getDoNotRetry()));
        policy.setRetry(retryConfig);
        return policy;
    }

    public String getTaskQueue() {
        return taskQueue;
    }

    public void setTaskQueue(String taskQueue) {
        this.taskQueue = taskQueue;
    }

    public int getBatchSize() {
        return batchSize;
    }

    public void setBatchSize(int batchSize) {
        this.batchSize = batchSize;
    }

    public int getMaxParallelBatches() {
        return maxParallelBatches;
    }

    public void setMaxParallelBatches(int maxParallelBatches) {
        this.maxParallelBatches = maxParallelBatches;
    }

    public Schedule getSchedule() {
        return schedule;
    }

    public void setSchedule(Schedule schedule) {
        this.schedule = schedule;
    }

    public ApiEndpoint getVendorApi() {
        return vendorApi;
    }

    public void setVendorApi(ApiEndpoint vendorApi) {
        this.vendorApi = vendorApi;
    }

    public ApiEndpoint getCustomerApi() {
        return customerApi;
    }

    public void setCustomerApi(ApiEndpoint customerApi) {
        this.customerApi = customerApi;
    }

    public Discrepancy getDiscrepancy() {
        return discrepancy;
    }

    public void setDiscrepancy(Discrepancy discrepancy) {
        this.discrepancy = discrepancy;
    }

    public Rules getRules() {
        return rules;
    }

    public void setRules(Rules rules) {
        this.rules = rules;
    }

    public Notifications getNotifications() {
        return notifications;
    }

    public void setNotifications(Notifications notifications) {
        this.notifications = notifications;
    }

    public WorkflowTimeouts getWorkflow() {
        return workflow;
    }

    public void setWorkflow(WorkflowTimeouts workflow) {
        this.workflow = workflow;
    }

    public Activities getActivities() {
        return activities;
    }

    public void setActivities(Activities activities) {
        this.activities = activities;
    }

    public static class Schedule {
        private boolean enabled = true;
        private String id = "daily-billing-reconciliation";
        private String cron = "0 8 * * *";
        private String timezone = "America/Chicago";
        private String workflowIdPrefix = "billing-reconciliation";
        // If the previous daily run is still in progress at the next trigger:
        // BUFFER_ONE queues one run; SKIP drops it; ALLOW_ALL runs concurrently.
        private String overlapPolicy = "BUFFER_ONE";

        public boolean isEnabled() {
            return enabled;
        }

        public String getOverlapPolicy() {
            return overlapPolicy;
        }

        public void setOverlapPolicy(String overlapPolicy) {
            this.overlapPolicy = overlapPolicy;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getId() {
            return id;
        }

        public void setId(String id) {
            this.id = id;
        }

        public String getCron() {
            return cron;
        }

        public void setCron(String cron) {
            this.cron = cron;
        }

        public String getTimezone() {
            return timezone;
        }

        public void setTimezone(String timezone) {
            this.timezone = timezone;
        }

        public String getWorkflowIdPrefix() {
            return workflowIdPrefix;
        }

        public void setWorkflowIdPrefix(String workflowIdPrefix) {
            this.workflowIdPrefix = workflowIdPrefix;
        }
    }

    public static class ApiEndpoint {
        private String baseUrl = "http://localhost:8080";
        private Duration timeout = Duration.ofSeconds(30);
        // Enrichment calls the API in parallel, bounded to this many concurrent requests per batch.
        private int maxConcurrentCalls = 1000;
        // Ids per request; the id list is split into chunks and the chunks are called concurrently.
        private int bulkChunkSize = 200;

        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
        }

        public Duration getTimeout() {
            return timeout;
        }

        public void setTimeout(Duration timeout) {
            this.timeout = timeout;
        }

        public int getMaxConcurrentCalls() {
            return maxConcurrentCalls;
        }

        public void setMaxConcurrentCalls(int maxConcurrentCalls) {
            this.maxConcurrentCalls = maxConcurrentCalls;
        }

        public int getBulkChunkSize() {
            return bulkChunkSize;
        }

        public void setBulkChunkSize(int bulkChunkSize) {
            this.bulkChunkSize = bulkChunkSize;
        }
    }

    public static class Discrepancy {
        private double amountTolerance = 0.01;
        // Loop guard: max correct-signal-reprocess rounds a batch child will run before it
        // completes and reports still-unresolved ids (prevents an unbounded human-in-the-loop cycle).
        private int maxResolutionRounds = 10;

        public double getAmountTolerance() {
            return amountTolerance;
        }

        public void setAmountTolerance(double amountTolerance) {
            this.amountTolerance = amountTolerance;
        }

        public int getMaxResolutionRounds() {
            return maxResolutionRounds;
        }

        public void setMaxResolutionRounds(int maxResolutionRounds) {
            this.maxResolutionRounds = maxResolutionRounds;
        }
    }

    public static class Rules {
        private double surchargeThreshold = 10000;
        private double surchargeRate = 0.02;
        private double midTierThreshold = 1000;
        private double midTierFlatFee = 5;
        private double discountThreshold = 5000;
        private double discountRate = 0.05;
        private int lateDays = 30;
        private double lateFeeRate = 0.02;

        public double getSurchargeThreshold() {
            return surchargeThreshold;
        }

        public void setSurchargeThreshold(double surchargeThreshold) {
            this.surchargeThreshold = surchargeThreshold;
        }

        public double getSurchargeRate() {
            return surchargeRate;
        }

        public void setSurchargeRate(double surchargeRate) {
            this.surchargeRate = surchargeRate;
        }

        public double getMidTierThreshold() {
            return midTierThreshold;
        }

        public void setMidTierThreshold(double midTierThreshold) {
            this.midTierThreshold = midTierThreshold;
        }

        public double getMidTierFlatFee() {
            return midTierFlatFee;
        }

        public void setMidTierFlatFee(double midTierFlatFee) {
            this.midTierFlatFee = midTierFlatFee;
        }

        public double getDiscountThreshold() {
            return discountThreshold;
        }

        public void setDiscountThreshold(double discountThreshold) {
            this.discountThreshold = discountThreshold;
        }

        public double getDiscountRate() {
            return discountRate;
        }

        public void setDiscountRate(double discountRate) {
            this.discountRate = discountRate;
        }

        public int getLateDays() {
            return lateDays;
        }

        public void setLateDays(int lateDays) {
            this.lateDays = lateDays;
        }

        public double getLateFeeRate() {
            return lateFeeRate;
        }

        public void setLateFeeRate(double lateFeeRate) {
            this.lateFeeRate = lateFeeRate;
        }
    }

    public static class Notifications {
        private List<String> recipients = new ArrayList<>();
        private String teamsChannel = "billing-ops-teams";

        public List<String> getRecipients() {
            return recipients;
        }

        public void setRecipients(List<String> recipients) {
            this.recipients = recipients;
        }

        public String getTeamsChannel() {
            return teamsChannel;
        }

        public void setTeamsChannel(String teamsChannel) {
            this.teamsChannel = teamsChannel;
        }
    }

    public static class WorkflowTimeouts {
        // 0 = unbounded. A daily reconciliation is long-running and each 100K child may wait an
        // arbitrarily long time for a human discrepancy signal, so execution timeouts are off by
        // default and rely on per-activity start-to-close + heartbeat timeouts instead.
        private Duration executionTimeout = Duration.ZERO;
        private Duration childExecutionTimeout = Duration.ZERO;

        public Duration getExecutionTimeout() {
            return executionTimeout;
        }

        public void setExecutionTimeout(Duration executionTimeout) {
            this.executionTimeout = executionTimeout;
        }

        public Duration getChildExecutionTimeout() {
            return childExecutionTimeout;
        }

        public void setChildExecutionTimeout(Duration childExecutionTimeout) {
            this.childExecutionTimeout = childExecutionTimeout;
        }
    }

    public static class Activities {
        private ActivityTimeouts ingestion = new ActivityTimeouts();
        private ActivityTimeouts enrichment = new ActivityTimeouts();
        private ActivityTimeouts billingRules = new ActivityTimeouts();
        private ActivityTimeouts reconciliation = new ActivityTimeouts();
        private ActivityTimeouts reporting = new ActivityTimeouts();
        private ActivityTimeouts compensation = new ActivityTimeouts();

        public ActivityTimeouts getIngestion() {
            return ingestion;
        }

        public void setIngestion(ActivityTimeouts ingestion) {
            this.ingestion = ingestion;
        }

        public ActivityTimeouts getEnrichment() {
            return enrichment;
        }

        public void setEnrichment(ActivityTimeouts enrichment) {
            this.enrichment = enrichment;
        }

        public ActivityTimeouts getBillingRules() {
            return billingRules;
        }

        public void setBillingRules(ActivityTimeouts billingRules) {
            this.billingRules = billingRules;
        }

        public ActivityTimeouts getReconciliation() {
            return reconciliation;
        }

        public void setReconciliation(ActivityTimeouts reconciliation) {
            this.reconciliation = reconciliation;
        }

        public ActivityTimeouts getReporting() {
            return reporting;
        }

        public void setReporting(ActivityTimeouts reporting) {
            this.reporting = reporting;
        }

        public ActivityTimeouts getCompensation() {
            return compensation;
        }

        public void setCompensation(ActivityTimeouts compensation) {
            this.compensation = compensation;
        }
    }

    public static class ActivityTimeouts {
        private Duration startToCloseTimeout = Duration.ofMinutes(15);
        private Duration heartbeatTimeout = Duration.ofSeconds(30);
        private Retry retry = new Retry();

        public Duration getStartToCloseTimeout() {
            return startToCloseTimeout;
        }

        public void setStartToCloseTimeout(Duration startToCloseTimeout) {
            this.startToCloseTimeout = startToCloseTimeout;
        }

        public Duration getHeartbeatTimeout() {
            return heartbeatTimeout;
        }

        public void setHeartbeatTimeout(Duration heartbeatTimeout) {
            this.heartbeatTimeout = heartbeatTimeout;
        }

        public Retry getRetry() {
            return retry;
        }

        public void setRetry(Retry retry) {
            this.retry = retry;
        }
    }

    public static class Retry {
        private Duration initialInterval = Duration.ofSeconds(1);
        private double backoffCoefficient = 2.0;
        private Duration maximumInterval = Duration.ofSeconds(16);
        private int maximumAttempts = 5;
        private List<String> doNotRetry = new ArrayList<>();

        public Duration getInitialInterval() {
            return initialInterval;
        }

        public void setInitialInterval(Duration initialInterval) {
            this.initialInterval = initialInterval;
        }

        public double getBackoffCoefficient() {
            return backoffCoefficient;
        }

        public void setBackoffCoefficient(double backoffCoefficient) {
            this.backoffCoefficient = backoffCoefficient;
        }

        public Duration getMaximumInterval() {
            return maximumInterval;
        }

        public void setMaximumInterval(Duration maximumInterval) {
            this.maximumInterval = maximumInterval;
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
}
