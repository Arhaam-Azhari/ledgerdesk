-- Replace the one-record-per-year constraint with one active close per year.
-- A reopened review remains intact when a replacement close is created.
CREATE TABLE year_end_closes_rebuilt (
    id VARCHAR(36) PRIMARY KEY,
    business_id BIGINT NOT NULL REFERENCES businesses(id),
    calendar_year INTEGER NOT NULL CHECK (calendar_year BETWEEN 1 AND 9999),
    starts_on DATE NOT NULL,
    ends_on DATE NOT NULL,
    entry_id VARCHAR(36) UNIQUE REFERENCES journal_entries(id),
    accounting_period_id VARCHAR(36) NOT NULL REFERENCES accounting_period_closes(id),
    bank_reconciliation_id VARCHAR(36) NOT NULL REFERENCES bank_reconciliations(id),
    review_note VARCHAR(240) NOT NULL,
    snapshot TEXT NOT NULL,
    closed_by VARCHAR(100) NOT NULL,
    closed_at TIMESTAMP NOT NULL,
    status VARCHAR(10) NOT NULL DEFAULT 'CLOSED' CHECK (status IN ('CLOSED', 'REOPENED')),
    version INTEGER NOT NULL DEFAULT 1 CHECK (version > 0),
    active_year INTEGER,
    reversal_entry_id VARCHAR(36) UNIQUE REFERENCES journal_entries(id),
    reopened_by VARCHAR(100),
    reopened_at TIMESTAMP,
    reopen_reason VARCHAR(240),
    UNIQUE (business_id, active_year),
    CHECK (starts_on <= ends_on),
    CHECK ((status = 'CLOSED' AND active_year IS NOT NULL AND active_year = calendar_year)
        OR (status = 'REOPENED' AND active_year IS NULL))
);
INSERT INTO year_end_closes_rebuilt (id, business_id, calendar_year, starts_on, ends_on, entry_id,
    accounting_period_id, bank_reconciliation_id, review_note, snapshot, closed_by, closed_at, active_year)
SELECT id, business_id, calendar_year, starts_on, ends_on, entry_id,
    accounting_period_id, bank_reconciliation_id, review_note, snapshot, closed_by, closed_at, calendar_year
FROM year_end_closes;
DROP TABLE year_end_closes;
ALTER TABLE year_end_closes_rebuilt RENAME TO year_end_closes;
CREATE INDEX year_end_cutoff_idx ON year_end_closes(business_id, status, ends_on);
