-- V42: Store original content in edit_session for proper undo support
-- Fixes bug where undoing step 1 restored content AFTER step 1 instead of before

ALTER TABLE edit_session ADD COLUMN original_content LONGTEXT DEFAULT NULL COMMENT 'Content before any edits, for undo-all and undo-step-1';
