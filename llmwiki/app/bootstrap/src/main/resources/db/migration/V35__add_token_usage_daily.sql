-- V35: 新增 token_usage_daily 表用于日粒度 Token 消耗趋势统计
CREATE TABLE token_usage_daily (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    scope_id BIGINT NOT NULL,
    usage_date DATE NOT NULL,
    operation_type VARCHAR(16) NOT NULL COMMENT 'ingest/query/lint/schema/modify',
    input_tokens BIGINT NOT NULL DEFAULT 0,
    output_tokens BIGINT NOT NULL DEFAULT 0,
    call_count INT NOT NULL DEFAULT 0,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_scope_date_type (scope_id, usage_date, operation_type)
);
CREATE INDEX idx_tud_scope_date ON token_usage_daily(scope_id, usage_date);
