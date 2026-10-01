CREATE TABLE invoice_numbers (
    invoice_id VARCHAR(36) PRIMARY KEY REFERENCES invoices(id),
    number_value BIGINT NOT NULL UNIQUE CHECK (number_value > 0)
);
-- Existing posted invoices keep their accounting entries and receive stable numbers.
INSERT INTO invoice_numbers (invoice_id, number_value)
SELECT id, ROW_NUMBER() OVER (ORDER BY issued_on, id) FROM invoices;
ALTER TABLE businesses ADD COLUMN next_invoice_number BIGINT NOT NULL DEFAULT 1;
UPDATE businesses SET next_invoice_number = (SELECT COALESCE(MAX(number_value), 0) + 1 FROM invoice_numbers);

-- Drafts have no journal entries. Retain the saved copy after posting or discarding.
CREATE TABLE invoice_drafts (
    id VARCHAR(36) PRIMARY KEY,
    business_id BIGINT NOT NULL REFERENCES businesses(id),
    customer_id VARCHAR(36) NOT NULL REFERENCES customers(id),
    description VARCHAR(240) NOT NULL,
    issued_on DATE NOT NULL,
    due_on DATE NOT NULL CHECK (due_on >= issued_on),
    amount NUMERIC(14,2) NOT NULL CHECK (amount > 0),
    version BIGINT NOT NULL DEFAULT 0 CHECK (version >= 0),
    posted_invoice_id VARCHAR(36) UNIQUE REFERENCES invoices(id),
    cancelled BOOLEAN NOT NULL DEFAULT FALSE,
    CHECK (NOT (cancelled AND posted_invoice_id IS NOT NULL))
);
CREATE INDEX invoice_drafts_customer_idx ON invoice_drafts(customer_id);
