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

    /** Temporal workflow history practical maximum; Continue-As-New threshold is capped at this. */
    public static final long CONTINUE_AS_NEW_HISTORY_BYTES_CAP = 50L * 1024 * 1024;

    private String taskQueue = TaskQueues.BILLING;
    private int batchSize = 100_000;
    private int maxParallelBatches = 12;
    private Coordinator coordinator = new Coordinator();
    private Files files = new Files();
    private Sftp sftp = new Sftp();
    private FileValidation fileValidation = new FileValidation();
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
        // 0 = rely on Temporal's isContinueAsNewSuggested(); >0 also forces CAN at that byte threshold (capped 50MB).
        long historyBytes = workflow.getContinueAsNewHistoryBytes();
        request.setContinueAsNewHistoryBytes(historyBytes <= 0 ? 0
                : Math.min(historyBytes, CONTINUE_AS_NEW_HISTORY_BYTES_CAP));
        request.setMaxFileValidationAttempts(Math.max(1, fileValidation.getMaxRetryAttempts()));
        request.setFileValidationRetryIntervalSeconds(Math.max(1, fileValidation.getRetryInterval().toSeconds()));
        request.setIngestion(toPolicy(activities.getIngestion()));
        // The file-validation ACTIVITY retry policy is the locate/hash retry loop shown in the UI.
        // One knob drives it: billing.file-validation.max-retry-attempts / retry-interval.
        // "Not found yet" failures retry; "present but wrong" failures do not.
        ActivityPolicyConfig fileValidationPolicy = toPolicy(activities.getFileValidation());
        fileValidationPolicy.getRetry().setMaximumAttempts(Math.max(1, fileValidation.getMaxRetryAttempts()));
        fileValidationPolicy.getRetry().setInitialIntervalSeconds(Math.max(1, fileValidation.getRetryInterval().toSeconds()));
        fileValidationPolicy.getRetry().setDoNotRetry(new ArrayList<>(List.of("HashMismatch", "GlHashMismatch")));
        request.setFileValidation(fileValidationPolicy);
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

    public Coordinator getCoordinator() {
        return coordinator;
    }

    public void setCoordinator(Coordinator coordinator) {
        this.coordinator = coordinator;
    }

    public Files getFiles() {
        return files;
    }

    public void setFiles(Files files) {
        this.files = files;
    }

    public Sftp getSftp() {
        return sftp;
    }

    public void setSftp(Sftp sftp) {
        this.sftp = sftp;
    }

    public FileValidation getFileValidation() {
        return fileValidation;
    }

    public void setFileValidation(FileValidation fileValidation) {
        this.fileValidation = fileValidation;
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

    public static class Coordinator {
        private String workflowId = "billing-reconciliation-coordinator";
        private boolean autoStart = true;

        public String getWorkflowId() {
            return workflowId;
        }

        public void setWorkflowId(String workflowId) {
            this.workflowId = workflowId;
        }

        public boolean isAutoStart() {
            return autoStart;
        }

        public void setAutoStart(boolean autoStart) {
            this.autoStart = autoStart;
        }
    }

    public static class Files {
        private String mode = "sftp";
        /** Codebase SFTP mount root. Contains home/ (drop) and destination/ (copied files + work). */
        private String root = "./sftp";
        /**
         * true (default) = API starts the copy in the background and signals at the same time → the
         * workflow's locate activity retries until a large file finishes landing (durability). false =
         * copy home→destination fully, then signal → the workflow's locate finds the file immediately.
         */
        private boolean asyncCopy = true;
        /** Demo only: delay (seconds) before the async copy starts, to force visible locate retries. */
        private long copyDelaySeconds = 0;

        public String getMode() {
            return mode;
        }

        public void setMode(String mode) {
            this.mode = mode;
        }

        public String getRoot() {
            return root;
        }

        public void setRoot(String root) {
            this.root = root;
        }

        public boolean isAsyncCopy() {
            return asyncCopy;
        }

        public void setAsyncCopy(boolean asyncCopy) {
            this.asyncCopy = asyncCopy;
        }

        public long getCopyDelaySeconds() {
            return copyDelaySeconds;
        }

        public void setCopyDelaySeconds(long copyDelaySeconds) {
            this.copyDelaySeconds = copyDelaySeconds;
        }

        public java.nio.file.Path destinationPath() {
            return java.nio.file.Path.of(root).toAbsolutePath().normalize().resolve("destination");
        }

        public boolean isLocal() {
            return "local".equalsIgnoreCase(mode);
        }
    }

    public static class Sftp {
        private String host = "localhost";
        private int port = 2222;
        private String username = "billing";
        private String password = "billing";
        private String homeDir = "home";
        private String destinationDir = "destination";

        public String getHost() {
            return host;
        }

        public void setHost(String host) {
            this.host = host;
        }

        public int getPort() {
            return port;
        }

        public void setPort(int port) {
            this.port = port;
        }

        public String getUsername() {
            return username;
        }

        public void setUsername(String username) {
            this.username = username;
        }

        public String getPassword() {
            return password;
        }

        public void setPassword(String password) {
            this.password = password;
        }

        public String getHomeDir() {
            return homeDir;
        }

        public void setHomeDir(String homeDir) {
            this.homeDir = homeDir;
        }

        public String getDestinationDir() {
            return destinationDir;
        }

        public void setDestinationDir(String destinationDir) {
            this.destinationDir = destinationDir;
        }
    }

    public static class FileValidation {
        private int maxRetryAttempts = 3;
        private Duration retryInterval = Duration.ofSeconds(5);

        public int getMaxRetryAttempts() {
            return maxRetryAttempts;
        }

        public void setMaxRetryAttempts(int maxRetryAttempts) {
            this.maxRetryAttempts = maxRetryAttempts;
        }

        public Duration getRetryInterval() {
            return retryInterval;
        }

        public void setRetryInterval(Duration retryInterval) {
            this.retryInterval = retryInterval;
        }
    }

    public static class ApiEndpoint {
        private String baseUrl = "http://localhost:8080";
        private Duration timeout = Duration.ofSeconds(30);
        private int maxConcurrentCalls = 1000;
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
        private Duration executionTimeout = Duration.ZERO;
        private Duration childExecutionTimeout = Duration.ZERO;
        private long continueAsNewHistoryBytes = 20L * 1024 * 1024;

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

        public long getContinueAsNewHistoryBytes() {
            return continueAsNewHistoryBytes;
        }

        public void setContinueAsNewHistoryBytes(long continueAsNewHistoryBytes) {
            this.continueAsNewHistoryBytes = continueAsNewHistoryBytes;
        }
    }

    public static class Activities {
        private ActivityTimeouts ingestion = new ActivityTimeouts();
        private ActivityTimeouts fileValidation = new ActivityTimeouts();
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

        public ActivityTimeouts getFileValidation() {
            return fileValidation;
        }

        public void setFileValidation(ActivityTimeouts fileValidation) {
            this.fileValidation = fileValidation;
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
