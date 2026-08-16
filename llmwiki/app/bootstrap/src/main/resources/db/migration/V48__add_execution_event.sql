CREATE TABLE execution_event (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    execution_id VARCHAR(128) NOT NULL,
    seq INT NOT NULL,
    event_type VARCHAR(64) NOT NULL,
    payload_json MEDIUMTEXT NULL,
    node_id VARCHAR(128) NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_execution_event_seq (execution_id, seq),
    KEY idx_execution_event_exec (execution_id, id)
);
