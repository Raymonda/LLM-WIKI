ALTER TABLE notification ADD COLUMN execution_id BIGINT NULL AFTER related_page_id;
CREATE INDEX idx_notification_execution ON notification(execution_id);
