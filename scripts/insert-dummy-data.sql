SET synchronous_commit = off;
SET client_min_messages = warning;

TRUNCATE TABLE
    billing_transactions,
    gl_entries,
    vendors,
    customers
RESTART IDENTITY;

INSERT INTO vendors (vendor_id, vendor_name, status)
SELECT
    'V' || lpad(gs::text, 4, '0'),
    'Vendor ' || gs,
    CASE WHEN gs % 20 = 0 THEN 'INACTIVE' ELSE 'ACTIVE' END
FROM generate_series(1, 100) AS gs;

INSERT INTO customers (customer_id, customer_name, status)
SELECT
    'C' || lpad(gs::text, 6, '0'),
    'Customer ' || gs,
    CASE WHEN gs % 50 = 0 THEN 'INACTIVE' ELSE 'ACTIVE' END
FROM generate_series(1, 1000) AS gs;

INSERT INTO billing_transactions (txn_id, vendor_id, customer_id, amount, currency, txn_date, due_date, status)
SELECT
    'TXN' || lpad(gs::text, 10, '0'),
    'V' || lpad(((gs % 100) + 1)::text, 4, '0'),
    'C' || lpad(((gs % 1000) + 1)::text, 6, '0'),
    CASE WHEN gs % 5000 = 0 THEN 0 ELSE round((10 + (gs % 4900) * 0.31)::numeric, 2) END,
    'USD',
    CURRENT_DATE - ((gs % 60)::int),
    (CURRENT_DATE - ((gs % 60)::int)) + 15,
    'NEW'
FROM generate_series(1, :rows) AS gs;

INSERT INTO gl_entries (txn_id, account, amount, gl_date)
SELECT
    txn_id,
    '4000',
    CASE WHEN id % 10000 = 1 THEN amount + 417 ELSE amount END,
    txn_date
FROM billing_transactions;

ANALYZE vendors;
ANALYZE customers;
ANALYZE billing_transactions;
ANALYZE gl_entries;

SELECT 'vendors' AS table_name, COUNT(*) AS row_count FROM vendors
UNION ALL
SELECT 'customers', COUNT(*) FROM customers
UNION ALL
SELECT 'billing_transactions', COUNT(*) FROM billing_transactions
UNION ALL
SELECT 'gl_entries', COUNT(*) FROM gl_entries
UNION ALL
SELECT 'invalid_amount_zero', COUNT(*) FROM billing_transactions WHERE amount = 0
UNION ALL
SELECT 'gl_mismatches', COUNT(*) FROM billing_transactions t
JOIN gl_entries g ON g.txn_id = t.txn_id
WHERE t.amount <> g.amount AND t.amount > 0;
