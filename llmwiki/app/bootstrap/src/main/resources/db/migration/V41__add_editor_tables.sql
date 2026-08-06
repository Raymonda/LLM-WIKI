-- V41: Wiki editor tables (draft, edit session, edit step)

CREATE TABLE wiki_page_draft (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  scope_id BIGINT NOT NULL,
  page_id BIGINT NULL,
  base_content_hash VARCHAR(64) NULL,
  title VARCHAR(255) NOT NULL,
  content MEDIUMTEXT,
  category VARCHAR(255),
  tags JSON,
  page_type VARCHAR(20) DEFAULT 'manual',
  status VARCHAR(20) DEFAULT 'draft',
  created_at DATETIME NOT NULL,
  updated_at DATETIME NOT NULL,
  created_by BIGINT NOT NULL,
  INDEX idx_scope_status (scope_id, status),
  INDEX idx_page (page_id),
  INDEX idx_updated (updated_at)
);

CREATE TABLE edit_session (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  scope_id BIGINT NOT NULL,
  page_id BIGINT NULL,
  draft_id BIGINT NULL,
  current_content MEDIUMTEXT NOT NULL,
  content_hash VARCHAR(64) NOT NULL,
  outline TEXT,
  history_summary TEXT,
  step_count INT DEFAULT 0,
  status VARCHAR(20) DEFAULT 'active',
  created_at DATETIME NOT NULL,
  updated_at DATETIME NOT NULL,
  created_by BIGINT NOT NULL,
  INDEX idx_scope_status (scope_id, status),
  INDEX idx_page (page_id),
  INDEX idx_updated (updated_at)
);

CREATE TABLE edit_step (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  session_id BIGINT NOT NULL,
  step_number INT NOT NULL,
  selected_lines VARCHAR(32),
  instruction TEXT NOT NULL,
  diff_removed TEXT,
  diff_added TEXT,
  content_after MEDIUMTEXT,
  created_at DATETIME NOT NULL,
  INDEX idx_session (session_id, step_number)
);

ALTER TABLE wiki_page ADD COLUMN user_modified TINYINT DEFAULT 0;
ALTER TABLE wiki_page ADD COLUMN content_hash VARCHAR(64);
