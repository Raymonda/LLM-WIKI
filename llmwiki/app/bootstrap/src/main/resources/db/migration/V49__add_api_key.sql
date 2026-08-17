CREATE TABLE api_key (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    key_hash VARCHAR(64) NOT NULL COMMENT 'SHA-256 hex of raw key',
    name VARCHAR(100) NOT NULL COMMENT 'purpose label, e.g. dsh-agent-laptop',
    user_id BIGINT NOT NULL,
    scope_id BIGINT NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'active' COMMENT 'active | revoked',
    expires_at DATETIME NULL,
    last_used_at DATETIME NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_api_key_hash (key_hash),
    KEY idx_api_key_user (user_id),
    KEY idx_api_key_scope (scope_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Machine API keys for agent access';
