CREATE TABLE app_users (
    id VARCHAR(36) PRIMARY KEY,
    username VARCHAR(100) NOT NULL UNIQUE,
    password_hash VARCHAR(100) NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT TRUE
);
CREATE TABLE business_memberships (
    user_id VARCHAR(36) NOT NULL REFERENCES app_users(id),
    business_id BIGINT NOT NULL REFERENCES businesses(id),
    role VARCHAR(12) NOT NULL CHECK (role IN ('OWNER', 'REVIEWER')),
    PRIMARY KEY (user_id, business_id)
);
