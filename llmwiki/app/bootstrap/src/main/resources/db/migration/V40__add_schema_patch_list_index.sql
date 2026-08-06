-- 审批中心列表查询优化：覆盖索引消除 filesort
CREATE INDEX idx_schema_patch_scope_status_created ON schema_patch(scope_id, status, created_at DESC);
