CREATE TABLE opening_book_imports (
    id VARCHAR(36) PRIMARY KEY,
    business_id BIGINT NOT NULL UNIQUE REFERENCES businesses(id),
    as_of DATE NOT NULL,
    review_note VARCHAR(240) NOT NULL,
    snapshot TEXT NOT NULL,
    entry_id VARCHAR(36) UNIQUE REFERENCES journal_entries(id),
    bank_opening_id VARCHAR(36) NOT NULL UNIQUE REFERENCES opening_bank_balances(id),
    created_by VARCHAR(100) NOT NULL,
    created_at TIMESTAMP NOT NULL
);
CREATE TABLE opening_book_invoices (
    invoice_id VARCHAR(36) PRIMARY KEY REFERENCES invoices(id),
    opening_books_id VARCHAR(36) NOT NULL REFERENCES opening_book_imports(id),
    customer_id VARCHAR(36) NOT NULL REFERENCES customers(id),
    original_reference VARCHAR(80) NOT NULL,
    reference_key VARCHAR(80) NOT NULL,
    UNIQUE (opening_books_id, customer_id, reference_key)
);
CREATE TABLE opening_book_bills (
    bill_id VARCHAR(36) PRIMARY KEY REFERENCES bills(id),
    opening_books_id VARCHAR(36) NOT NULL REFERENCES opening_book_imports(id)
);
