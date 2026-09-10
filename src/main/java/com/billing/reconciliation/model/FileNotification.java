package com.billing.reconciliation.model;

/**
 * Signal payload after the API copies a billing CSV (and optional GL companion) from home to destination.
 */
public class FileNotification {

    private String fileName;
    private String glFileName;
    private String checksumFileName;
    private String expectedSha256;
    /** Optional per-request override of billing.files.async-copy (demo convenience; null = use config). */
    private Boolean async;

    public FileNotification() {
    }

    public FileNotification(String fileName) {
        this.fileName = fileName;
    }

    public String getFileName() {
        return fileName;
    }

    public void setFileName(String fileName) {
        this.fileName = fileName;
    }

    public String getGlFileName() {
        return glFileName;
    }

    public void setGlFileName(String glFileName) {
        this.glFileName = glFileName;
    }

    public String getChecksumFileName() {
        return checksumFileName;
    }

    public void setChecksumFileName(String checksumFileName) {
        this.checksumFileName = checksumFileName;
    }

    public String getExpectedSha256() {
        return expectedSha256;
    }

    public void setExpectedSha256(String expectedSha256) {
        this.expectedSha256 = expectedSha256;
    }

    public Boolean getAsync() {
        return async;
    }

    public void setAsync(Boolean async) {
        this.async = async;
    }
}
