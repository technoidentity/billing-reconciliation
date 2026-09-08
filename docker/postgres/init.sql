-- Runs only on first Postgres volume create (docker-entrypoint-initdb.d).
-- App user/db. Connect to database "billing" as billing/billing (not the default "postgres" db).
CREATE USER billing WITH PASSWORD 'billing';
CREATE DATABASE billing OWNER billing;

\connect billing

GRANT USAGE, CREATE ON SCHEMA public TO billing;

-- Source tables (loaded by scripts/insert-dummy-data.sh)

-- Daily warehouse: ~1.2M billing transactions (demo scenario step 2).
CREATE TABLE billing_transactions (
    id              BIGSERIAL PRIMARY KEY,
    txn_id          VARCHAR(50) NOT NULL,
    vendor_id       VARCHAR(20) NOT NULL,
    customer_id     VARCHAR(20) NOT NULL,
    amount          NUMERIC(12, 2) NOT NULL,
    currency        VARCHAR(10) NOT NULL,
    txn_date        DATE NOT NULL,
    due_date        DATE NOT NULL,
    status          VARCHAR(20) NOT NULL
);

-- Vendor master used by enrichment API (demo scenario step 5).
CREATE TABLE vendors (
    vendor_id       VARCHAR(20) PRIMARY KEY,
    vendor_name     VARCHAR(100) NOT NULL,
    status          VARCHAR(20) NOT NULL
);

-- Customer master used by enrichment API (demo scenario step 6).
CREATE TABLE customers (
    customer_id     VARCHAR(20) PRIMARY KEY,
    customer_name   VARCHAR(100) NOT NULL,
    status          VARCHAR(20) NOT NULL
);

-- General ledger used for match (demo scenario steps 11-12).
CREATE TABLE gl_entries (
    id              BIGSERIAL PRIMARY KEY,
    txn_id          VARCHAR(50) NOT NULL,
    account         VARCHAR(20) NOT NULL,
    amount          NUMERIC(12, 2) NOT NULL,
    gl_date         DATE NOT NULL
);

-- Per-run tables (written by the Temporal workflow)

CREATE TABLE reconciliation_runs (
    run_id              VARCHAR(50) PRIMARY KEY,
    workflow_id         VARCHAR(120),
    status              VARCHAR(30) NOT NULL,
    started_at          TIMESTAMP NOT NULL,
    completed_at        TIMESTAMP,
    total_txns          BIGINT DEFAULT 0,
    valid_txns          BIGINT DEFAULT 0,
    invalid_txns        BIGINT DEFAULT 0,
    discrepancy_count   BIGINT DEFAULT 0,
    discrepancy_amount  NUMERIC(14, 2) DEFAULT 0
);

-- Per-run cache of vendor HTTP responses (fallback after retries).
CREATE TABLE vendor_cache (
    run_id          VARCHAR(50) NOT NULL,
    vendor_id       VARCHAR(20) NOT NULL,
    vendor_name     VARCHAR(100) NOT NULL,
    status          VARCHAR(20) NOT NULL,
    PRIMARY KEY (run_id, vendor_id)
);

-- Per-run cache of customer HTTP responses (fallback after retries).
CREATE TABLE customer_cache (
    run_id          VARCHAR(50) NOT NULL,
    customer_id     VARCHAR(20) NOT NULL,
    customer_name   VARCHAR(100) NOT NULL,
    status          VARCHAR(20) NOT NULL,
    PRIMARY KEY (run_id, customer_id)
);

-- Enriched + billed rows (demo scenario steps 7-10).
CREATE TABLE processed_transactions (
    id                  BIGSERIAL PRIMARY KEY,
    run_id              VARCHAR(50) NOT NULL,
    txn_id              VARCHAR(50) NOT NULL,
    vendor_name         VARCHAR(100),
    customer_name       VARCHAR(100),
    currency            VARCHAR(10) NOT NULL,
    original_amount     NUMERIC(12, 2) NOT NULL,
    adjustment          NUMERIC(12, 2) NOT NULL DEFAULT 0,
    discount            NUMERIC(12, 2) NOT NULL DEFAULT 0,
    penalty             NUMERIC(12, 2) NOT NULL DEFAULT 0,
    final_amount        NUMERIC(12, 2) NOT NULL,
    batch_no            INT NOT NULL,
    UNIQUE (run_id, txn_id)
);

CREATE TABLE validation_errors (
    id              BIGSERIAL PRIMARY KEY,
    run_id          VARCHAR(50) NOT NULL,
    txn_id          VARCHAR(50),
    reason          VARCHAR(200) NOT NULL
);

-- Billing vs GL mismatches. Child waits for COMPENSATE / CONTINUE before continuing.
CREATE TABLE discrepancies (
    id              BIGSERIAL PRIMARY KEY,
    run_id          VARCHAR(50) NOT NULL,
    txn_id          VARCHAR(50) NOT NULL,
    billing_amount  NUMERIC(12, 2) NOT NULL,
    gl_amount       NUMERIC(12, 2) NOT NULL,
    difference      NUMERIC(12, 2) NOT NULL,
    reason          VARCHAR(100) NOT NULL,
    compensated     VARCHAR(10) NOT NULL DEFAULT 'N'
);

-- Compliance reports stored in DB (demo scenario step 14: CSV / Excel-named CSV).
CREATE TABLE reports (
    id              BIGSERIAL PRIMARY KEY,
    run_id          VARCHAR(50) NOT NULL,
    report_name     VARCHAR(100) NOT NULL,
    report_type     VARCHAR(20) NOT NULL,
    content         TEXT NOT NULL
);

-- Email / Teams rows stored in DB (demo scenario step 15). No SMTP or webhook.
CREATE TABLE notifications (
    id              BIGSERIAL PRIMARY KEY,
    run_id          VARCHAR(50) NOT NULL,
    channel         VARCHAR(20) NOT NULL,
    recipient       VARCHAR(100) NOT NULL,
    subject         VARCHAR(200) NOT NULL,
    message         TEXT NOT NULL,
    sent_at         TIMESTAMP NOT NULL
);

-- Audit trail (demo scenario step 16). Temporal history is the other source of truth.
CREATE TABLE audit_log (
    id              BIGSERIAL PRIMARY KEY,
    run_id          VARCHAR(50) NOT NULL,
    step_name       VARCHAR(50) NOT NULL,
    message         VARCHAR(500) NOT NULL,
    created_at      TIMESTAMP NOT NULL
);

CREATE INDEX idx_billing_txn_id ON billing_transactions (txn_id);
CREATE INDEX idx_billing_status ON billing_transactions (status);
CREATE INDEX idx_gl_txn_id ON gl_entries (txn_id);
CREATE INDEX idx_processed_run ON processed_transactions (run_id);
CREATE INDEX idx_discrepancy_run ON discrepancies (run_id);
CREATE INDEX idx_audit_run ON audit_log (run_id);

GRANT ALL PRIVILEGES ON ALL TABLES IN SCHEMA public TO billing;
GRANT ALL PRIVILEGES ON ALL SEQUENCES IN SCHEMA public TO billing;
ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT ALL ON TABLES TO billing;
ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT ALL ON SEQUENCES TO billing;
