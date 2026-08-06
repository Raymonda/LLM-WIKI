ALTER TABLE wiki_page ADD COLUMN visibility VARCHAR(16) NOT NULL DEFAULT 'open';
ALTER TABLE wiki_page ADD COLUMN promoted_from_scope_id BIGINT;
ALTER TABLE wiki_page ADD COLUMN promoted_from_page_id BIGINT;
ALTER TABLE wiki_page ADD COLUMN promoted_from_username VARCHAR(64);

ALTER TABLE `user` ADD COLUMN consent_knowledge_promotion TINYINT NOT NULL DEFAULT 1;

ALTER TABLE scope ADD COLUMN upstream_scope_ids VARCHAR(512);

CREATE INDEX idx_wiki_page_visibility_scope ON wiki_page(visibility, scope_id);
CREATE INDEX idx_wiki_page_promoted_from ON wiki_page(promoted_from_scope_id, promoted_from_page_id);