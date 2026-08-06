-- 知识编译硬性约束：关系表必须带 scope_id，不依赖 JOIN 的隔离是不可靠的
-- 向 wiki_page 的四张关系表补充 scope_id 字段，并从 wiki_page 回填数据
-- 相关设计说明：ARCHITECTURE.md「数据库 Schema」章节

ALTER TABLE wiki_page_tag
    ADD COLUMN scope_id BIGINT NOT NULL DEFAULT 0 AFTER id;

ALTER TABLE wiki_page_keyword
    ADD COLUMN scope_id BIGINT NOT NULL DEFAULT 0 AFTER id;

ALTER TABLE wiki_page_link
    ADD COLUMN scope_id BIGINT NOT NULL DEFAULT 0 AFTER id;

ALTER TABLE wiki_page_source
    ADD COLUMN scope_id BIGINT NOT NULL DEFAULT 0 AFTER id,
    ADD COLUMN created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP AFTER source_id;

-- 从 wiki_page 回填历史数据
UPDATE wiki_page_tag t
    INNER JOIN wiki_page p ON t.page_id = p.id
    SET t.scope_id = p.scope_id
    WHERE t.scope_id = 0;

UPDATE wiki_page_keyword k
    INNER JOIN wiki_page p ON k.page_id = p.id
    SET k.scope_id = p.scope_id
    WHERE k.scope_id = 0;

UPDATE wiki_page_link l
    INNER JOIN wiki_page p ON l.from_page_id = p.id
    SET l.scope_id = p.scope_id
    WHERE l.scope_id = 0;

UPDATE wiki_page_source s
    INNER JOIN wiki_page p ON s.page_id = p.id
    SET s.scope_id = p.scope_id
    WHERE s.scope_id = 0;

-- 补充 scope_id 索引（关键的隔离过滤索引）
CREATE INDEX idx_wiki_page_tag_scope_id ON wiki_page_tag(scope_id);
CREATE INDEX idx_wiki_page_keyword_scope_id ON wiki_page_keyword(scope_id);
CREATE INDEX idx_wiki_page_link_scope_id ON wiki_page_link(scope_id);
CREATE INDEX idx_wiki_page_source_scope_id ON wiki_page_source(scope_id);
