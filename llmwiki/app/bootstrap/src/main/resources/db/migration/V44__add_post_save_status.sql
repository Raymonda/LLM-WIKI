-- V44: Add post-save async processing status tracking to wiki_page
-- Tracks the state of async post-processing (summary generation, schema check, link sync, full ES index)

ALTER TABLE wiki_page ADD COLUMN post_save_status VARCHAR(20) DEFAULT NULL;
ALTER TABLE wiki_page ADD COLUMN post_save_error VARCHAR(1000) DEFAULT NULL;
