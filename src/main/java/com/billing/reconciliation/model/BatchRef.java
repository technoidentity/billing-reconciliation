package com.billing.reconciliation.model;

public class BatchRef {

    private int batchNo;
    private long fromId;
    private long toId;
    private String fileId;

    public BatchRef() {
    }

    public BatchRef(int batchNo, long fromId, long toId) {
        this(batchNo, fromId, toId, null);
    }

    public BatchRef(int batchNo, long fromId, long toId, String fileId) {
        this.batchNo = batchNo;
        this.fromId = fromId;
        this.toId = toId;
        this.fileId = fileId;
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

    public String getFileId() {
        return fileId;
    }

    public void setFileId(String fileId) {
        this.fileId = fileId;
    }
}
