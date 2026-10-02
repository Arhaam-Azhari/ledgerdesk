CREATE TABLE prepaid_cancellations (
    id VARCHAR(36) PRIMARY KEY,
    plan_id VARCHAR(36) NOT NULL UNIQUE REFERENCES prepaid_plans(id),
    cancelled_on DATE NOT NULL,
    reason VARCHAR(240) NOT NULL,
    amount NUMERIC(14,2) NOT NULL CHECK (amount > 0)
);
