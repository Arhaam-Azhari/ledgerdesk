CREATE TABLE account_recoveries (
    id VARCHAR(36) PRIMARY KEY,
    user_id VARCHAR(36) NOT NULL REFERENCES app_users(id),
    business_id BIGINT NOT NULL REFERENCES businesses(id),
    recovered_at TIMESTAMP NOT NULL,
    reason VARCHAR(240) NOT NULL
);
