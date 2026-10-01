CREATE TABLE bank_reconciliations (
    id VARCHAR(36) PRIMARY KEY,
    business_id BIGINT NOT NULL REFERENCES businesses(id),
    account_code VARCHAR(10) NOT NULL REFERENCES accounts(code),
    starts_on DATE NOT NULL,
    ends_on DATE NOT NULL,
    opening_balance NUMERIC(14,2) NOT NULL,
    closing_balance NUMERIC(14,2) NOT NULL,
    snapshot TEXT NOT NULL,
    status VARCHAR(10) NOT NULL CHECK (status IN ('CLOSED', 'REOPENED')),
    version BIGINT NOT NULL DEFAULT 1,
    closed_at TIMESTAMP NOT NULL,
    closed_by VARCHAR(100) NOT NULL,
    reopened_at TIMESTAMP,
    reopened_by VARCHAR(100),
    reopen_reason VARCHAR(240),
    CHECK (starts_on <= ends_on)
);
CREATE INDEX bank_reconciliations_period_idx ON bank_reconciliations(business_id, ends_on);
