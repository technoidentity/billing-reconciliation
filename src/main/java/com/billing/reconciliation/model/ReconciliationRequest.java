package com.billing.reconciliation.model;

import java.util.ArrayList;
import java.util.List;

public class ReconciliationRequest {

    private String taskQueue;
    private int batchSize;
    private int maxParallelBatches;
    private double amountTolerance;
    private double surchargeThreshold;
    private double surchargeRate;
    private double midTierThreshold;
    private double midTierFlatFee;
    private double discountThreshold;
    private double discountRate;
    private int lateDays;
    private double lateFeeRate;
    private int maxDiscrepancyRounds;
    private List<String> recipients = new ArrayList<>();
    private String teamsChannel;
    private long workflowExecutionTimeoutSeconds;
    private long childExecutionTimeoutSeconds;
    private ActivityPolicyConfig ingestion;
    private ActivityPolicyConfig enrichment;
    private ActivityPolicyConfig billingRules;
    private ActivityPolicyConfig reconciliation;
    private ActivityPolicyConfig reporting;
    private ActivityPolicyConfig compensation;

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

    public double getAmountTolerance() {
        return amountTolerance;
    }

    public void setAmountTolerance(double amountTolerance) {
        this.amountTolerance = amountTolerance;
    }

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

    public int getMaxDiscrepancyRounds() {
        return maxDiscrepancyRounds;
    }

    public void setMaxDiscrepancyRounds(int maxDiscrepancyRounds) {
        this.maxDiscrepancyRounds = maxDiscrepancyRounds;
    }

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

    public long getWorkflowExecutionTimeoutSeconds() {
        return workflowExecutionTimeoutSeconds;
    }

    public void setWorkflowExecutionTimeoutSeconds(long workflowExecutionTimeoutSeconds) {
        this.workflowExecutionTimeoutSeconds = workflowExecutionTimeoutSeconds;
    }

    public long getChildExecutionTimeoutSeconds() {
        return childExecutionTimeoutSeconds;
    }

    public void setChildExecutionTimeoutSeconds(long childExecutionTimeoutSeconds) {
        this.childExecutionTimeoutSeconds = childExecutionTimeoutSeconds;
    }

    public ActivityPolicyConfig getIngestion() {
        return ingestion;
    }

    public void setIngestion(ActivityPolicyConfig ingestion) {
        this.ingestion = ingestion;
    }

    public ActivityPolicyConfig getEnrichment() {
        return enrichment;
    }

    public void setEnrichment(ActivityPolicyConfig enrichment) {
        this.enrichment = enrichment;
    }

    public ActivityPolicyConfig getBillingRules() {
        return billingRules;
    }

    public void setBillingRules(ActivityPolicyConfig billingRules) {
        this.billingRules = billingRules;
    }

    public ActivityPolicyConfig getReconciliation() {
        return reconciliation;
    }

    public void setReconciliation(ActivityPolicyConfig reconciliation) {
        this.reconciliation = reconciliation;
    }

    public ActivityPolicyConfig getReporting() {
        return reporting;
    }

    public void setReporting(ActivityPolicyConfig reporting) {
        this.reporting = reporting;
    }

    public ActivityPolicyConfig getCompensation() {
        return compensation;
    }

    public void setCompensation(ActivityPolicyConfig compensation) {
        this.compensation = compensation;
    }
}
