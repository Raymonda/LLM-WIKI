-- ============================================================================
-- V54: scope 限额默认值治理
--
-- 1) max_file_size 语义统一为 MB：历史写入侧（V3 默认值 / 注册建库 / V52 补建）
--    均按字节写入，而 RateLimitService.checkFileSize 按 MB 解释（再乘 1024*1024），
--    导致文件大小限制实际失效（10485760 被当作 10TB）。
--    存量字节值（≥1MB 的行）统一换算为 MB，列默认值改为 10（MB）。
--
-- 2) monthly_budget 默认值对齐：V3 默认 50000 与运行时建库值（个人 1000000）
--    相差 20 倍，统一为 1000000；scope_budget 快照表同步修正。
-- ============================================================================

ALTER TABLE scope MODIFY COLUMN max_file_size INT NOT NULL DEFAULT 10;

UPDATE scope
SET max_file_size = GREATEST(1, ROUND(max_file_size / 1048576))
WHERE max_file_size >= 1048576;

ALTER TABLE scope MODIFY COLUMN monthly_budget INT NOT NULL DEFAULT 1000000;

UPDATE scope SET monthly_budget = 1000000 WHERE monthly_budget = 50000;

UPDATE scope_budget SET monthly_budget = 1000000 WHERE monthly_budget = 50000;
