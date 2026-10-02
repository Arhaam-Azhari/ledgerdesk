CREATE TABLE equity_reversals (
    id VARCHAR(36) PRIMARY KEY,
    transfer_id VARCHAR(36) NOT NULL UNIQUE REFERENCES equity_transactions(id),
    reversed_on DATE NOT NULL,
    reason VARCHAR(240) NOT NULL
);
