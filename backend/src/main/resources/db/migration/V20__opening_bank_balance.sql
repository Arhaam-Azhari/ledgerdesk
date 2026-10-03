INSERT INTO accounts VALUES ('3200', 'Opening balance equity', 'EQUITY');
CREATE TABLE opening_bank_balances (
    id VARCHAR(36) PRIMARY KEY,
    business_id BIGINT NOT NULL UNIQUE REFERENCES businesses(id),
    as_of DATE NOT NULL,
    balance NUMERIC(14,2) NOT NULL CHECK (balance >= 0),
    memo VARCHAR(240) NOT NULL
);
