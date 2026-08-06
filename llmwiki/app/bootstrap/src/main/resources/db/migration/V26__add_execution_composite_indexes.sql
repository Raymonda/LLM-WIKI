CREATE INDEX idx_execution_scope_created ON execution(scope_id, created_at DESC);
CREATE INDEX idx_execution_scope_type_created ON execution(scope_id, type, created_at DESC);
CREATE INDEX idx_execution_step_exec_order ON execution_step(execution_id, step_order);