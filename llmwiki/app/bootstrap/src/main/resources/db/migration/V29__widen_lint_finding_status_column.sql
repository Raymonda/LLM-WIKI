-- V29: 扩展 lint_finding.status 列宽度至 VARCHAR(32)，解决 awaiting_approval(16字符) 在 VARCHAR(16) 下的截断问题
-- 与 V6 扩展 execution.status 至 VARCHAR(32) 保持一致

ALTER TABLE lint_finding
    MODIFY COLUMN status VARCHAR(32) NOT NULL DEFAULT 'open'
    COMMENT 'open/repairing/auto_resolved/awaiting_approval/resolved/rolled_back/dismissed';