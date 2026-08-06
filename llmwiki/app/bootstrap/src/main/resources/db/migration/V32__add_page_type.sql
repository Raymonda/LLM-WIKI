ALTER TABLE wiki_page
    ADD COLUMN page_type VARCHAR(32) COMMENT '页面类型: summary/entity/concept/procedure/faq/comparison/chapter-nav';

UPDATE wiki_page SET page_type = 'summary' WHERE file_path LIKE '%/summary.md' OR title LIKE '%摘要%' OR title LIKE '%总结%';
UPDATE wiki_page SET page_type = 'chapter-nav' WHERE file_path LIKE '%/chapter-%' AND page_type IS NULL;
UPDATE wiki_page SET page_type = 'entity' WHERE page_type IS NULL;
