-- system_config 表由 V2 创建（无 updated_by 列），导致 V24 的 CREATE TABLE IF NOT EXISTS
-- 在已建表的库上成为空操作，updated_by 列与 uk_scope_key 唯一键从未生效。
-- 此迁移幂等补齐两者：对存量库（表已存在）和新库（V2 先建表）都正确。

SET @col_exists := (SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'system_config' AND COLUMN_NAME = 'updated_by');
SET @ddl := IF(@col_exists = 0,
    'ALTER TABLE system_config ADD COLUMN updated_by BIGINT DEFAULT NULL AFTER scope_id',
    'SELECT 1');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @idx_exists := (SELECT COUNT(*) FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'system_config' AND INDEX_NAME = 'uk_scope_key');
SET @ddl2 := IF(@idx_exists = 0,
    'ALTER TABLE system_config ADD UNIQUE KEY uk_scope_key (scope_id, config_key)',
    'SELECT 1');
PREPARE stmt2 FROM @ddl2;
EXECUTE stmt2;
DEALLOCATE PREPARE stmt2;
