ALTER TABLE businesses ADD COLUMN settings_version BIGINT NOT NULL DEFAULT 0 CHECK (settings_version >= 0);
