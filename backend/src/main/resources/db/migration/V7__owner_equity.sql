INSERT INTO accounts VALUES ('3000', 'Owner contributions', 'EQUITY'), ('3100', 'Owner drawings', 'EQUITY');
CREATE TABLE equity_transactions (
    id VARCHAR(36) PRIMARY KEY,
    business_id BIGINT NOT NULL REFERENCES businesses(id),
    kind VARCHAR(12) NOT NULL CHECK (kind IN ('CONTRIBUTION', 'DRAWING')),
    posted_on DATE NOT NULL,
    memo VARCHAR(240) NOT NULL,
    amount NUMERIC(14,2) NOT NULL CHECK (amount > 0)
);
CREATE INDEX equity_business_date_idx ON equity_transactions(business_id, posted_on);
