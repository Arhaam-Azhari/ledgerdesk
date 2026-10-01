CREATE TABLE bank_imports (
    id VARCHAR(36) PRIMARY KEY,
    business_id BIGINT NOT NULL REFERENCES businesses(id),
    account_code VARCHAR(4) NOT NULL REFERENCES accounts(code),
    label VARCHAR(120) NOT NULL,
    imported_at TIMESTAMP NOT NULL,
    added_rows INTEGER NOT NULL CHECK (added_rows >= 0),
    duplicate_rows INTEGER NOT NULL CHECK (duplicate_rows >= 0)
);
CREATE TABLE bank_transactions (
    id VARCHAR(36) PRIMARY KEY,
    business_id BIGINT NOT NULL REFERENCES businesses(id),
    account_code VARCHAR(4) NOT NULL REFERENCES accounts(code),
    import_id VARCHAR(36) NOT NULL REFERENCES bank_imports(id),
    external_id VARCHAR(120) NOT NULL,
    posted_on DATE NOT NULL,
    description VARCHAR(240) NOT NULL,
    amount NUMERIC(14,2) NOT NULL CHECK (amount <> 0),
    UNIQUE (business_id, account_code, external_id)
);
CREATE INDEX bank_transactions_import_idx ON bank_transactions(import_id);
