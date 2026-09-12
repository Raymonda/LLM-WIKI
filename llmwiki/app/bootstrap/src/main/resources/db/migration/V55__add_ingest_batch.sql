-- V55: Ingest batch queue + review inbox
CREATE TABLE ingest_batch (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  scope_id BIGINT NOT NULL,
  user_id BIGINT NOT NULL,
  status VARCHAR(20) NOT NULL DEFAULT 'active',
  total_count INT NOT NULL DEFAULT 0,
  guidance TEXT,
  created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  completed_at DATETIME,
  INDEX idx_ingest_batch_scope (scope_id, status, created_at),
  INDEX idx_ingest_batch_user (user_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

ALTER TABLE execution
  ADD COLUMN batch_id BIGINT NULL,
  ADD COLUMN guidance TEXT NULL,
  ADD INDEX idx_execution_batch (batch_id);

ALTER TABLE notification
  ADD COLUMN batch_id BIGINT NULL,
  ADD INDEX idx_notification_batch (batch_id);
