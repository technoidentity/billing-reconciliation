package com.billing.reconciliation.model;

public class BatchRef {

    private int batchNo;
    private long fromId;
    private long toId;

    public BatchRef() {
    }

    public BatchRef(int batchNo, long fromId, long toId) {
        this.batchNo = batchNo;
        this.fromId = fromId;
        this.toId = toId;
    }

    public int getBatchNo() {
        return batchNo;
    }

    public void setBatchNo(int batchNo) {
        this.batchNo = batchNo;
    }

    public long getFromId() {
        return fromId;
    }

    public void setFromId(long fromId) {
        this.fromId = fromId;
    }

    public long getToId() {
        return toId;
    }

    public void setToId(long toId) {
        this.toId = toId;
    }
}
