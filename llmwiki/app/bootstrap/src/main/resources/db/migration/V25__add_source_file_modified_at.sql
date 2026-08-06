-- V25: source 表增加 file_modified_at 列，记录 raw/ 下源文件的真实修改时间
-- 用于 stale 检测（替换 source.updated_at，后者被 Pipeline 处理活动频繁 bump 导致假阳性）
-- file_modified_at 写入后不再被 Pipeline 触碰，语义纯粹：来源文件内容最后一次变更的时间

ALTER TABLE source
    ADD COLUMN file_modified_at DATETIME DEFAULT NULL COMMENT '来源文件在存储层中的真实修改时间（仅上传时写入，后续不更新）' AFTER updated_at;

-- 回填已有数据：对于已存在的 source 记录，用 created_at 作为 file_modified_at 的兜底值
-- 后续重新上传时会由 SourceService.uploadSource 写入真实的存储层 mtime
UPDATE source SET file_modified_at = created_at WHERE file_modified_at IS NULL;