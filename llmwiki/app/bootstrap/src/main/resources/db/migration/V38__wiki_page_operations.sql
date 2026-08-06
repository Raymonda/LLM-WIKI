-- V38: Wiki 页面操作重构 — 增加过期标记、软删除、合并追踪字段

ALTER TABLE wiki_page
    ADD COLUMN deprecated_at DATETIME COMMENT '用户标记过期时间' AFTER visibility,
    ADD COLUMN deprecated_reason VARCHAR(500) COMMENT '过期原因' AFTER deprecated_at,
    ADD COLUMN deleted_at DATETIME COMMENT '软删除时间' AFTER deprecated_reason,
    ADD COLUMN merged_into_page_id BIGINT COMMENT '合并目标页面ID（被合并的旧页面指向新页面）' AFTER deleted_at;

CREATE INDEX idx_wiki_page_deleted_at ON wiki_page(deleted_at);
CREATE INDEX idx_wiki_page_merged_into ON wiki_page(merged_into_page_id);
