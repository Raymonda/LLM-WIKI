-- V20: lint_finding新增repair_execution_id追踪修复Ingest执行，status扩展repairing状态

-- 1. 新增repair_execution_id：记录触发修复的Ingest execution ID，便于追踪修复进度
ALTER TABLE lint_finding
    ADD COLUMN repair_execution_id BIGINT COMMENT '触发修复的Ingest执行ID，仅repairing状态时有值' AFTER auto_resolved_at;

-- 2. status扩展注释：从6种扩展为7种，新增repairing（修复Ingest已触发，进行中）
ALTER TABLE lint_finding
    MODIFY COLUMN status VARCHAR(16) NOT NULL DEFAULT 'open'
    COMMENT 'open/repairing/auto_resolved/awaiting_approval/resolved/rolled_back/dismissed';

-- 3. 索引：按scope_id+repairing状态查询进行中的修复
CREATE INDEX idx_lf_scope_repairing ON lint_finding(scope_id, status, repair_execution_id);