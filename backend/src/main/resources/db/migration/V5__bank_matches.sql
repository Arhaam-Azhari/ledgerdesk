CREATE TABLE bank_matches (
    id VARCHAR(36) PRIMARY KEY,
    transaction_id VARCHAR(36) NOT NULL UNIQUE REFERENCES bank_transactions(id),
    line_id VARCHAR(36) NOT NULL UNIQUE REFERENCES journal_lines(id),
    matched_at TIMESTAMP NOT NULL
);
CREATE TABLE bank_match_events (
    id VARCHAR(36) PRIMARY KEY,
    match_id VARCHAR(36) NOT NULL,
    transaction_id VARCHAR(36) NOT NULL REFERENCES bank_transactions(id),
    line_id VARCHAR(36) NOT NULL REFERENCES journal_lines(id),
    action VARCHAR(12) NOT NULL CHECK (action IN ('MATCHED', 'UNMATCHED')),
    occurred_at TIMESTAMP NOT NULL
);
CREATE INDEX bank_match_events_transaction_idx ON bank_match_events(transaction_id);
