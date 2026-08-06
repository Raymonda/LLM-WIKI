-- V46: Scope visibility + Join request

ALTER TABLE scope ADD COLUMN visibility VARCHAR(20) DEFAULT 'members_only';

CREATE TABLE scope_join_request (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  scope_id BIGINT NOT NULL,
  user_id BIGINT NOT NULL,
  message VARCHAR(500),
  status VARCHAR(20) DEFAULT 'pending',
  reviewer_id BIGINT,
  review_message VARCHAR(500),
  created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
  reviewed_at DATETIME,
  INDEX idx_scope_status (scope_id, status),
  INDEX idx_user (user_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
