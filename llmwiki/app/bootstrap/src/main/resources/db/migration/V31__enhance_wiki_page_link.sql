-- V31: 增强 wiki_page_link 表，支持链接上下文、溯源和置信度
-- Phase 1: 为交叉引用链接增加 AI 判断所需的字段

ALTER TABLE wiki_page_link
    ADD COLUMN link_context TEXT COMMENT '链接上下文说明（AI生成的关系描述）',
    ADD COLUMN created_by VARCHAR(32) DEFAULT 'manual' COMMENT '创建来源: lint_ai/ingest_ai/manual',
    ADD COLUMN confidence DECIMAL(3,2) COMMENT 'AI置信度(0.00-1.00)',
    ADD COLUMN execution_id BIGINT COMMENT '关联的执行记录ID',
    ADD COLUMN created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '链接创建时间';

-- 增加索引以支持溯源查询
CREATE INDEX idx_wiki_page_link_created_by ON wiki_page_link(created_by);
CREATE INDEX idx_wiki_page_link_execution_id ON wiki_page_link(execution_id);
