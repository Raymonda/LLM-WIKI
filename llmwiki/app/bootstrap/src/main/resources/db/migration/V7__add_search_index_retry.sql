CREATE TABLE search_index_retry (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    scope_id BIGINT NOT NULL,
    page_id BIGINT NOT NULL,
    operation VARCHAR(16) NOT NULL COMMENT 'INDEX or REMOVE',
    payload MEDIUMTEXT COMMENT 'JSON payload for INDEX op; NULL for REMOVE',
    retry_count INT NOT NULL DEFAULT 0,
    max_attempts INT NOT NULL DEFAULT 5,
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/SUCCESS/DEAD',
    last_error VARCHAR(1024),
    next_retry_at DATETIME,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
);

CREATE INDEX idx_search_retry_status_next ON search_index_retry(status, next_retry_at);
CREATE INDEX idx_search_retry_scope_page ON search_index_retry(scope_id, page_id);
