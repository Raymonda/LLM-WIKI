-- V19: wiki_page增加content_updated_at（只在内容实际写入时更新），lint_finding状态扩展与治理字段

-- 1. wiki_page.content_updated_at：只在页面内容实际写入时设置，不受healthStatus等元数据更新影响
ALTER TABLE wiki_page
    ADD COLUMN content_updated_at DATETIME AFTER last_checked_at;

-- 回填：对于已有数据，用updated_at作为最佳近似值
UPDATE wiki_page SET content_updated_at = updated_at WHERE content_updated_at IS NULL;

-- 索引：stale检测核心查询 s.updated_at > p.content_updated_at 需此索引加速
CREATE INDEX idx_wp_scope_content_updated ON wiki_page(scope_id, content_updated_at);

-- 2. lint_finding状态扩展注释：从3种扩展为6种
ALTER TABLE lint_finding
    MODIFY COLUMN status VARCHAR(16) NOT NULL DEFAULT 'open'
    COMMENT 'open/auto_resolved/awaiting_approval/resolved/rolled_back/dismissed';

-- 3. lint_finding新增治理字段
ALTER TABLE lint_finding
    ADD COLUMN handling_method VARCHAR(32) COMMENT 'auto_refresh/auto_resolve/manual_ingest/manual_edit/dismiss' AFTER extra;

ALTER TABLE lint_finding
    ADD COLUMN risk_score INT COMMENT '5维度风险评分(0-15): 可逆性/影响范围/Schema合规/信息损失/主观性' AFTER handling_method;

ALTER TABLE lint_finding
    ADD COLUMN auto_resolved_at DATETIME AFTER risk_score;

-- 索引：活动中心查询需要按scope_id+status+handling_method过滤
CREATE INDEX idx_lf_scope_status_handling ON lint_finding(scope_id, status, handling_method);