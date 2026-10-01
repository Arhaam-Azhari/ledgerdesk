INSERT INTO accounts VALUES
('2000', 'Accounts payable', 'LIABILITY'),
('5000', 'Office supplies', 'EXPENSE'),
('5100', 'Software subscriptions', 'EXPENSE'),
('5200', 'Professional services', 'EXPENSE'),
('5300', 'Rent and utilities', 'EXPENSE'),
('5400', 'Business travel', 'EXPENSE'),
('5500', 'Other operating expenses', 'EXPENSE');
CREATE TABLE vendors (
    id VARCHAR(36) PRIMARY KEY,
    business_id BIGINT NOT NULL REFERENCES businesses(id),
    name VARCHAR(120) NOT NULL,
    email VARCHAR(200) NOT NULL
);
CREATE TABLE bills (
    id VARCHAR(36) PRIMARY KEY,
    business_id BIGINT NOT NULL REFERENCES businesses(id),
    vendor_id VARCHAR(36) NOT NULL REFERENCES vendors(id),
    reference VARCHAR(80) NOT NULL,
    reference_key VARCHAR(80) NOT NULL,
    description VARCHAR(240) NOT NULL,
    issued_on DATE NOT NULL,
    due_on DATE NOT NULL CHECK (due_on >= issued_on),
    account_code VARCHAR(4) NOT NULL REFERENCES accounts(code),
    amount NUMERIC(14,2) NOT NULL CHECK (amount > 0),
    paid NUMERIC(14,2) NOT NULL DEFAULT 0 CHECK (paid >= 0 AND paid <= amount),
    status VARCHAR(12) NOT NULL DEFAULT 'POSTED' CHECK (status IN ('POSTED', 'VOID')),
    UNIQUE (business_id, vendor_id, reference_key)
);
CREATE TABLE bill_payments (
    id VARCHAR(36) PRIMARY KEY,
    bill_id VARCHAR(36) NOT NULL REFERENCES bills(id),
    paid_on DATE NOT NULL,
    amount NUMERIC(14,2) NOT NULL CHECK (amount > 0)
);
CREATE TABLE expenses (
    id VARCHAR(36) PRIMARY KEY,
    business_id BIGINT NOT NULL REFERENCES businesses(id),
    vendor_id VARCHAR(36) NOT NULL REFERENCES vendors(id),
    description VARCHAR(240) NOT NULL,
    spent_on DATE NOT NULL,
    account_code VARCHAR(4) NOT NULL REFERENCES accounts(code),
    amount NUMERIC(14,2) NOT NULL CHECK (amount > 0),
    status VARCHAR(12) NOT NULL DEFAULT 'POSTED' CHECK (status IN ('POSTED', 'VOID'))
);
CREATE TABLE receipts (
    id VARCHAR(36) PRIMARY KEY,
    bill_id VARCHAR(36) REFERENCES bills(id),
    expense_id VARCHAR(36) REFERENCES expenses(id),
    filename VARCHAR(120) NOT NULL,
    media_type VARCHAR(40) NOT NULL,
    content_sha VARCHAR(64) NOT NULL,
    size_bytes INTEGER NOT NULL CHECK (size_bytes > 0 AND size_bytes <= 2097152),
    content BYTEA NOT NULL,
    uploaded_at TIMESTAMP NOT NULL,
    CHECK ((bill_id IS NOT NULL AND expense_id IS NULL) OR (expense_id IS NOT NULL AND bill_id IS NULL)),
    UNIQUE (bill_id, content_sha),
    UNIQUE (expense_id, content_sha)
);
CREATE INDEX bills_vendor_idx ON bills(vendor_id);
CREATE INDEX bill_payments_bill_idx ON bill_payments(bill_id);
CREATE INDEX expenses_vendor_idx ON expenses(vendor_id);
CREATE INDEX receipts_expense_idx ON receipts(expense_id);
CREATE INDEX receipts_bill_idx ON receipts(bill_id);
