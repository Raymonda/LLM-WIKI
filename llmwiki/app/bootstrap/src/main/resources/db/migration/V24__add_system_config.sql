CREATE TABLE IF NOT EXISTS system_config (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    config_key VARCHAR(128) NOT NULL,
    config_value TEXT,
    scope_id BIGINT,
    updated_by BIGINT,
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_scope_key (scope_id, config_key)
);