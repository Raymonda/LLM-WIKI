ALTER TABLE scope_budget
    ADD COLUMN warning_notified TINYINT NOT NULL DEFAULT 0,
    ADD COLUMN exceeded_notified TINYINT NOT NULL DEFAULT 0;