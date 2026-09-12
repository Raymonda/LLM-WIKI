CREATE INDEX idx_execution_scope_status_created ON execution(scope_id, status, created_at DESC);

