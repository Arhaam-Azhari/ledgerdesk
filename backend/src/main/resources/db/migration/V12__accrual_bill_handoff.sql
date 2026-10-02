CREATE TABLE accrual_bills (
    accrual_id VARCHAR(36) PRIMARY KEY REFERENCES expense_accruals(id),
    bill_id VARCHAR(36) NOT NULL UNIQUE REFERENCES bills(id),
    reversal_id VARCHAR(36) NOT NULL UNIQUE REFERENCES accrual_reversals(id)
);
