CREATE INDEX idx_wiki_page_link_scope_from ON wiki_page_link(scope_id, from_page_id);
CREATE INDEX idx_wiki_page_link_scope_to ON wiki_page_link(scope_id, to_page_id);
CREATE INDEX idx_wiki_page_link_scope_type ON wiki_page_link(scope_id, link_type);
CREATE INDEX idx_wiki_page_scope_health ON wiki_page(scope_id, health_status);
