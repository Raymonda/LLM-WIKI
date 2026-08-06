-- Fix user.role semantics: system-level role is admin/user (not scope-level owner)
-- Existing rows with role='owner' are the legacy scope-level default — migrate to 'user'
UPDATE `user` SET role = 'user' WHERE role = 'owner';
ALTER TABLE `user` MODIFY COLUMN role VARCHAR(32) NOT NULL DEFAULT 'user';
