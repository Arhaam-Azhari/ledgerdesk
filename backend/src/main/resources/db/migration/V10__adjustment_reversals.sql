CREATE TABLE adjustment_reversals (
    id VARCHAR(36) PRIMARY KEY,
    adjustment_id VARCHAR(36) NOT NULL UNIQUE REFERENCES journal_adjustments(id),
    reversed_on DATE NOT NULL,
    reason VARCHAR(240) NOT NULL
);
