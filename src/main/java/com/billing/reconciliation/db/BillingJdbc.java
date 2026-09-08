package com.billing.reconciliation.db;

import com.billing.reconciliation.model.BatchRef;
import com.billing.reconciliation.model.CustomerDto;
import com.billing.reconciliation.model.DiscrepancySummary;
import com.billing.reconciliation.model.ReconciliationResult;
import com.billing.reconciliation.model.VendorDto;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.IntConsumer;

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

    public long countByStatus(long fromId, long toId, String status) {
        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM billing_transactions WHERE id BETWEEN ? AND ? AND status = ?",
                Long.class, fromId, toId, status);
        return count == null ? 0 : count;
    }

    public long markInvalid(String runId, long fromId, long toId) {
        jdbc.update(
                """
                DELETE FROM validation_errors
                WHERE run_id = ?
                  AND txn_id IN (SELECT txn_id FROM billing_transactions WHERE id BETWEEN ? AND ?)
                """,
                runId, fromId, toId);
        jdbc.update(
                """
                INSERT INTO validation_errors (run_id, txn_id, reason)
                SELECT ?, txn_id, 'amount must be greater than 0'
                FROM billing_transactions
                WHERE id BETWEEN ? AND ? AND amount <= 0
                """,
                runId, fromId, toId);
        jdbc.update(
                """
                INSERT INTO validation_errors (run_id, txn_id, reason)
                SELECT ?, txn_id, 'txn_id is blank'
                FROM billing_transactions
                WHERE id BETWEEN ? AND ? AND (txn_id IS NULL OR txn_id = '')
                """,
                runId, fromId, toId);
        jdbc.update(
                """
                INSERT INTO validation_errors (run_id, txn_id, reason)
                SELECT ?, txn_id, 'currency is blank'
                FROM billing_transactions
                WHERE id BETWEEN ? AND ? AND (currency IS NULL OR currency = '')
                """,
                runId, fromId, toId);
        return jdbc.update(
                """
                UPDATE billing_transactions
                SET status = 'INVALID'
                WHERE id BETWEEN ? AND ?
                  AND status = 'NEW'
                  AND (amount <= 0 OR txn_id IS NULL OR txn_id = '' OR vendor_id IS NULL OR vendor_id = ''
                       OR customer_id IS NULL OR customer_id = '' OR currency IS NULL OR currency = '')
                """,
                fromId, toId);
    }

    public long markValid(long fromId, long toId) {
        return jdbc.update(
                """
                UPDATE billing_transactions
                SET status = 'VALID'
                WHERE id BETWEEN ? AND ? AND status = 'NEW' AND amount > 0
                  AND txn_id IS NOT NULL AND txn_id <> ''
                  AND vendor_id IS NOT NULL AND vendor_id <> ''
                  AND customer_id IS NOT NULL AND customer_id <> ''
                  AND currency IS NOT NULL AND currency <> ''
                """,
                fromId, toId);
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

    public long enrichBatch(String runId, BatchRef batch) {
        return jdbc.update(
                """
                INSERT INTO processed_transactions
                    (run_id, txn_id, vendor_name, customer_name, currency, original_amount, adjustment, discount, penalty, final_amount, batch_no)
                SELECT ?, t.txn_id, v.vendor_name, c.customer_name, t.currency, t.amount, 0, 0, 0, t.amount, ?
                FROM billing_transactions t
                JOIN vendor_cache v ON v.run_id = ? AND v.vendor_id = t.vendor_id
                JOIN customer_cache c ON c.run_id = ? AND c.customer_id = t.customer_id
                WHERE t.id BETWEEN ? AND ? AND t.status = 'VALID'
                ON CONFLICT (run_id, txn_id) DO UPDATE SET
                    vendor_name = EXCLUDED.vendor_name,
                    customer_name = EXCLUDED.customer_name,
                    currency = EXCLUDED.currency,
                    original_amount = EXCLUDED.original_amount,
                    adjustment = 0,
                    discount = 0,
                    penalty = 0,
                    final_amount = EXCLUDED.original_amount,
                    batch_no = EXCLUDED.batch_no
                """,
                runId, batch.getBatchNo(), runId, runId, batch.getFromId(), batch.getToId());
    }

    public long applySurcharge(String runId, BatchRef batch, double threshold, double rate,
                               double midTierThreshold, double midTierFlatFee) {
        return jdbc.update(
                """
                UPDATE processed_transactions
                SET final_amount = CASE
                    WHEN original_amount > CAST(? AS numeric)
                        THEN ROUND(original_amount * (1 + CAST(? AS numeric)), 2)
                    WHEN original_amount > CAST(? AS numeric)
                        THEN ROUND(original_amount + CAST(? AS numeric), 2)
                    ELSE original_amount
                END
                WHERE run_id = ? AND batch_no = ?
                """,
                threshold, rate, midTierThreshold, midTierFlatFee, runId, batch.getBatchNo());
    }

    public long applyDiscount(String runId, BatchRef batch, double threshold, double rate) {
        return jdbc.update(
                """
                UPDATE processed_transactions
                SET discount = ROUND(original_amount * CAST(? AS numeric), 2),
                    adjustment = ROUND(original_amount * CAST(? AS numeric), 2) * -1,
                    final_amount = ROUND(final_amount - (original_amount * CAST(? AS numeric)), 2)
                WHERE run_id = ? AND batch_no = ? AND original_amount > CAST(? AS numeric)
                """,
                rate, rate, rate, runId, batch.getBatchNo(), threshold);
    }

    public long applyLateFees(String runId, BatchRef batch, int lateDays, double rate) {
        Date cutoff = Date.valueOf(LocalDate.now().minusDays(lateDays));
        return jdbc.update(
                """
                UPDATE processed_transactions p
                SET penalty = ROUND(p.original_amount * CAST(? AS numeric), 2),
                    final_amount = ROUND(p.final_amount + (p.original_amount * CAST(? AS numeric)), 2)
                FROM billing_transactions t
                WHERE p.run_id = ? AND p.batch_no = ? AND p.txn_id = t.txn_id AND t.due_date < ?
                """,
                rate, rate, runId, batch.getBatchNo(), cutoff);
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

    public long matchTransactions(String runId, BatchRef batch, double tolerance, IntConsumer onProgress) {
        jdbc.update(
                """
                DELETE FROM discrepancies d
                USING processed_transactions p
                WHERE d.run_id = ? AND p.run_id = ? AND p.batch_no = ? AND d.txn_id = p.txn_id
                """,
                runId, runId, batch.getBatchNo());

        List<AmountRow> billing = jdbc.query(
                """
                SELECT txn_id, original_amount
                FROM processed_transactions
                WHERE run_id = ? AND batch_no = ?
                """,
                (rs, i) -> new AmountRow(rs.getString("txn_id"), rs.getDouble("original_amount")),
                runId, batch.getBatchNo());
        List<AmountRow> glRows = jdbc.query(
                """
                SELECT g.txn_id, g.amount
                FROM gl_entries g
                JOIN billing_transactions t ON t.txn_id = g.txn_id
                WHERE t.id BETWEEN ? AND ?
                """,
                (rs, i) -> new AmountRow(rs.getString("txn_id"), rs.getDouble("amount")),
                batch.getFromId(), batch.getToId());

        Map<String, Double> glByTxn = new HashMap<>();
        for (AmountRow row : glRows) {
            glByTxn.put(row.txnId(), row.amount());
        }

        List<Object[]> inserts = new ArrayList<>();
        int scanned = 0;
        for (AmountRow billed : billing) {
            scanned++;
            if (scanned % 10000 == 0 && onProgress != null) {
                onProgress.accept(scanned);
            }
            Double glAmount = glByTxn.get(billed.txnId());
            if (glAmount == null) {
                inserts.add(new Object[]{runId, billed.txnId(), billed.amount(), 0.0, billed.amount(), "MISSING_GL", "N"});
                continue;
            }
            double difference = Math.round((billed.amount() - glAmount) * 100.0) / 100.0;
            if (Math.abs(difference) > tolerance) {
                inserts.add(new Object[]{runId, billed.txnId(), billed.amount(), glAmount, difference, "AMOUNT_MISMATCH", "N"});
            }
        }
        if (!inserts.isEmpty()) {
            jdbc.batchUpdate(
                    """
                    INSERT INTO discrepancies (run_id, txn_id, billing_amount, gl_amount, difference, reason, compensated)
                    VALUES (?, ?, ?, ?, ?, ?, ?)
                    """,
                    inserts);
        }
        return inserts.size();
    }

    private record AmountRow(String txnId, double amount) {
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

    /**
     * Saga compensation for a GL mismatch: the ledger is the system of record, so the billing source
     * amount is corrected to the GL amount for the flagged ids. Because the batch child re-runs the
     * full pipeline (enrich re-reads {@code billing_transactions}) after this activity via
     * Continue-As-New, the subsequent match finds no discrepancy. This is the demo's correction rule;
     * Replace the body with your own reconciliation policy for production.
     */
    public long compensateDiscrepancies(String runId, List<String> txnIds) {
        if (txnIds == null || txnIds.isEmpty()) {
            return 0;
        }
        String placeholders = String.join(",", txnIds.stream().map(id -> "?").toList());
        Object[] args = new Object[txnIds.size() + 1];
        args[0] = runId;
        for (int i = 0; i < txnIds.size(); i++) {
            args[i + 1] = txnIds.get(i);
        }
        // Align the billing source to the ledger for the flagged ids (accept GL as truth).
        int corrected = jdbc.update(
                "UPDATE billing_transactions b "
                        + "SET amount = g.amount "
                        + "FROM gl_entries g "
                        + "WHERE b.txn_id = g.txn_id AND b.txn_id IN (" + placeholders + ")",
                java.util.Arrays.copyOfRange(args, 1, args.length));
        jdbc.update(
                "UPDATE processed_transactions SET adjustment = 0, discount = 0, penalty = 0, final_amount = original_amount "
                        + "WHERE run_id = ? AND txn_id IN (" + placeholders + ")",
                args);
        jdbc.update(
                "UPDATE discrepancies SET compensated = 'Y' WHERE run_id = ? AND txn_id IN (" + placeholders + ")",
                args);
        return corrected;
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
}
