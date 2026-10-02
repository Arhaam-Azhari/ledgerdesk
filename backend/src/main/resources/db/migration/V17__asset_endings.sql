INSERT INTO accounts VALUES ('5700', 'Loss on equipment retirement', 'EXPENSE');
CREATE TABLE asset_corrections (
    id VARCHAR(36) PRIMARY KEY,
    asset_id VARCHAR(36) NOT NULL UNIQUE REFERENCES fixed_assets(id),
    corrected_on DATE NOT NULL,
    reason VARCHAR(240) NOT NULL
);
CREATE TABLE asset_retirements (
    id VARCHAR(36) PRIMARY KEY,
    asset_id VARCHAR(36) NOT NULL UNIQUE REFERENCES fixed_assets(id),
    retired_on DATE NOT NULL,
    reason VARCHAR(240) NOT NULL,
    cost NUMERIC(14,2) NOT NULL CHECK (cost > 0),
    accumulated NUMERIC(14,2) NOT NULL CHECK (accumulated >= 0 AND accumulated <= cost),
    book_value NUMERIC(14,2) NOT NULL CHECK (book_value = cost - accumulated)
);
