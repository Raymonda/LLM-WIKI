CREATE TABLE step_baseline (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    scope_id BIGINT NOT NULL,
    step_name VARCHAR(64) NOT NULL,
    doc_format VARCHAR(32) NOT NULL DEFAULT '',
    avg_ms BIGINT NOT NULL DEFAULT 0,
    p95_ms BIGINT NOT NULL DEFAULT 0,
    sample_count INT NOT NULL DEFAULT 0,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE UNIQUE INDEX uk_step_baseline_scope_step_format ON step_baseline(scope_id, step_name, doc_format);
