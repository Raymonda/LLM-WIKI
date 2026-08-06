ALTER TABLE wiki_page ADD COLUMN lifecycle_status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE';

UPDATE wiki_page SET lifecycle_status = 'DELETED' WHERE deleted_at IS NOT NULL;
UPDATE wiki_page SET lifecycle_status = 'MERGED' WHERE lifecycle_status = 'ACTIVE' AND merged_into_page_id IS NOT NULL;
UPDATE wiki_page SET lifecycle_status = 'DEPRECATED' WHERE lifecycle_status = 'ACTIVE' AND deprecated_at IS NOT NULL;

CREATE INDEX idx_wiki_page_lifecycle ON wiki_page (scope_id, lifecycle_status);
