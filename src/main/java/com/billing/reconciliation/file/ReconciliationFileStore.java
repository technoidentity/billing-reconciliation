package com.billing.reconciliation.file;

import com.billing.reconciliation.config.BillingProperties;
import com.billing.reconciliation.dto.BillingAmountCorrection;
import com.billing.reconciliation.dto.BillingTransactionDto;
import com.billing.reconciliation.dto.CustomerDto;
import com.billing.reconciliation.dto.DiscrepancyDto;
import com.billing.reconciliation.dto.ProcessedTransactionDto;
import com.billing.reconciliation.dto.ValidationErrorDto;
import com.billing.reconciliation.dto.VendorDto;
import com.billing.reconciliation.model.BatchRef;
import com.billing.reconciliation.model.DiscrepancySummary;
import com.billing.reconciliation.model.ReconciliationResult;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVPrinter;
import org.apache.commons.csv.CSVRecord;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

@Component
public class ReconciliationFileStore {

    static final String[] BILLING_HEADER = {
            "id", "txn_id", "vendor_id", "customer_id", "amount", "currency", "txn_date", "due_date"};
    static final String[] GL_HEADER = {"txn_id", "account", "amount", "gl_date"};
    static final String[] PROCESSED_HEADER = {
            "txn_id", "vendor_name", "customer_name", "currency", "original_amount",
            "adjustment", "discount", "penalty", "final_amount", "batch_no", "due_date"};
    static final String[] DISCREPANCY_HEADER = {
            "txn_id", "billing_amount", "gl_amount", "difference", "reason", "compensated"};
    static final String[] VALIDATION_HEADER = {"txn_id", "reason"};
    static final String[] VENDOR_HEADER = {"vendor_id", "vendor_name", "status"};
    static final String[] CUSTOMER_HEADER = {"customer_id", "customer_name", "status"};

    private final BillingProperties properties;
    private final ObjectMapper objectMapper;

    public ReconciliationFileStore(BillingProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    public Path baseDir() {
        return properties.getFiles().destinationPath();
    }

    /**
     * Copied source file in the SFTP destination directory (same names as home).
     */
    public Path destinationFile(String fileName) {
        return baseDir().resolve(Path.of(fileName).getFileName().toString());
    }

    public Path workDir(String fileId) {
        return baseDir().resolve("work").resolve(fileId);
    }

    public Path outboundDir(String fileId) {
        return baseDir().resolve("outbound").resolve(fileId);
    }

    public Path referenceDir() {
        return baseDir().resolve("reference");
    }

    public Path billingCsv(String fileId) {
        return workDir(fileId).resolve("billing.csv");
    }

    public Path glCsv(String fileId) {
        return workDir(fileId).resolve("gl.csv");
    }

    public Path batchCsv(String fileId, int batchNo) {
        return workDir(fileId).resolve("batches").resolve("batch-" + batchNo + ".csv");
    }

    public Path glBatchCsv(String fileId, int batchNo) {
        return workDir(fileId).resolve("gl").resolve("batch-" + batchNo + ".csv");
    }

    public Path validBatchCsv(String fileId, int batchNo) {
        return workDir(fileId).resolve("valid").resolve("batch-" + batchNo + ".csv");
    }

    public Path processedBatchCsv(String fileId, int batchNo) {
        return workDir(fileId).resolve("processed").resolve("batch-" + batchNo + ".csv");
    }

    public Path discrepancyBatchCsv(String fileId, int batchNo) {
        return workDir(fileId).resolve("discrepancies").resolve("batch-" + batchNo + ".csv");
    }

    public Path validationErrorsCsv(String fileId, int batchNo) {
        return workDir(fileId).resolve("validation-errors-batch-" + batchNo + ".csv");
    }

    public Path vendorCache(String fileId, int batchNo) {
        return workDir(fileId).resolve("vendors-batch-" + batchNo + ".json");
    }

    public Path customerCache(String fileId, int batchNo) {
        return workDir(fileId).resolve("customers-batch-" + batchNo + ".json");
    }

    public Path auditLog(String fileId) {
        return outboundDir(fileId).resolve("audit.log");
    }

    public Path resultJson(String fileId) {
        return outboundDir(fileId).resolve("result.json");
    }

    public void ensureDestinationDir() {
        try {
            Files.createDirectories(baseDir());
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to create destination directory", ex);
        }
    }

    public void ensureWorkDir(String fileId) {
        try {
            Files.createDirectories(baseDir());
            Files.createDirectories(workDir(fileId).resolve("batches"));
            Files.createDirectories(workDir(fileId).resolve("gl"));
            Files.createDirectories(workDir(fileId).resolve("valid"));
            Files.createDirectories(workDir(fileId).resolve("processed"));
            Files.createDirectories(workDir(fileId).resolve("discrepancies"));
            Files.createDirectories(outboundDir(fileId));
            Files.createDirectories(referenceDir());
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to create work directories for " + fileId, ex);
        }
    }

    /**
     * Copies destination source files into the work directory used by child workflows.
     */
    public void promoteDestinationToWork(String fileId, String billingFileName, String glFileName) {
        try {
            Files.createDirectories(workDir(fileId));
            Files.copy(destinationFile(billingFileName), billingCsv(fileId),
                    StandardCopyOption.REPLACE_EXISTING);
            Files.copy(destinationFile(glFileName), glCsv(fileId),
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to copy destination files into work for " + fileId, ex);
        }
    }

    public List<BatchRef> sliceBatches(String fileId, int batchSize, Consumer<Integer> onProgress) {
        int size = Math.max(1, batchSize);
        Map<String, Integer> txnToBatch = new HashMap<>();
        List<BatchRef> batches = new ArrayList<>();
        Path source = billingCsv(fileId);
        try (BufferedReader reader = Files.newBufferedReader(source, StandardCharsets.UTF_8);
             CSVParser parser = CSVFormat.DEFAULT.builder()
                     .setHeader(BILLING_HEADER)
                     .setSkipHeaderRecord(true)
                     .build()
                     .parse(reader)) {
            int batchNo = 1;
            int inBatch = 0;
            long firstId = -1;
            long lastId = -1;
            CSVPrinter printer = null;
            int scanned = 0;
            for (CSVRecord record : parser) {
                if (inBatch == 0) {
                    printer = newPrinter(batchCsv(fileId, batchNo), BILLING_HEADER);
                    firstId = -1;
                }
                BillingTransactionDto row = mapBilling(record);
                if (firstId < 0) {
                    firstId = row.getId();
                }
                lastId = row.getId();
                printBilling(printer, row);
                txnToBatch.put(row.getTxnId(), batchNo);
                inBatch++;
                scanned++;
                if (scanned % 5000 == 0 && onProgress != null) {
                    onProgress.accept(scanned);
                }
                if (inBatch >= size) {
                    printer.close();
                    batches.add(new BatchRef(batchNo, firstId, lastId, fileId));
                    batchNo++;
                    inBatch = 0;
                    printer = null;
                }
            }
            if (printer != null) {
                printer.close();
                batches.add(new BatchRef(batchNo, firstId, lastId, fileId));
            }
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to slice billing file " + fileId, ex);
        }
        sliceGl(fileId, txnToBatch);
        return batches;
    }

    private void sliceGl(String fileId, Map<String, Integer> txnToBatch) {
        Map<Integer, CSVPrinter> printers = new HashMap<>();
        try {
            Path source = glCsv(fileId);
            if (!Files.isRegularFile(source)) {
                return;
            }
            try (BufferedReader reader = Files.newBufferedReader(source, StandardCharsets.UTF_8);
                 CSVParser parser = CSVFormat.DEFAULT.builder()
                         .setHeader(GL_HEADER)
                         .setSkipHeaderRecord(true)
                         .build()
                         .parse(reader)) {
                for (CSVRecord record : parser) {
                    String txnId = record.get("txn_id");
                    Integer batchNo = txnToBatch.get(txnId);
                    if (batchNo == null) {
                        continue;
                    }
                    CSVPrinter printer = printers.get(batchNo);
                    if (printer == null) {
                        printer = newPrinter(glBatchCsv(fileId, batchNo), GL_HEADER);
                        printers.put(batchNo, printer);
                    }
                    printer.printRecord(
                            txnId,
                            record.get("account"),
                            record.get("amount"),
                            record.get("gl_date"));
                }
            }
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to slice GL file " + fileId, ex);
        } finally {
            for (CSVPrinter printer : printers.values()) {
                try {
                    printer.close();
                } catch (IOException ignored) {
                    // ignore close errors after a write failure
                }
            }
        }
    }

    public List<BillingTransactionDto> loadBatch(BatchRef batch) {
        return loadBillingCsv(batchCsv(batch.getFileId(), batch.getBatchNo()));
    }

    public List<BillingTransactionDto> loadValidBatch(BatchRef batch) {
        Path path = validBatchCsv(batch.getFileId(), batch.getBatchNo());
        if (!Files.isRegularFile(path)) {
            return List.of();
        }
        return loadBillingCsv(path);
    }

    public void writeValidBatch(BatchRef batch, List<BillingTransactionDto> rows) {
        writeBillingCsv(validBatchCsv(batch.getFileId(), batch.getBatchNo()), rows);
    }

    public void writeValidationErrors(BatchRef batch, List<ValidationErrorDto> errors) {
        Path path = validationErrorsCsv(batch.getFileId(), batch.getBatchNo());
        try (CSVPrinter printer = newPrinter(path, VALIDATION_HEADER)) {
            if (errors != null) {
                for (ValidationErrorDto error : errors) {
                    printer.printRecord(error.getTxnId(), error.getReason());
                }
            }
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to write validation errors", ex);
        }
    }

    public List<String> distinctVendorIds(BatchRef batch) {
        return loadValidBatch(batch).stream().map(BillingTransactionDto::getVendorId).distinct().toList();
    }

    public List<String> distinctCustomerIds(BatchRef batch) {
        return loadValidBatch(batch).stream().map(BillingTransactionDto::getCustomerId).distinct().toList();
    }

    public void saveVendorCache(BatchRef batch, List<VendorDto> vendors) {
        writeJson(vendorCache(batch.getFileId(), batch.getBatchNo()), vendors == null ? List.of() : vendors);
    }

    public void saveCustomerCache(BatchRef batch, List<CustomerDto> customers) {
        writeJson(customerCache(batch.getFileId(), batch.getBatchNo()), customers == null ? List.of() : customers);
    }

    public Map<String, VendorDto> loadVendorCache(BatchRef batch) {
        List<VendorDto> rows = readJson(vendorCache(batch.getFileId(), batch.getBatchNo()), new TypeReference<>() {
        });
        Map<String, VendorDto> map = new LinkedHashMap<>();
        for (VendorDto row : rows) {
            map.put(row.getVendorId(), row);
        }
        return map;
    }

    public Map<String, CustomerDto> loadCustomerCache(BatchRef batch) {
        List<CustomerDto> rows = readJson(customerCache(batch.getFileId(), batch.getBatchNo()), new TypeReference<>() {
        });
        Map<String, CustomerDto> map = new LinkedHashMap<>();
        for (CustomerDto row : rows) {
            map.put(row.getCustomerId(), row);
        }
        return map;
    }

    public void writeProcessed(BatchRef batch, List<ProcessedTransactionDto> rows) {
        Path path = processedBatchCsv(batch.getFileId(), batch.getBatchNo());
        try (CSVPrinter printer = newPrinter(path, PROCESSED_HEADER)) {
            if (rows != null) {
                for (ProcessedTransactionDto row : rows) {
                    printProcessed(printer, row);
                }
            }
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to write processed batch", ex);
        }
    }

    public List<ProcessedTransactionDto> loadProcessed(BatchRef batch) {
        Path path = processedBatchCsv(batch.getFileId(), batch.getBatchNo());
        if (!Files.isRegularFile(path)) {
            return List.of();
        }
        List<ProcessedTransactionDto> rows = new ArrayList<>();
        try (BufferedReader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8);
             CSVParser parser = CSVFormat.DEFAULT.builder()
                     .setHeader(PROCESSED_HEADER)
                     .setSkipHeaderRecord(true)
                     .build()
                     .parse(reader)) {
            for (CSVRecord record : parser) {
                rows.add(mapProcessed(record));
            }
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to load processed batch", ex);
        }
        return rows;
    }

    public List<ProcessedTransactionDto> loadProcessedByTxnIds(BatchRef batch, List<String> txnIds) {
        if (txnIds == null || txnIds.isEmpty()) {
            return List.of();
        }
        java.util.Set<String> wanted = new java.util.HashSet<>(txnIds);
        List<ProcessedTransactionDto> matched = new ArrayList<>();
        for (ProcessedTransactionDto row : loadProcessed(batch)) {
            if (wanted.contains(row.getTxnId())) {
                matched.add(row);
            }
        }
        return matched;
    }

    public void updateProcessedMoney(BatchRef batch, List<ProcessedTransactionDto> updated) {
        if (updated == null || updated.isEmpty()) {
            return;
        }
        Map<String, ProcessedTransactionDto> byId = new HashMap<>();
        for (ProcessedTransactionDto row : updated) {
            byId.put(row.getTxnId(), row);
        }
        List<ProcessedTransactionDto> all = loadProcessed(batch);
        for (int i = 0; i < all.size(); i++) {
            ProcessedTransactionDto replacement = byId.get(all.get(i).getTxnId());
            if (replacement != null) {
                all.set(i, replacement);
            }
        }
        writeProcessed(batch, all);
    }

    public long countGlEntries(BatchRef batch) {
        Path path = glBatchCsv(batch.getFileId(), batch.getBatchNo());
        if (!Files.isRegularFile(path)) {
            return 0;
        }
        return loadGlAmounts(path).size();
    }

    public Map<String, BigDecimal> loadGlAmountsForBatch(BatchRef batch) {
        return loadGlAmounts(glBatchCsv(batch.getFileId(), batch.getBatchNo()));
    }

    public Map<String, BigDecimal> loadGlAmounts(BatchRef batch, List<String> txnIds) {
        Map<String, BigDecimal> all = loadGlAmountsForBatch(batch);
        if (txnIds == null || txnIds.isEmpty()) {
            return all;
        }
        Map<String, BigDecimal> filtered = new HashMap<>();
        for (String txnId : txnIds) {
            BigDecimal amount = all.get(txnId);
            if (amount != null) {
                filtered.put(txnId, amount);
            }
        }
        return filtered;
    }

    public void writeDiscrepancies(BatchRef batch, List<DiscrepancyDto> discrepancies) {
        Path path = discrepancyBatchCsv(batch.getFileId(), batch.getBatchNo());
        try (CSVPrinter printer = newPrinter(path, DISCREPANCY_HEADER)) {
            if (discrepancies != null) {
                for (DiscrepancyDto row : discrepancies) {
                    printer.printRecord(
                            row.getTxnId(),
                            row.getBillingAmount(),
                            row.getGlAmount(),
                            row.getDifference(),
                            row.getReason(),
                            "N");
                }
            }
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to write discrepancies", ex);
        }
    }

    public DiscrepancySummary discrepancySummary(BatchRef batch) {
        List<DiscrepancyRow> rows = loadDiscrepancyRows(batch);
        List<String> ids = new ArrayList<>();
        double amount = 0;
        for (DiscrepancyRow row : rows) {
            ids.add(row.txnId);
            if (row.difference != null) {
                amount += row.difference.abs().doubleValue();
            }
        }
        return new DiscrepancySummary(ids.size(), amount, ids);
    }

    public void updateBillingAmounts(BatchRef batch, List<BillingAmountCorrection> corrections) {
        if (corrections == null || corrections.isEmpty()) {
            return;
        }
        Map<String, BigDecimal> byTxn = new HashMap<>();
        for (BillingAmountCorrection correction : corrections) {
            byTxn.put(correction.getTxnId(), correction.getAmount());
        }
        List<BillingTransactionDto> rows = loadBatch(batch);
        for (BillingTransactionDto row : rows) {
            BigDecimal amount = byTxn.get(row.getTxnId());
            if (amount != null) {
                row.setAmount(amount);
            }
        }
        writeBillingCsv(batchCsv(batch.getFileId(), batch.getBatchNo()), rows);
    }

    public void markDiscrepanciesCompensated(BatchRef batch, List<String> txnIds) {
        if (txnIds == null || txnIds.isEmpty()) {
            return;
        }
        java.util.Set<String> wanted = new java.util.HashSet<>(txnIds);
        List<DiscrepancyRow> rows = loadDiscrepancyRows(batch);
        Path path = discrepancyBatchCsv(batch.getFileId(), batch.getBatchNo());
        try (CSVPrinter printer = newPrinter(path, DISCREPANCY_HEADER)) {
            for (DiscrepancyRow row : rows) {
                String compensated = wanted.contains(row.txnId) ? "Y" : row.compensated;
                printer.printRecord(row.txnId, row.billingAmount, row.glAmount, row.difference, row.reason, compensated);
            }
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to mark discrepancies compensated", ex);
        }
    }

    public String buildComplianceCsv(String fileId) {
        long processed = 0;
        long invalid = 0;
        long discrepancies = 0;
        double discrepancyAmount = 0;
        Path work = workDir(fileId);
        try {
            if (Files.isDirectory(work.resolve("processed"))) {
                try (var stream = Files.list(work.resolve("processed"))) {
                    for (Path path : stream.toList()) {
                        processed += countDataRows(path);
                    }
                }
            }
            try (var stream = Files.list(work)) {
                for (Path path : stream.toList()) {
                    String name = path.getFileName().toString();
                    if (name.startsWith("validation-errors-batch-") && name.endsWith(".csv")) {
                        invalid += countDataRows(path);
                    }
                }
            }
            if (Files.isDirectory(work.resolve("discrepancies"))) {
                try (var stream = Files.list(work.resolve("discrepancies"))) {
                    for (Path path : stream.toList()) {
                        for (DiscrepancyRow row : loadDiscrepancyRows(path)) {
                            discrepancies++;
                            if (row.difference != null) {
                                discrepancyAmount += row.difference.abs().doubleValue();
                            }
                        }
                    }
                }
            }
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to build compliance CSV", ex);
        }
        return "file_id,processed,invalid,discrepancies,discrepancy_amount\n"
                + fileId + "," + processed + "," + invalid + "," + discrepancies + "," + discrepancyAmount + "\n";
    }

    public String buildDiscrepancyCsv(String fileId) {
        StringBuilder csv = new StringBuilder("txn_id,billing_amount,gl_amount,difference,reason,compensated\n");
        Path dir = workDir(fileId).resolve("discrepancies");
        if (!Files.isDirectory(dir)) {
            return csv.toString();
        }
        try (var stream = Files.list(dir)) {
            List<Path> paths = stream.sorted().toList();
            for (Path path : paths) {
                for (DiscrepancyRow row : loadDiscrepancyRows(path)) {
                    csv.append(row.txnId).append(',')
                            .append(row.billingAmount).append(',')
                            .append(row.glAmount).append(',')
                            .append(row.difference).append(',')
                            .append(row.reason).append(',')
                            .append(row.compensated).append('\n');
                }
            }
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to build discrepancy CSV", ex);
        }
        return csv.toString();
    }

    public void saveReport(String fileId, String name, String content) {
        Path path = outboundDir(fileId).resolve(name);
        try {
            Files.createDirectories(path.getParent());
            Files.writeString(path, content == null ? "" : content, StandardCharsets.UTF_8);
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to write report " + name, ex);
        }
    }

    public void saveNotification(String fileId, String channel, String recipient, String subject, String message) {
        Path path = outboundDir(fileId).resolve("notifications.jsonl");
        String line = channel + "\t" + recipient + "\t" + subject + "\t" + message + "\n";
        try {
            Files.createDirectories(path.getParent());
            Files.writeString(path, line, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to write notification", ex);
        }
    }

    public void logAudit(String fileId, String stepName, String message) {
        String line = Instant.now() + "\t" + stepName + "\t" + message + "\n";
        Path path = auditLog(fileId);
        try {
            Files.createDirectories(path.getParent());
            Files.writeString(path, line, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to write audit log", ex);
        }
    }

    public void writeResult(String fileId, ReconciliationResult result) {
        writeJson(resultJson(fileId), result);
    }

    public ReconciliationResult readResult(String fileId) {
        Path path = resultJson(fileId);
        if (!Files.isRegularFile(path)) {
            return null;
        }
        try {
            return objectMapper.readValue(path.toFile(), ReconciliationResult.class);
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to read result for " + fileId, ex);
        }
    }

    public List<VendorDto> findReferenceVendors(List<String> ids) {
        Map<String, VendorDto> all = loadReferenceVendors();
        if (ids == null || ids.isEmpty()) {
            return new ArrayList<>(all.values());
        }
        List<VendorDto> matched = new ArrayList<>();
        for (String id : ids) {
            VendorDto vendor = all.get(id);
            if (vendor != null) {
                matched.add(vendor);
            } else {
                matched.add(synthesizeVendor(id));
            }
        }
        return matched;
    }

    public List<CustomerDto> findReferenceCustomers(List<String> ids) {
        Map<String, CustomerDto> all = loadReferenceCustomers();
        if (ids == null || ids.isEmpty()) {
            return new ArrayList<>(all.values());
        }
        List<CustomerDto> matched = new ArrayList<>();
        for (String id : ids) {
            CustomerDto customer = all.get(id);
            if (customer != null) {
                matched.add(customer);
            } else {
                matched.add(synthesizeCustomer(id));
            }
        }
        return matched;
    }

    public Map<String, VendorDto> loadReferenceVendors() {
        Path path = referenceDir().resolve("vendors.csv");
        Map<String, VendorDto> map = new LinkedHashMap<>();
        if (!Files.isRegularFile(path)) {
            return map;
        }
        try (BufferedReader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8);
             CSVParser parser = CSVFormat.DEFAULT.builder()
                     .setHeader(VENDOR_HEADER)
                     .setSkipHeaderRecord(true)
                     .build()
                     .parse(reader)) {
            for (CSVRecord record : parser) {
                VendorDto vendor = new VendorDto(record.get("vendor_id"), record.get("vendor_name"), record.get("status"));
                map.put(vendor.getVendorId(), vendor);
            }
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to load reference vendors", ex);
        }
        return map;
    }

    public Map<String, CustomerDto> loadReferenceCustomers() {
        Path path = referenceDir().resolve("customers.csv");
        Map<String, CustomerDto> map = new LinkedHashMap<>();
        if (!Files.isRegularFile(path)) {
            return map;
        }
        try (BufferedReader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8);
             CSVParser parser = CSVFormat.DEFAULT.builder()
                     .setHeader(CUSTOMER_HEADER)
                     .setSkipHeaderRecord(true)
                     .build()
                     .parse(reader)) {
            for (CSVRecord record : parser) {
                CustomerDto customer = new CustomerDto(
                        record.get("customer_id"), record.get("customer_name"), record.get("status"));
                map.put(customer.getCustomerId(), customer);
            }
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to load reference customers", ex);
        }
        return map;
    }

    static VendorDto synthesizeVendor(String id) {
        int n = parseTrailingInt(id);
        String status = n > 0 && n % 20 == 0 ? "INACTIVE" : "ACTIVE";
        return new VendorDto(id, "Vendor " + Math.max(n, 0), status);
    }

    static CustomerDto synthesizeCustomer(String id) {
        int n = parseTrailingInt(id);
        String status = n > 0 && n % 50 == 0 ? "INACTIVE" : "ACTIVE";
        return new CustomerDto(id, "Customer " + Math.max(n, 0), status);
    }

    private static int parseTrailingInt(String id) {
        if (id == null) {
            return 0;
        }
        String digits = id.replaceAll("\\D+", "");
        if (digits.isEmpty()) {
            return 0;
        }
        try {
            return Integer.parseInt(digits);
        } catch (NumberFormatException ex) {
            return 0;
        }
    }

    private List<BillingTransactionDto> loadBillingCsv(Path path) {
        List<BillingTransactionDto> rows = new ArrayList<>();
        if (!Files.isRegularFile(path)) {
            return rows;
        }
        try (BufferedReader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8);
             CSVParser parser = CSVFormat.DEFAULT.builder()
                     .setHeader(BILLING_HEADER)
                     .setSkipHeaderRecord(true)
                     .build()
                     .parse(reader)) {
            for (CSVRecord record : parser) {
                rows.add(mapBilling(record));
            }
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to load billing CSV " + path, ex);
        }
        return rows;
    }

    private void writeBillingCsv(Path path, List<BillingTransactionDto> rows) {
        try (CSVPrinter printer = newPrinter(path, BILLING_HEADER)) {
            if (rows != null) {
                for (BillingTransactionDto row : rows) {
                    printBilling(printer, row);
                }
            }
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to write billing CSV " + path, ex);
        }
    }

    private Map<String, BigDecimal> loadGlAmounts(Path path) {
        Map<String, BigDecimal> glByTxn = new HashMap<>();
        if (!Files.isRegularFile(path)) {
            return glByTxn;
        }
        try (BufferedReader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8);
             CSVParser parser = CSVFormat.DEFAULT.builder()
                     .setHeader(GL_HEADER)
                     .setSkipHeaderRecord(true)
                     .build()
                     .parse(reader)) {
            for (CSVRecord record : parser) {
                glByTxn.put(record.get("txn_id"), decimal(record.get("amount")));
            }
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to load GL CSV " + path, ex);
        }
        return glByTxn;
    }

    private List<DiscrepancyRow> loadDiscrepancyRows(BatchRef batch) {
        return loadDiscrepancyRows(discrepancyBatchCsv(batch.getFileId(), batch.getBatchNo()));
    }

    private List<DiscrepancyRow> loadDiscrepancyRows(Path path) {
        List<DiscrepancyRow> rows = new ArrayList<>();
        if (!Files.isRegularFile(path)) {
            return rows;
        }
        try (BufferedReader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8);
             CSVParser parser = CSVFormat.DEFAULT.builder()
                     .setHeader(DISCREPANCY_HEADER)
                     .setSkipHeaderRecord(true)
                     .build()
                     .parse(reader)) {
            for (CSVRecord record : parser) {
                DiscrepancyRow row = new DiscrepancyRow();
                row.txnId = record.get("txn_id");
                row.billingAmount = decimal(record.get("billing_amount"));
                row.glAmount = decimal(record.get("gl_amount"));
                row.difference = decimal(record.get("difference"));
                row.reason = record.get("reason");
                row.compensated = record.get("compensated");
                rows.add(row);
            }
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to load discrepancies " + path, ex);
        }
        return rows;
    }

    private long countDataRows(Path path) throws IOException {
        if (!Files.isRegularFile(path)) {
            return 0;
        }
        long lines = 0;
        try (BufferedReader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            while (reader.readLine() != null) {
                lines++;
            }
        }
        return Math.max(0, lines - 1);
    }

    private CSVPrinter newPrinter(Path path, String[] header) throws IOException {
        Files.createDirectories(path.getParent());
        BufferedWriter writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8);
        return new CSVPrinter(writer, CSVFormat.DEFAULT.builder().setHeader(header).build());
    }

    private void printBilling(CSVPrinter printer, BillingTransactionDto row) throws IOException {
        printer.printRecord(
                row.getId(),
                row.getTxnId(),
                row.getVendorId(),
                row.getCustomerId(),
                row.getAmount(),
                row.getCurrency(),
                row.getTxnDate(),
                row.getDueDate());
    }

    private void printProcessed(CSVPrinter printer, ProcessedTransactionDto row) throws IOException {
        printer.printRecord(
                row.getTxnId(),
                row.getVendorName(),
                row.getCustomerName(),
                row.getCurrency(),
                row.getOriginalAmount(),
                row.getAdjustment(),
                row.getDiscount(),
                row.getPenalty(),
                row.getFinalAmount(),
                row.getBatchNo(),
                row.getDueDate());
    }

    private BillingTransactionDto mapBilling(CSVRecord record) {
        return new BillingTransactionDto(
                parseLong(record.get("id")),
                record.get("txn_id"),
                record.get("vendor_id"),
                record.get("customer_id"),
                decimal(record.get("amount")),
                record.get("currency"),
                date(record.get("txn_date")),
                date(record.get("due_date")));
    }

    private ProcessedTransactionDto mapProcessed(CSVRecord record) {
        return new ProcessedTransactionDto(
                record.get("txn_id"),
                record.get("vendor_name"),
                record.get("customer_name"),
                record.get("currency"),
                decimal(record.get("original_amount")),
                decimal(record.get("adjustment")),
                decimal(record.get("discount")),
                decimal(record.get("penalty")),
                decimal(record.get("final_amount")),
                (int) parseLong(record.get("batch_no")),
                date(record.get("due_date")));
    }

    private void writeJson(Path path, Object value) {
        try {
            Files.createDirectories(path.getParent());
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(path.toFile(), value);
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to write JSON " + path, ex);
        }
    }

    private <T> T readJson(Path path, TypeReference<T> type) {
        if (!Files.isRegularFile(path)) {
            try {
                return objectMapper.readValue("[]", type);
            } catch (IOException ex) {
                throw new IllegalStateException("Failed to read empty JSON", ex);
            }
        }
        try {
            return objectMapper.readValue(path.toFile(), type);
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to read JSON " + path, ex);
        }
    }

    private static long parseLong(String value) {
        if (value == null || value.isBlank()) {
            return 0;
        }
        return Long.parseLong(value.trim());
    }

    private static BigDecimal decimal(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return new BigDecimal(value.trim());
    }

    private static LocalDate date(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return LocalDate.parse(value.trim());
    }

    private static final class DiscrepancyRow {
        private String txnId;
        private BigDecimal billingAmount;
        private BigDecimal glAmount;
        private BigDecimal difference;
        private String reason;
        private String compensated;
    }
}
