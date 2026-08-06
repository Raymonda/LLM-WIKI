-- V18: 创建 lint_finding 诊断结果持久化表，并为 source 表补充 updated_at 列

-- 1. 创建 lint_finding 表
CREATE TABLE lint_finding (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    scope_id BIGINT NOT NULL,
    page_path VARCHAR(512),
    asset_id BIGINT,
    finding_type VARCHAR(32) NOT NULL COMMENT 'orphan/stale/missing_crossref/conflict/gap/web_gap/action',
    priority VARCHAR(8) NOT NULL DEFAULT 'medium' COMMENT 'high/medium/low',
    title VARCHAR(256) NOT NULL,
    detail LONGTEXT,
    extra JSON COMMENT '异构元数据，如关联页面ID、来源时间戳等',
    status VARCHAR(16) NOT NULL DEFAULT 'open' COMMENT 'open/dismissed/resolved',
    execution_id BIGINT NOT NULL COMMENT '关联的 execution 记录',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_lf_scope_id (scope_id),
    INDEX idx_lf_scope_type_status (scope_id, finding_type, status),
    INDEX idx_lf_execution_id (execution_id),
    INDEX idx_lf_asset_id (asset_id),
    INDEX idx_lf_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 2. 为 source 表补充 updated_at 列（CHECK_STALE 依赖此字段比较来源更新时间）
ALTER TABLE source
    ADD COLUMN updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP AFTER created_at;