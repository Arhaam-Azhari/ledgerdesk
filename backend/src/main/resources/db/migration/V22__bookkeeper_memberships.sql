-- Replace the unnamed role check without changing stored users or memberships.
CREATE TABLE business_memberships_expanded (
    user_id VARCHAR(36) NOT NULL REFERENCES app_users(id),
    business_id BIGINT NOT NULL REFERENCES businesses(id),
    role VARCHAR(12) NOT NULL CHECK (role IN ('OWNER', 'BOOKKEEPER', 'REVIEWER')),
    PRIMARY KEY (user_id, business_id)
);
INSERT INTO business_memberships_expanded SELECT user_id, business_id, role FROM business_memberships;
DROP TABLE business_memberships;
ALTER TABLE business_memberships_expanded RENAME TO business_memberships;
