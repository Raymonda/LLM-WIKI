CREATE TABLE `user` (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    username VARCHAR(64) NOT NULL UNIQUE,
    password_hash VARCHAR(256) NOT NULL,
    email VARCHAR(128),
    avatar VARCHAR(256),
    role VARCHAR(32) NOT NULL DEFAULT 'owner',
    status VARCHAR(16) NOT NULL DEFAULT 'active',
    scope_id BIGINT NOT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
);

CREATE TABLE system_config (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    config_key VARCHAR(128) NOT NULL,
    config_value LONGTEXT,
    config_group VARCHAR(64),
    scope_id BIGINT NOT NULL,
    description VARCHAR(256),
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
);

CREATE TABLE source (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    name VARCHAR(256) NOT NULL,
    file_path VARCHAR(512) NOT NULL,
    format VARCHAR(16) NOT NULL,
    size BIGINT NOT NULL DEFAULT 0,
    status VARCHAR(16) NOT NULL DEFAULT 'pending',
    scope_id BIGINT NOT NULL,
    upload_user_id BIGINT NOT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE wiki_page (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    title VARCHAR(256) NOT NULL,
    file_path VARCHAR(512) NOT NULL,
    category VARCHAR(64),
    summary LONGTEXT,
    scope_id BIGINT NOT NULL,
    source_count INT NOT NULL DEFAULT 0,
    health_status VARCHAR(16) NOT NULL DEFAULT 'healthy',
    last_checked_at DATETIME,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
);

CREATE TABLE wiki_page_tag (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    page_id BIGINT NOT NULL,
    tag VARCHAR(64) NOT NULL,
    FOREIGN KEY (page_id) REFERENCES wiki_page(id) ON DELETE CASCADE
);

CREATE TABLE wiki_page_keyword (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    page_id BIGINT NOT NULL,
    keyword VARCHAR(128) NOT NULL,
    FOREIGN KEY (page_id) REFERENCES wiki_page(id) ON DELETE CASCADE
);

CREATE TABLE wiki_page_link (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    from_page_id BIGINT NOT NULL,
    to_page_id BIGINT NOT NULL,
    link_type VARCHAR(32) NOT NULL DEFAULT 'cross-reference',
    FOREIGN KEY (from_page_id) REFERENCES wiki_page(id) ON DELETE CASCADE,
    FOREIGN KEY (to_page_id) REFERENCES wiki_page(id) ON DELETE CASCADE
);

CREATE TABLE wiki_page_source (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    page_id BIGINT NOT NULL,
    source_id BIGINT NOT NULL,
    FOREIGN KEY (page_id) REFERENCES wiki_page(id) ON DELETE CASCADE,
    FOREIGN KEY (source_id) REFERENCES source(id) ON DELETE CASCADE
);

CREATE TABLE schema_config (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    config_key VARCHAR(128) NOT NULL,
    config_value LONGTEXT,
    config_group VARCHAR(64),
    scope_id BIGINT NOT NULL,
    description VARCHAR(256),
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
);

CREATE TABLE execution (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    type VARCHAR(32) NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'pending',
    scope_id BIGINT NOT NULL,
    source_id BIGINT,
    schema_config_id BIGINT,
    started_at DATETIME,
    completed_at DATETIME,
    total_tokens INT NOT NULL DEFAULT 0,
    total_cost DECIMAL(10,4) NOT NULL DEFAULT 0,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE execution_step (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    execution_id BIGINT NOT NULL,
    step_name VARCHAR(64) NOT NULL,
    step_order INT NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'pending',
    input_data LONGTEXT,
    output_data LONGTEXT,
    tokens_used INT NOT NULL DEFAULT 0,
    duration_ms INT NOT NULL DEFAULT 0,
    approval_level VARCHAR(16) NOT NULL DEFAULT 'auto',
    approved_by BIGINT,
    started_at DATETIME,
    completed_at DATETIME,
    FOREIGN KEY (execution_id) REFERENCES execution(id) ON DELETE CASCADE
);

CREATE TABLE scope_budget (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    scope_id BIGINT NOT NULL UNIQUE,
    monthly_budget INT NOT NULL DEFAULT 50000,
    used_tokens INT NOT NULL DEFAULT 0,
    reset_date DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_user_scope_id ON `user`(scope_id);
CREATE INDEX idx_user_username ON `user`(username);
CREATE INDEX idx_source_scope_id ON source(scope_id);
CREATE INDEX idx_source_upload_user_id ON source(upload_user_id);
CREATE INDEX idx_wiki_page_scope_id ON wiki_page(scope_id);
CREATE INDEX idx_wiki_page_category ON wiki_page(category);
CREATE INDEX idx_wiki_page_file_path ON wiki_page(file_path);
CREATE INDEX idx_wiki_page_tag_page_id ON wiki_page_tag(page_id);
CREATE INDEX idx_wiki_page_keyword_page_id ON wiki_page_keyword(page_id);
CREATE INDEX idx_wiki_page_link_from ON wiki_page_link(from_page_id);
CREATE INDEX idx_wiki_page_link_to ON wiki_page_link(to_page_id);
CREATE INDEX idx_wiki_page_source_page ON wiki_page_source(page_id);
CREATE INDEX idx_schema_config_scope_id ON schema_config(scope_id);
CREATE INDEX idx_execution_scope_id ON execution(scope_id);
CREATE INDEX idx_execution_step_execution_id ON execution_step(execution_id);
CREATE INDEX idx_scope_budget_scope_id ON scope_budget(scope_id);