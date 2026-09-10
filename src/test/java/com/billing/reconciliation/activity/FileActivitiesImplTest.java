package com.billing.reconciliation.activity;

import com.billing.reconciliation.config.BillingProperties;
import com.billing.reconciliation.file.FileHasher;
import com.billing.reconciliation.file.FileIds;
import com.billing.reconciliation.file.InboundFileGateway;
import com.billing.reconciliation.file.InboundFileTransfer;
import com.billing.reconciliation.file.LocalInboundFiles;
import com.billing.reconciliation.file.ReconciliationFileStore;
import com.billing.reconciliation.file.SftpInboundFiles;
import com.billing.reconciliation.model.FileNotification;
import com.billing.reconciliation.model.FileValidationResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.temporal.failure.ApplicationFailure;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FileActivitiesImplTest {

    @TempDir
    Path root;

    private FileActivitiesImpl activities;
    private InboundFileTransfer transfer;
    private Path home;
    private Path destination;
    private String billingSha256;

    @BeforeEach
    void setUp() throws Exception {
        BillingProperties properties = new BillingProperties();
        properties.getFiles().setMode("local");
        properties.getFiles().setRoot(root.toString());
        properties.getSftp().setHomeDir("home");
        properties.getSftp().setDestinationDir("destination");

        home = root.resolve("home");
        destination = root.resolve("destination");
        Files.createDirectories(home);
        Files.createDirectories(destination);

        String billing = "id,txn_id,vendor_id,customer_id,amount,currency,txn_date,due_date\n"
                + "1,TXN1,V0001,C000001,10.00,USD,2026-01-01,2026-01-16\n";
        String gl = "txn_id,account,amount,gl_date\nTXN1,4000,10.00,2026-01-01\n";
        Path billingFile = home.resolve("billing-demo.csv");
        Path glFile = home.resolve("gl-demo.csv");
        Files.writeString(billingFile, billing, StandardCharsets.UTF_8);
        Files.writeString(glFile, gl, StandardCharsets.UTF_8);
        billingSha256 = FileHasher.sha256(billingFile);
        Files.writeString(home.resolve("billing-demo.csv.sha256"),
                billingSha256 + "  billing-demo.csv\n");
        Files.writeString(home.resolve("gl-demo.csv.sha256"),
                FileHasher.sha256(glFile) + "  gl-demo.csv\n");

        LocalInboundFiles local = new LocalInboundFiles(properties);
        SftpInboundFiles sftp = new SftpInboundFiles(properties);
        InboundFileGateway gateway = new InboundFileGateway(properties, local, sftp);
        ReconciliationFileStore store = new ReconciliationFileStore(properties, new ObjectMapper());
        transfer = new InboundFileTransfer(gateway, store, properties);
        activities = new FileActivitiesImpl(gateway, store, properties);
    }

    @Test
    void validatesAgainstSidecarAndIdentifiesRunBySha256() {
        FileNotification notification = new FileNotification("billing-demo.csv");
        FileValidationResult copied = transfer.copyHomeToDestination(notification);
        assertTrue(copied.isValid(), copied.getMessage());

        // No expectedSha256 on the signal: integrity is checked against the producer's sidecar.
        FileValidationResult validated = activities.locateAndValidate(notification);
        assertTrue(validated.isValid(), validated.getMessage());
        assertEquals(64, validated.getComputedSha256().length());
        assertEquals(FileIds.fromSha256(billingSha256), validated.getFileId());
        assertTrue(Files.isDirectory(root.resolve("destination").resolve("work").resolve(validated.getFileId())));
    }

    @Test
    void throwsRetryableFileNotFoundWhenNotCopied() {
        ApplicationFailure ex = assertThrows(ApplicationFailure.class,
                () -> activities.locateAndValidate(new FileNotification("billing-demo.csv")));
        assertEquals("FileNotFound", ex.getType());
        assertTrue(ex.getOriginalMessage().contains("FILE_NOT_FOUND"));
    }

    @Test
    void throwsNonRetryableHashMismatchWhenContentDiffersFromSidecar() throws Exception {
        // Producer sidecar says one thing; the actual bytes differ → tamper/corruption is caught.
        Files.writeString(home.resolve("billing-demo.csv.sha256"),
                "0000000000000000000000000000000000000000000000000000000000000000  billing-demo.csv\n");
        FileNotification notification = new FileNotification("billing-demo.csv");
        assertTrue(transfer.copyHomeToDestination(notification).isValid());

        ApplicationFailure ex = assertThrows(ApplicationFailure.class,
                () -> activities.locateAndValidate(notification));
        assertEquals("HashMismatch", ex.getType());
        assertTrue(ex.getOriginalMessage().contains("HASH_MISMATCH"));
    }
}
