package com.billing.reconciliation.db;

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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Repository
public class BillingJdbc {

    private final JdbcTemplate jdbc;

    public BillingJdbc(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void startRun(String runId, String workflowId) {
        jdbc.update(
                """
                INSERT INTO reconciliation_runs (run_id, workflow_id, status, started_at)
                VALUES (?, ?, 'RUNNING', ?)
                ON CONFLICT (run_id) DO NOTHING
                """,
                runId, workflowId, Timestamp.from(Instant.now()));
    }

    public long countTransactions() {
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM billing_transactions", Long.class);
        return count == null ? 0 : count;
    }

    /**
     * Slices the transactions into batches of at most {@code batchSize} rows by row position, not by
     * id range, so sparse / non-contiguous ids still yield exactly ceil(count / batchSize) batches
     * (e.g. 12 batches for 1.2M at 100K). Each {@link BatchRef} carries the inclusive id bounds of
     * its slice so downstream activities can filter with {@code id BETWEEN from AND to}.
     */
    public List<BatchRef> listBatches(int batchSize) {
        List<BatchRef> batches = jdbc.query(
                """
                SELECT MIN(id) AS from_id, MAX(id) AS to_id
                FROM (
                    SELECT id, ((ROW_NUMBER() OVER (ORDER BY id) - 1) / ?) AS grp
                    FROM billing_transactions
                ) ranked
                GROUP BY grp
                ORDER BY grp
                """,
                (rs, i) -> new BatchRef(i + 1, rs.getLong("from_id"), rs.getLong("to_id")),
                batchSize);
        return new ArrayList<>(batches);
    }

    /**
     * Loads every row in the inclusive id range for Java schema validation (not only {@code NEW}),
     * so Continue-As-New re-validates after a data fix.
     */
    public List<BillingTransactionDto> loadBatchForValidation(long fromId, long toId) {
        return jdbc.query(
                """
                SELECT id, txn_id, vendor_id, customer_id, amount, currency
                FROM billing_transactions
                WHERE id BETWEEN ? AND ?
                ORDER BY id
                """,
                (rs, i) -> mapBillingTransaction(rs),
                fromId, toId);
    }

    /**
     * VALID rows in the inclusive id range, ready for Java enrichment.
     */
    public List<BillingTransactionDto> loadValidBatch(long fromId, long toId) {
        return jdbc.query(
                """
                SELECT id, txn_id, vendor_id, customer_id, amount, currency
                FROM billing_transactions
                WHERE id BETWEEN ? AND ? AND status = 'VALID'
                ORDER BY id
                """,
                (rs, i) -> mapBillingTransaction(rs),
                fromId, toId);
    }

    /**
     * Batched status updates. Empty lists are skipped. Ids are chunked to stay under parameter limits.
     */
    public void updateStatuses(List<Long> validIds, List<Long> invalidIds) {
        updateStatusChunked(validIds, "VALID");
        updateStatusChunked(invalidIds, "INVALID");
    }

    /**
     * Replaces {@code validation_errors} for the batch: delete existing rows for the id range, then
     * batch-insert the current per-field failures from Java validation.
     */
    public void replaceValidationErrors(String runId, BatchRef batch, List<ValidationErrorDto> errors) {
        jdbc.update(
                """
                DELETE FROM validation_errors
                WHERE run_id = ?
                  AND txn_id IN (SELECT txn_id FROM billing_transactions WHERE id BETWEEN ? AND ?)
                """,
                runId, batch.getFromId(), batch.getToId());
        if (errors == null || errors.isEmpty()) {
            return;
        }
        jdbc.batchUpdate(
                "INSERT INTO validation_errors (run_id, txn_id, reason) VALUES (?, ?, ?)",
                errors,
                500,
                (ps, error) -> {
                    ps.setString(1, runId);
                    ps.setString(2, error.getTxnId());
                    ps.setString(3, error.getReason());
                });
    }

    private void updateStatusChunked(List<Long> ids, String status) {
        if (ids == null || ids.isEmpty()) {
            return;
        }
        final int chunkSize = 5000;
        for (int i = 0; i < ids.size(); i += chunkSize) {
            List<Long> chunk = ids.subList(i, Math.min(i + chunkSize, ids.size()));
            String placeholders = String.join(",", chunk.stream().map(id -> "?").toList());
            Object[] args = new Object[chunk.size() + 1];
            args[0] = status;
            for (int j = 0; j < chunk.size(); j++) {
                args[j + 1] = chunk.get(j);
            }
            jdbc.update(
                    "UPDATE billing_transactions SET status = ? WHERE id IN (" + placeholders + ")",
                    args);
        }
    }

    public List<String> distinctVendorIds(long fromId, long toId) {
        return jdbc.queryForList(
                """
                SELECT DISTINCT vendor_id FROM billing_transactions
                WHERE id BETWEEN ? AND ? AND status = 'VALID'
                """,
                String.class, fromId, toId);
    }

    public List<String> distinctCustomerIds(long fromId, long toId) {
        return jdbc.queryForList(
                """
                SELECT DISTINCT customer_id FROM billing_transactions
                WHERE id BETWEEN ? AND ? AND status = 'VALID'
                """,
                String.class, fromId, toId);
    }

    public List<VendorDto> findVendors(List<String> ids) {
        if (ids.isEmpty()) {
            return List.of();
        }
        String placeholders = String.join(",", ids.stream().map(id -> "?").toList());
        return jdbc.query(
                "SELECT vendor_id, vendor_name, status FROM vendors WHERE vendor_id IN (" + placeholders + ")",
                (rs, i) -> new VendorDto(rs.getString("vendor_id"), rs.getString("vendor_name"), rs.getString("status")),
                ids.toArray());
    }

    public List<CustomerDto> findCustomers(List<String> ids) {
        if (ids.isEmpty()) {
            return List.of();
        }
        String placeholders = String.join(",", ids.stream().map(id -> "?").toList());
        return jdbc.query(
                "SELECT customer_id, customer_name, status FROM customers WHERE customer_id IN (" + placeholders + ")",
                (rs, i) -> new CustomerDto(rs.getString("customer_id"), rs.getString("customer_name"), rs.getString("status")),
                ids.toArray());
    }

    public void saveVendorCache(String runId, List<VendorDto> vendors) {
        if (vendors.isEmpty()) {
            return;
        }
        jdbc.batchUpdate(
                """
                INSERT INTO vendor_cache (run_id, vendor_id, vendor_name, status)
                VALUES (?, ?, ?, ?)
                ON CONFLICT (run_id, vendor_id) DO UPDATE
                    SET vendor_name = EXCLUDED.vendor_name, status = EXCLUDED.status
                """,
                vendors,
                200,
                (ps, vendor) -> {
                    ps.setString(1, runId);
                    ps.setString(2, vendor.getVendorId());
                    ps.setString(3, vendor.getVendorName());
                    ps.setString(4, vendor.getStatus() == null ? "ACTIVE" : vendor.getStatus());
                });
    }

    public void saveCustomerCache(String runId, List<CustomerDto> customers) {
        if (customers.isEmpty()) {
            return;
        }
        jdbc.batchUpdate(
                """
                INSERT INTO customer_cache (run_id, customer_id, customer_name, status)
                VALUES (?, ?, ?, ?)
                ON CONFLICT (run_id, customer_id) DO UPDATE
                    SET customer_name = EXCLUDED.customer_name, status = EXCLUDED.status
                """,
                customers,
                200,
                (ps, customer) -> {
                    ps.setString(1, runId);
                    ps.setString(2, customer.getCustomerId());
                    ps.setString(3, customer.getCustomerName());
                    ps.setString(4, customer.getStatus() == null ? "ACTIVE" : customer.getStatus());
                });
    }

    public Map<String, VendorDto> loadVendorCache(String runId) {
        Map<String, VendorDto> vendors = new HashMap<>();
        List<VendorDto> rows = jdbc.query(
                "SELECT vendor_id, vendor_name, status FROM vendor_cache WHERE run_id = ?",
                (rs, i) -> new VendorDto(rs.getString("vendor_id"), rs.getString("vendor_name"), rs.getString("status")),
                runId);
        for (VendorDto vendor : rows) {
            vendors.put(vendor.getVendorId(), vendor);
        }
        return vendors;
    }

    public Map<String, CustomerDto> loadCustomerCache(String runId) {
        Map<String, CustomerDto> customers = new HashMap<>();
        List<CustomerDto> rows = jdbc.query(
                "SELECT customer_id, customer_name, status FROM customer_cache WHERE run_id = ?",
                (rs, i) -> new CustomerDto(
                        rs.getString("customer_id"),
                        rs.getString("customer_name"),
                        rs.getString("status")),
                runId);
        for (CustomerDto customer : rows) {
            customers.put(customer.getCustomerId(), customer);
        }
        return customers;
    }

    public void upsertProcessedTransactions(String runId, List<ProcessedTransactionDto> rows) {
        if (rows == null || rows.isEmpty()) {
            return;
        }
        jdbc.batchUpdate(
                """
                INSERT INTO processed_transactions
                    (run_id, txn_id, vendor_name, customer_name, currency, original_amount, adjustment, discount, penalty, final_amount, batch_no)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (run_id, txn_id) DO UPDATE SET
                    vendor_name = EXCLUDED.vendor_name,
                    customer_name = EXCLUDED.customer_name,
                    currency = EXCLUDED.currency,
                    original_amount = EXCLUDED.original_amount,
                    adjustment = EXCLUDED.adjustment,
                    discount = EXCLUDED.discount,
                    penalty = EXCLUDED.penalty,
                    final_amount = EXCLUDED.final_amount,
                    batch_no = EXCLUDED.batch_no
                """,
                rows,
                500,
                (ps, row) -> {
                    ps.setString(1, runId);
                    ps.setString(2, row.getTxnId());
                    ps.setString(3, row.getVendorName());
                    ps.setString(4, row.getCustomerName());
                    ps.setString(5, row.getCurrency());
                    ps.setBigDecimal(6, row.getOriginalAmount());
                    ps.setBigDecimal(7, row.getAdjustment());
                    ps.setBigDecimal(8, row.getDiscount());
                    ps.setBigDecimal(9, row.getPenalty());
                    ps.setBigDecimal(10, row.getFinalAmount());
                    ps.setInt(11, row.getBatchNo());
                });
    }

    public List<ProcessedTransactionDto> loadProcessedBatch(String runId, BatchRef batch) {
        return jdbc.query(
                """
                SELECT DISTINCT ON (p.txn_id) p.txn_id, p.vendor_name, p.customer_name, p.currency,
                       p.original_amount, p.adjustment, p.discount, p.penalty, p.final_amount, p.batch_no,
                       t.due_date
                FROM processed_transactions p
                LEFT JOIN billing_transactions t ON t.txn_id = p.txn_id
                WHERE p.run_id = ? AND p.batch_no = ?
                ORDER BY p.txn_id, t.id
                """,
                (rs, i) -> mapProcessed(rs),
                runId, batch.getBatchNo());
    }

    public List<ProcessedTransactionDto> loadProcessedByTxnIds(String runId, List<String> txnIds) {
        if (txnIds == null || txnIds.isEmpty()) {
            return List.of();
        }
        List<ProcessedTransactionDto> rows = new ArrayList<>();
        forEachInChunk(txnIds, chunk -> {
            String ph = placeholders(chunk.size());
            List<Object> args = new ArrayList<>();
            args.add(runId);
            args.addAll(chunk);
            rows.addAll(jdbc.query(
                    "SELECT txn_id, vendor_name, customer_name, currency, original_amount, adjustment, "
                            + "discount, penalty, final_amount, batch_no, NULL AS due_date "
                            + "FROM processed_transactions WHERE run_id = ? AND txn_id IN (" + ph + ")",
                    (rs, i) -> mapProcessed(rs),
                    args.toArray()));
        });
        return rows;
    }

    public void updateProcessedMoney(String runId, List<ProcessedTransactionDto> rows) {
        if (rows == null || rows.isEmpty()) {
            return;
        }
        jdbc.batchUpdate(
                """
                UPDATE processed_transactions
                SET adjustment = ?, discount = ?, penalty = ?, final_amount = ?
                WHERE run_id = ? AND txn_id = ?
                """,
                rows,
                500,
                (ps, row) -> {
                    ps.setBigDecimal(1, row.getAdjustment());
                    ps.setBigDecimal(2, row.getDiscount());
                    ps.setBigDecimal(3, row.getPenalty());
                    ps.setBigDecimal(4, row.getFinalAmount());
                    ps.setString(5, runId);
                    ps.setString(6, row.getTxnId());
                });
    }

    public long countGlEntries(BatchRef batch) {
        Long count = jdbc.queryForObject(
                """
                SELECT COUNT(*)
                FROM gl_entries g
                JOIN billing_transactions t ON t.txn_id = g.txn_id
                WHERE t.id BETWEEN ? AND ?
                """,
                Long.class, batch.getFromId(), batch.getToId());
        return count == null ? 0 : count;
    }

    public void deleteDiscrepanciesForBatch(String runId, BatchRef batch) {
        jdbc.update(
                """
                DELETE FROM discrepancies d
                USING processed_transactions p
                WHERE d.run_id = ? AND p.run_id = ? AND p.batch_no = ? AND d.txn_id = p.txn_id
                """,
                runId, runId, batch.getBatchNo());
    }

    public Map<String, BigDecimal> loadGlAmountsForBatch(BatchRef batch) {
        Map<String, BigDecimal> glByTxn = new HashMap<>();
        List<Map.Entry<String, BigDecimal>> rows = jdbc.query(
                """
                SELECT g.txn_id, g.amount
                FROM gl_entries g
                JOIN billing_transactions t ON t.txn_id = g.txn_id
                WHERE t.id BETWEEN ? AND ?
                """,
                (rs, i) -> Map.entry(rs.getString("txn_id"), rs.getBigDecimal("amount")),
                batch.getFromId(), batch.getToId());
        for (Map.Entry<String, BigDecimal> row : rows) {
            glByTxn.put(row.getKey(), row.getValue());
        }
        return glByTxn;
    }

    public Map<String, BigDecimal> loadGlAmounts(List<String> txnIds) {
        Map<String, BigDecimal> glByTxn = new HashMap<>();
        if (txnIds == null || txnIds.isEmpty()) {
            return glByTxn;
        }
        forEachInChunk(txnIds, chunk -> {
            String ph = placeholders(chunk.size());
            List<Map.Entry<String, BigDecimal>> rows = jdbc.query(
                    "SELECT txn_id, amount FROM gl_entries WHERE txn_id IN (" + ph + ")",
                    (rs, i) -> Map.entry(rs.getString("txn_id"), rs.getBigDecimal("amount")),
                    chunk.toArray());
            for (Map.Entry<String, BigDecimal> row : rows) {
                glByTxn.put(row.getKey(), row.getValue());
            }
        });
        return glByTxn;
    }

    public void insertDiscrepancies(String runId, List<DiscrepancyDto> discrepancies) {
        if (discrepancies == null || discrepancies.isEmpty()) {
            return;
        }
        jdbc.batchUpdate(
                """
                INSERT INTO discrepancies (run_id, txn_id, billing_amount, gl_amount, difference, reason, compensated)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """,
                discrepancies,
                500,
                (ps, row) -> {
                    ps.setString(1, runId);
                    ps.setString(2, row.getTxnId());
                    ps.setBigDecimal(3, row.getBillingAmount());
                    ps.setBigDecimal(4, row.getGlAmount());
                    ps.setBigDecimal(5, row.getDifference());
                    ps.setString(6, row.getReason());
                    ps.setString(7, "N");
                });
    }

    public DiscrepancySummary discrepancySummary(String runId, BatchRef batch) {
        List<String> txnIds = jdbc.queryForList(
                """
                SELECT d.txn_id
                FROM discrepancies d
                JOIN processed_transactions p ON p.run_id = d.run_id AND p.txn_id = d.txn_id
                WHERE d.run_id = ? AND p.batch_no = ?
                ORDER BY d.txn_id
                """,
                String.class, runId, batch.getBatchNo());
        Double amount = jdbc.queryForObject(
                """
                SELECT COALESCE(SUM(ABS(d.difference)), 0)
                FROM discrepancies d
                JOIN processed_transactions p ON p.run_id = d.run_id AND p.txn_id = d.txn_id
                WHERE d.run_id = ? AND p.batch_no = ?
                """,
                Double.class, runId, batch.getBatchNo());
        return new DiscrepancySummary(txnIds.size(), amount == null ? 0 : amount, txnIds);
    }

    public void updateBillingAmounts(List<BillingAmountCorrection> corrections) {
        if (corrections == null || corrections.isEmpty()) {
            return;
        }
        jdbc.batchUpdate(
                "UPDATE billing_transactions SET amount = ? WHERE txn_id = ?",
                corrections,
                500,
                (ps, correction) -> {
                    ps.setBigDecimal(1, correction.getAmount());
                    ps.setString(2, correction.getTxnId());
                });
    }

    public void markDiscrepanciesCompensated(String runId, List<String> txnIds) {
        if (txnIds == null || txnIds.isEmpty()) {
            return;
        }
        forEachInChunk(txnIds, chunk -> {
            String ph = placeholders(chunk.size());
            List<Object> args = new ArrayList<>();
            args.add(runId);
            args.addAll(chunk);
            jdbc.update(
                    "UPDATE discrepancies SET compensated = 'Y' WHERE run_id = ? AND txn_id IN (" + ph + ")",
                    args.toArray());
        });
    }

    public void saveReport(String runId, String name, String reportType, String content) {
        jdbc.update(
                "INSERT INTO reports (run_id, report_name, report_type, content) VALUES (?, ?, ?, ?)",
                runId, name, reportType, content);
    }

    public String buildComplianceCsv(String runId) {
        return jdbc.queryForObject(
                """
                SELECT 'run_id,processed,invalid,discrepancies,discrepancy_amount' || E'\\n' ||
                       ? || ',' ||
                       (SELECT COUNT(*) FROM processed_transactions WHERE run_id = ?) || ',' ||
                       (SELECT COUNT(*) FROM validation_errors WHERE run_id = ?) || ',' ||
                       (SELECT COUNT(*) FROM discrepancies WHERE run_id = ?) || ',' ||
                       (SELECT COALESCE(SUM(ABS(difference)), 0) FROM discrepancies WHERE run_id = ?)
                """,
                String.class, runId, runId, runId, runId, runId);
    }

    public String buildDiscrepancyCsv(String runId) {
        StringBuilder csv = new StringBuilder("txn_id,billing_amount,gl_amount,difference,reason,compensated\n");
        jdbc.query(
                """
                SELECT txn_id, billing_amount, gl_amount, difference, reason, compensated
                FROM discrepancies WHERE run_id = ? ORDER BY txn_id
                """,
                rs -> {
                    csv.append(rs.getString("txn_id")).append(',')
                            .append(rs.getBigDecimal("billing_amount")).append(',')
                            .append(rs.getBigDecimal("gl_amount")).append(',')
                            .append(rs.getBigDecimal("difference")).append(',')
                            .append(rs.getString("reason")).append(',')
                            .append(rs.getString("compensated")).append('\n');
                },
                runId);
        return csv.toString();
    }

    public void saveNotification(String runId, String channel, String recipient, String subject, String message) {
        jdbc.update(
                """
                INSERT INTO notifications (run_id, channel, recipient, subject, message, sent_at)
                VALUES (?, ?, ?, ?, ?, ?)
                """,
                runId, channel, recipient, subject, message, Timestamp.from(Instant.now()));
    }

    public void logAudit(String runId, String stepName, String message) {
        jdbc.update(
                """
                INSERT INTO audit_log (run_id, step_name, message, created_at)
                VALUES (?, ?, ?, ?)
                """,
                runId, stepName, message, Timestamp.from(Instant.now()));
    }

    public void completeRun(String runId, ReconciliationResult result) {
        jdbc.update(
                """
                UPDATE reconciliation_runs
                SET status = ?, completed_at = ?, total_txns = ?, valid_txns = ?, invalid_txns = ?,
                    discrepancy_count = ?, discrepancy_amount = ?
                WHERE run_id = ?
                """,
                result.getStatus(),
                Timestamp.from(Instant.now()),
                result.getTotalTxns(),
                result.getValidTxns(),
                result.getInvalidTxns(),
                result.getDiscrepancyCount(),
                result.getDiscrepancyAmount(),
                runId);
    }

    /** Demo helper: current billing amount vs GL amount for a txn, so the mismatch is visible. */
    public Map<String, Object> transactionView(String txnId) {
        List<Map<String, Object>> rows = jdbc.query(
                """
                SELECT b.txn_id, b.amount AS billing_amount, g.amount AS gl_amount, b.status
                FROM billing_transactions b
                LEFT JOIN gl_entries g ON g.txn_id = b.txn_id
                WHERE b.txn_id = ?
                """,
                (rs, i) -> {
                    Map<String, Object> row = new HashMap<>();
                    row.put("txnId", rs.getString("txn_id"));
                    row.put("billingAmount", rs.getBigDecimal("billing_amount"));
                    row.put("glAmount", rs.getBigDecimal("gl_amount"));
                    row.put("status", rs.getString("status"));
                    return row;
                },
                txnId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /**
     * Demo helper: correct a billing transaction in place. If {@code newAmount} is null the billing
     * amount is aligned to the ledger (accept GL as truth); otherwise it is set to the given value.
     * After this, signalling the batch child with CONTINUE re-processes and clears the discrepancy.
     */
    public int correctTransactionAmount(String txnId, Double newAmount) {
        if (newAmount == null) {
            return jdbc.update(
                    "UPDATE billing_transactions b SET amount = g.amount "
                            + "FROM gl_entries g WHERE b.txn_id = g.txn_id AND b.txn_id = ?",
                    txnId);
        }
        return jdbc.update(
                "UPDATE billing_transactions SET amount = ? WHERE txn_id = ?",
                newAmount, txnId);
    }

    public ReconciliationResult findRun(String runId) {
        List<ReconciliationResult> rows = jdbc.query(
                """
                SELECT run_id, status, total_txns, valid_txns, invalid_txns, discrepancy_count, discrepancy_amount
                FROM reconciliation_runs WHERE run_id = ?
                """,
                (rs, i) -> {
                    ReconciliationResult result = new ReconciliationResult();
                    result.setRunId(rs.getString("run_id"));
                    result.setStatus(rs.getString("status"));
                    result.setTotalTxns(rs.getLong("total_txns"));
                    result.setValidTxns(rs.getLong("valid_txns"));
                    result.setInvalidTxns(rs.getLong("invalid_txns"));
                    result.setDiscrepancyCount(rs.getLong("discrepancy_count"));
                    result.setDiscrepancyAmount(rs.getDouble("discrepancy_amount"));
                    result.setCompensated("COMPLETED_WITH_COMPENSATION".equals(rs.getString("status")));
                    return result;
                },
                runId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private static BillingTransactionDto mapBillingTransaction(ResultSet rs) throws SQLException {
        return new BillingTransactionDto(
                rs.getLong("id"),
                rs.getString("txn_id"),
                rs.getString("vendor_id"),
                rs.getString("customer_id"),
                rs.getBigDecimal("amount"),
                rs.getString("currency"));
    }

    private static ProcessedTransactionDto mapProcessed(ResultSet rs) throws SQLException {
        Date due = rs.getDate("due_date");
        return new ProcessedTransactionDto(
                rs.getString("txn_id"),
                rs.getString("vendor_name"),
                rs.getString("customer_name"),
                rs.getString("currency"),
                rs.getBigDecimal("original_amount"),
                rs.getBigDecimal("adjustment"),
                rs.getBigDecimal("discount"),
                rs.getBigDecimal("penalty"),
                rs.getBigDecimal("final_amount"),
                rs.getInt("batch_no"),
                due == null ? null : due.toLocalDate());
    }

    private static String placeholders(int count) {
        return String.join(",", java.util.Collections.nCopies(count, "?"));
    }

    private static void forEachInChunk(List<String> ids, java.util.function.Consumer<List<String>> consumer) {
        final int chunkSize = 5000;
        for (int i = 0; i < ids.size(); i += chunkSize) {
            consumer.accept(ids.subList(i, Math.min(i + chunkSize, ids.size())));
        }
    }
}
