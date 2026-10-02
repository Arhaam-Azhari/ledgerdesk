CREATE TABLE journal_adjustments (
    id VARCHAR(36) PRIMARY KEY,
    business_id BIGINT NOT NULL REFERENCES businesses(id),
    posted_on DATE NOT NULL,
    memo VARCHAR(300) NOT NULL
);
CREATE INDEX adjustments_business_date_idx ON journal_adjustments(business_id, posted_on);
