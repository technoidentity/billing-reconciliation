package com.billing.reconciliation.model;

public class FileValidationResult {

    private boolean valid;
    private String fileId;
    private String fileName;
    private String glFileName;
    private String computedSha256;
    private String message;

    public static FileValidationResult ok(String fileId, String fileName, String glFileName, String sha256) {
        FileValidationResult result = new FileValidationResult();
        result.valid = true;
        result.fileId = fileId;
        result.fileName = fileName;
        result.glFileName = glFileName;
        result.computedSha256 = sha256;
        result.message = "OK";
        return result;
    }

    public static FileValidationResult failed(String fileId, String fileName, String message) {
        FileValidationResult result = new FileValidationResult();
        result.valid = false;
        result.fileId = fileId;
        result.fileName = fileName;
        result.message = message;
        return result;
    }

    public boolean isValid() {
        return valid;
    }

    public void setValid(boolean valid) {
        this.valid = valid;
    }

    public String getFileId() {
        return fileId;
    }

    public void setFileId(String fileId) {
        this.fileId = fileId;
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

    public String getComputedSha256() {
        return computedSha256;
    }

    public void setComputedSha256(String computedSha256) {
        this.computedSha256 = computedSha256;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }
}
