package com.billing.reconciliation.file;

import com.billing.reconciliation.model.FileNotification;

public final class FileIds {

    private FileIds() {
    }

    /**
     * Work/outbound directory name is the SHA-256 of the destination billing file.
     */
    public static String fromSha256(String sha256) {
        String hex = FileHasher.parseSidecar(sha256);
        return hex.isBlank() ? "file" : hex;
    }

    public static String checksumFileName(FileNotification notification) {
        if (notification.getChecksumFileName() != null && !notification.getChecksumFileName().isBlank()) {
            return notification.getChecksumFileName();
        }
        return notification.getFileName() + ".sha256";
    }

    public static String glFileName(FileNotification notification) {
        if (notification.getGlFileName() != null && !notification.getGlFileName().isBlank()) {
            return notification.getGlFileName();
        }
        String billing = notification.getFileName();
        if (billing == null) {
            return "gl.csv";
        }
        if (billing.startsWith("billing-")) {
            return "gl-" + billing.substring("billing-".length());
        }
        return "gl-" + billing;
    }

    public static String glChecksumFileName(String glFileName) {
        return glFileName + ".sha256";
    }
}
