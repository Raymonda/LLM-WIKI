ALTER TABLE execution ADD COLUMN node_id VARCHAR(128) NULL AFTER error_message;
CREATE INDEX idx_execution_status_node ON execution(status, node_id);
