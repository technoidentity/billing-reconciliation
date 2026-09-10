package com.billing.reconciliation.file;

import com.billing.reconciliation.config.BillingProperties;
import com.billing.reconciliation.model.FileNotification;
import com.billing.reconciliation.model.FileValidationResult;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/**
 * Copies drop files from SFTP home to destination. This is an API/IO step, not a Temporal activity.
 */
@Component
public class InboundFileTransfer {

    private final InboundFileGateway inbound;
    private final ReconciliationFileStore files;
    private final BillingProperties properties;

    public InboundFileTransfer(InboundFileGateway inbound, ReconciliationFileStore files,
                               BillingProperties properties) {
        this.inbound = inbound;
        this.files = files;
        this.properties = properties;
    }

    public FileValidationResult copyHomeToDestination(FileNotification notification) {
        if (notification == null || notification.getFileName() == null || notification.getFileName().isBlank()) {
            return FileValidationResult.failed("", "", "fileName is required");
        }
        String fileName = notification.getFileName();
        InboundFileAccess access = inbound.active();
        String home = homeDir();
        String destination = destinationDir();
        try {
            if (!access.exists(home, fileName)) {
                return FileValidationResult.failed("", fileName, "FILE_NOT_FOUND in home: " + fileName);
            }
            files.ensureDestinationDir();
            access.copy(home, destination, fileName);
            String checksumName = FileIds.checksumFileName(notification);
            if (access.exists(home, checksumName)) {
                access.copy(home, destination, checksumName);
            }
            String glName = FileIds.glFileName(notification);
            if (access.exists(home, glName)) {
                access.copy(home, destination, glName);
                String glChecksumName = FileIds.glChecksumFileName(glName);
                if (access.exists(home, glChecksumName)) {
                    access.copy(home, destination, glChecksumName);
                }
            }
            String sha256 = sha256OfDestination(fileName);
            String fileId = FileIds.fromSha256(sha256);
            FileValidationResult result = FileValidationResult.ok(fileId, fileName, glName, sha256);
            result.setMessage("Copied " + fileName + " from home to destination; sha256=" + sha256);
            return result;
        } catch (Exception ex) {
            return FileValidationResult.failed("", fileName, "COPY_ERROR: " + ex.getMessage());
        }
    }

    public String sha256OfDestination(String fileName) throws Exception {
        Path staged = files.destinationFile(fileName);
        if (Files.isRegularFile(staged)) {
            return FileHasher.sha256(staged).toLowerCase(Locale.ROOT);
        }
        Path tmp = Files.createTempFile("dest-hash-", ".csv");
        try {
            inbound.active().download(destinationDir(), fileName, tmp);
            return FileHasher.sha256(tmp).toLowerCase(Locale.ROOT);
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    private String homeDir() {
        return properties.getSftp().getHomeDir();
    }

    private String destinationDir() {
        return properties.getSftp().getDestinationDir();
    }
}
