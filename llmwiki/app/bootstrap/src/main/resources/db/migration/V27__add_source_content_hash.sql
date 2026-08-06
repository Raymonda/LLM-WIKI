ALTER TABLE source ADD COLUMN content_hash VARCHAR(64) DEFAULT NULL COMMENT '文件内容 SHA-256 哈希，用于幂等去重' AFTER file_modified_at;

CREATE INDEX idx_source_scope_hash_status ON source(scope_id, content_hash, status);