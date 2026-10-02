INSERT INTO accounts VALUES ('2100', 'Accrued expenses', 'LIABILITY');
CREATE TABLE expense_accruals (
    id VARCHAR(36) PRIMARY KEY,
    business_id BIGINT NOT NULL REFERENCES businesses(id),
    posted_on DATE NOT NULL,
    memo VARCHAR(240) NOT NULL,
    account_code VARCHAR(4) NOT NULL REFERENCES accounts(code),
    amount NUMERIC(14,2) NOT NULL CHECK (amount > 0)
);
CREATE TABLE accrual_reversals (
    id VARCHAR(36) PRIMARY KEY,
    accrual_id VARCHAR(36) NOT NULL UNIQUE REFERENCES expense_accruals(id),
    reversed_on DATE NOT NULL,
    reason VARCHAR(240) NOT NULL
);
CREATE INDEX accruals_business_date_idx ON expense_accruals(business_id, posted_on);
