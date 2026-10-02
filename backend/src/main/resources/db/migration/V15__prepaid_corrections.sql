CREATE TABLE prepaid_corrections (
    id VARCHAR(36) PRIMARY KEY,
    plan_id VARCHAR(36) NOT NULL UNIQUE REFERENCES prepaid_plans(id),
    corrected_on DATE NOT NULL,
    reason VARCHAR(240) NOT NULL
);
