CREATE TABLE year_end_closes (
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
    UNIQUE (business_id, calendar_year),
    CHECK (starts_on <= ends_on)
);
CREATE INDEX year_end_cutoff_idx ON year_end_closes(business_id, ends_on);
