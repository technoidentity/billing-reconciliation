package com.billing.reconciliation.model;

public class ResolveRequest {

    private String decision = "COMPENSATE";
    private String txnId;

    public String getDecision() {
        return decision;
    }

    public void setDecision(String decision) {
        this.decision = decision;
    }

    public String getTxnId() {
        return txnId;
    }

    public void setTxnId(String txnId) {
        this.txnId = txnId;
    }
}
