CREATE TABLE scope (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    name VARCHAR(128) NOT NULL,
    description VARCHAR(256),
    type VARCHAR(16) NOT NULL DEFAULT 'personal',
    owner_id BIGINT NOT NULL,
    monthly_budget INT NOT NULL DEFAULT 50000,
    default_approval VARCHAR(16) NOT NULL DEFAULT 'auto',
    max_file_size INT NOT NULL DEFAULT 10485760,
    max_concurrent INT NOT NULL DEFAULT 1,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
);

CREATE TABLE scope_member (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    scope_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    role VARCHAR(16) NOT NULL DEFAULT 'viewer',
    joined_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (scope_id) REFERENCES scope(id) ON DELETE CASCADE,
    FOREIGN KEY (user_id) REFERENCES `user`(id) ON DELETE CASCADE,
    UNIQUE KEY uk_scope_user (scope_id, user_id)
);

CREATE INDEX idx_scope_owner_id ON scope(owner_id);
CREATE INDEX idx_scope_member_scope_id ON scope_member(scope_id);
CREATE INDEX idx_scope_member_user_id ON scope_member(user_id);

ALTER TABLE `user` MODIFY COLUMN scope_id BIGINT NOT NULL DEFAULT 0;