package com.billing.reconciliation.activity;

import com.billing.reconciliation.config.BillingProperties;
import com.billing.reconciliation.config.TaskQueues;
import com.billing.reconciliation.file.FileHasher;
import com.billing.reconciliation.file.FileIds;
import com.billing.reconciliation.file.InboundFileAccess;
import com.billing.reconciliation.file.InboundFileGateway;
import com.billing.reconciliation.file.ReconciliationFileStore;
import com.billing.reconciliation.model.BatchRef;
import com.billing.reconciliation.model.FileNotification;
import com.billing.reconciliation.model.FileValidationResult;
import io.temporal.failure.ApplicationFailure;
import io.temporal.spring.boot.ActivityImpl;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

@Component
@ActivityImpl(taskQueues = TaskQueues.BILLING)
public class FileActivitiesImpl implements FileActivities {

    private final InboundFileGateway inbound;
    private final ReconciliationFileStore files;
    private final BillingProperties properties;

    public FileActivitiesImpl(InboundFileGateway inbound, ReconciliationFileStore files,
                              BillingProperties properties) {
        this.inbound = inbound;
        this.files = files;
        this.properties = properties;
    }

    @Override
    public FileValidationResult locateAndValidate(FileNotification notification) {
        Heartbeats.beat("locate-file");
        if (notification == null || notification.getFileName() == null || notification.getFileName().isBlank()) {
            throw ApplicationFailure.newNonRetryableFailure("fileName is required", "InvalidRequest");
        }
        String fileName = notification.getFileName();
        InboundFileAccess access = inbound.active();
        String destination = destinationDir();
        try {
            // Not-found conditions are RETRYABLE: a large file (or its sidecar/GL) may still be landing
            // when the copy runs concurrently with the signal. Temporal retries per the file-validation
            // policy, which shows as repeated (red) attempts in the UI before it eventually succeeds.
            if (!access.exists(destination, fileName)) {
                throw ApplicationFailure.newFailure("FILE_NOT_FOUND in destination: " + fileName, "FileNotFound");
            }
            Heartbeats.beat("stage-from-destination");
            Path billingInDest = takeFromDestination(access, fileName);
            Heartbeats.beat("hash-billing");
            String computed = FileHasher.sha256(billingInDest).toLowerCase(Locale.ROOT);
            String fileId = FileIds.fromSha256(computed);
            files.ensureWorkDir(fileId);

            String checksumName = FileIds.checksumFileName(notification);
            if (access.exists(destination, checksumName)) {
                takeFromDestination(access, checksumName);
            }
            String expected = expectedChecksum(notification, checksumName);
            if (expected.isBlank()) {
                throw ApplicationFailure.newFailure(
                        "CHECKSUM_NOT_FOUND: no .sha256 sidecar yet for " + fileName, "ChecksumNotFound");
            }
            // Present-but-wrong is NON-RETRYABLE: retrying the same bytes will not help; the file must be
            // corrected and re-sent (Signal 2). Shows as a single red attempt, then the parent parks it.
            if (!FileHasher.matches(expected, computed)) {
                throw ApplicationFailure.newNonRetryableFailure(
                        "HASH_MISMATCH: expected " + expected + " computed " + computed, "HashMismatch");
            }
            String glName = FileIds.glFileName(notification);
            if (!access.exists(destination, glName) && !Files.isRegularFile(files.destinationFile(glName))) {
                throw ApplicationFailure.newFailure("GL_FILE_NOT_FOUND in destination: " + glName, "GlFileNotFound");
            }
            Path glInDest = takeFromDestination(access, glName);
            String glChecksumName = FileIds.glChecksumFileName(glName);
            if (access.exists(destination, glChecksumName) || Files.isRegularFile(files.destinationFile(glChecksumName))) {
                takeFromDestination(access, glChecksumName);
                String glExpected = FileHasher.parseSidecar(Files.readString(files.destinationFile(glChecksumName)));
                Heartbeats.beat("hash-gl");
                String glComputed = FileHasher.sha256(glInDest);
                if (!FileHasher.matches(glExpected, glComputed)) {
                    throw ApplicationFailure.newNonRetryableFailure(
                            "GL_HASH_MISMATCH: expected " + glExpected + " computed " + glComputed, "GlHashMismatch");
                }
            }
            files.promoteDestinationToWork(fileId, fileName, glName);
            FileValidationResult result = FileValidationResult.ok(fileId, fileName, glName, computed);
            result.setMessage("Validated destination copy sha256=" + computed + " ("
                    + Files.size(billingInDest) + " billing bytes)");
            return result;
        } catch (ApplicationFailure af) {
            throw af;
        } catch (Exception ex) {
            // Unexpected IO is treated as transient → retryable.
            throw ApplicationFailure.newFailure("VALIDATION_ERROR: " + ex.getMessage(), "ValidationError");
        }
    }

    @Override
    public List<BatchRef> sliceBatches(String fileId, int batchSize) {
        Heartbeats.beat("slice-start");
        files.ensureWorkDir(fileId);
        return files.sliceBatches(fileId, batchSize, scanned -> Heartbeats.beat("slice-rows-" + scanned));
    }

    private Path takeFromDestination(InboundFileAccess access, String fileName) {
        Path staged = files.destinationFile(fileName);
        if (Files.isRegularFile(staged)) {
            return staged;
        }
        access.download(destinationDir(), fileName, staged);
        return staged;
    }

    /** Producer sidecar is the source of truth; a producer-supplied hash on the signal is the fallback. */
    private String expectedChecksum(FileNotification notification, String sidecarName) throws Exception {
        Path sidecar = files.destinationFile(sidecarName);
        if (Files.isRegularFile(sidecar)) {
            return FileHasher.parseSidecar(Files.readString(sidecar));
        }
        Optional<String> remote = inbound.active().readText(destinationDir(), sidecarName);
        if (remote.isPresent()) {
            return FileHasher.parseSidecar(remote.get());
        }
        if (notification.getExpectedSha256() != null && !notification.getExpectedSha256().isBlank()) {
            return FileHasher.parseSidecar(notification.getExpectedSha256());
        }
        return "";
    }

    private String destinationDir() {
        return properties.getSftp().getDestinationDir();
    }
}
