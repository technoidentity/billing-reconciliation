package com.billing.reconciliation.model;

import java.util.ArrayList;
import java.util.List;

public class DiscrepancySummary {

    private long count;
    private double amount;
    private List<String> txnIds = new ArrayList<>();

    public DiscrepancySummary() {
    }

    public DiscrepancySummary(long count, double amount, List<String> txnIds) {
        this.count = count;
        this.amount = amount;
        this.txnIds = txnIds == null ? new ArrayList<>() : txnIds;
    }

    public long getCount() {
        return count;
    }

    public void setCount(long count) {
        this.count = count;
    }

    public double getAmount() {
        return amount;
    }

    public void setAmount(double amount) {
        this.amount = amount;
    }

    public List<String> getTxnIds() {
        return txnIds;
    }

    public void setTxnIds(List<String> txnIds) {
        this.txnIds = txnIds;
    }
}
