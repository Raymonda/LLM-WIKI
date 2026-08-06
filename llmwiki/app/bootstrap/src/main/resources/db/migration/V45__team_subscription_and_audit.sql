-- V45: Team edition - Subscription + Audit Log

CREATE TABLE scope_subscription (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  subscriber_scope_id BIGINT NOT NULL,
  publisher_scope_id BIGINT NOT NULL,
  status VARCHAR(20) DEFAULT 'active',
  subscription_type VARCHAR(20) DEFAULT 'mirror',
  created_by BIGINT NOT NULL,
  created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_sub_pub (subscriber_scope_id, publisher_scope_id),
  INDEX idx_subscriber (subscriber_scope_id, status),
  INDEX idx_publisher (publisher_scope_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE audit_log (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  actor_user_id BIGINT NOT NULL,
  actor_username VARCHAR(100),
  action VARCHAR(50) NOT NULL,
  target_type VARCHAR(50) NOT NULL,
  target_id BIGINT,
  target_name VARCHAR(500),
  scope_id BIGINT NOT NULL,
  detail_json TEXT,
  ip_address VARCHAR(50),
  user_agent VARCHAR(500),
  created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
  INDEX idx_scope_created (scope_id, created_at),
  INDEX idx_actor (actor_user_id, created_at),
  INDEX idx_action (action, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
