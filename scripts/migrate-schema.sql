-- Safe to run on an existing billing database.
ALTER TABLE billing_transactions ADD COLUMN IF NOT EXISTS currency VARCHAR(10);
ALTER TABLE billing_transactions ADD COLUMN IF NOT EXISTS due_date DATE;
UPDATE billing_transactions SET currency = 'USD' WHERE currency IS NULL;
UPDATE billing_transactions SET due_date = txn_date + 15 WHERE due_date IS NULL;

ALTER TABLE vendors ADD COLUMN IF NOT EXISTS status VARCHAR(20);
UPDATE vendors SET status = 'ACTIVE' WHERE status IS NULL;

ALTER TABLE customers ADD COLUMN IF NOT EXISTS status VARCHAR(20);
UPDATE customers SET status = 'ACTIVE' WHERE status IS NULL;

ALTER TABLE gl_entries ADD COLUMN IF NOT EXISTS account VARCHAR(20);
ALTER TABLE gl_entries ADD COLUMN IF NOT EXISTS gl_date DATE;
UPDATE gl_entries SET account = '4000' WHERE account IS NULL;
UPDATE gl_entries g
SET gl_date = t.txn_date
FROM billing_transactions t
WHERE g.txn_id = t.txn_id AND g.gl_date IS NULL;

ALTER TABLE vendor_cache ADD COLUMN IF NOT EXISTS status VARCHAR(20);
UPDATE vendor_cache SET status = 'ACTIVE' WHERE status IS NULL;

ALTER TABLE customer_cache ADD COLUMN IF NOT EXISTS status VARCHAR(20);
UPDATE customer_cache SET status = 'ACTIVE' WHERE status IS NULL;

ALTER TABLE processed_transactions ADD COLUMN IF NOT EXISTS currency VARCHAR(10);
ALTER TABLE processed_transactions ADD COLUMN IF NOT EXISTS discount NUMERIC(12, 2);
UPDATE processed_transactions SET currency = 'USD' WHERE currency IS NULL;
UPDATE processed_transactions SET discount = 0 WHERE discount IS NULL;

ALTER TABLE discrepancies ADD COLUMN IF NOT EXISTS reason VARCHAR(100);
UPDATE discrepancies SET reason = 'AMOUNT_MISMATCH' WHERE reason IS NULL;

ALTER TABLE reports ADD COLUMN IF NOT EXISTS report_type VARCHAR(20);
UPDATE reports SET report_type = 'CSV' WHERE report_type IS NULL;

ALTER TABLE notifications ADD COLUMN IF NOT EXISTS subject VARCHAR(200);
UPDATE notifications SET subject = 'Billing reconciliation' WHERE subject IS NULL;
