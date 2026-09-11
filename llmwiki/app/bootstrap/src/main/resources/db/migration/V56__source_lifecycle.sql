-- V56: source lifecycle deprecation
ALTER TABLE source ADD COLUMN lifecycle_status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE' AFTER status;
ALTER TABLE source ADD COLUMN deprecated_at DATETIME NULL;
ALTER TABLE source ADD COLUMN deprecated_category VARCHAR(32) NULL;
ALTER TABLE source ADD COLUMN deprecated_reason VARCHAR(500) NULL;
ALTER TABLE source ADD COLUMN deprecated_by BIGINT NULL;

CREATE INDEX idx_source_lifecycle ON source (scope_id, lifecycle_status);
