INSERT INTO accounts VALUES ('1300', 'Prepaid expenses', 'ASSET');
CREATE TABLE prepaid_plans (
    id VARCHAR(36) PRIMARY KEY,
    business_id BIGINT NOT NULL REFERENCES businesses(id),
    expense_id VARCHAR(36) NOT NULL UNIQUE REFERENCES expenses(id),
    funded_on DATE NOT NULL,
    starts_on DATE NOT NULL,
    months INTEGER NOT NULL CHECK (months BETWEEN 1 AND 60),
    memo VARCHAR(240) NOT NULL,
    account_code VARCHAR(4) NOT NULL REFERENCES accounts(code),
    amount NUMERIC(14,2) NOT NULL CHECK (amount > 0)
);
CREATE TABLE prepaid_periods (
    id VARCHAR(36) PRIMARY KEY,
    plan_id VARCHAR(36) NOT NULL REFERENCES prepaid_plans(id),
    period_on DATE NOT NULL,
    amount NUMERIC(14,2) NOT NULL CHECK (amount > 0),
    entry_id VARCHAR(36) UNIQUE REFERENCES journal_entries(id),
    UNIQUE(plan_id, period_on)
);
