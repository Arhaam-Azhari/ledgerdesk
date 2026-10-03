CREATE TABLE accounting_period_closes (
    id VARCHAR(36) PRIMARY KEY,
    business_id BIGINT NOT NULL REFERENCES businesses(id),
    starts_on DATE NOT NULL,
    ends_on DATE NOT NULL,
    review_note VARCHAR(240) NOT NULL,
    snapshot TEXT NOT NULL,
    status VARCHAR(10) NOT NULL CHECK (status IN ('CLOSED', 'REOPENED')),
    version INTEGER NOT NULL DEFAULT 1,
    closed_by VARCHAR(100) NOT NULL,
    closed_at TIMESTAMP NOT NULL,
    reopened_by VARCHAR(100),
    reopened_at TIMESTAMP,
    reopen_reason VARCHAR(240),
    CHECK (starts_on <= ends_on)
);
CREATE INDEX accounting_period_closed_idx ON accounting_period_closes(business_id, status, ends_on);
