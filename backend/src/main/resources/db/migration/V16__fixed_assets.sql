INSERT INTO accounts VALUES ('1500', 'Equipment at cost', 'ASSET'), ('1590', 'Accumulated depreciation', 'ASSET'), ('5600', 'Depreciation expense', 'EXPENSE');
CREATE TABLE fixed_assets (
    id VARCHAR(36) PRIMARY KEY,
    business_id BIGINT NOT NULL REFERENCES businesses(id),
    expense_id VARCHAR(36) NOT NULL UNIQUE REFERENCES expenses(id),
    funded_on DATE NOT NULL,
    in_service_on DATE NOT NULL,
    months INTEGER NOT NULL CHECK (months BETWEEN 1 AND 600),
    name VARCHAR(240) NOT NULL,
    account_code VARCHAR(4) NOT NULL REFERENCES accounts(code),
    cost NUMERIC(14,2) NOT NULL CHECK (cost > 0),
    residual_value NUMERIC(14,2) NOT NULL CHECK (residual_value >= 0 AND residual_value < cost)
);
CREATE TABLE asset_periods (
    id VARCHAR(36) PRIMARY KEY,
    asset_id VARCHAR(36) NOT NULL REFERENCES fixed_assets(id),
    period_on DATE NOT NULL,
    amount NUMERIC(14,2) NOT NULL CHECK (amount > 0),
    entry_id VARCHAR(36) UNIQUE REFERENCES journal_entries(id),
    UNIQUE(asset_id, period_on)
);
