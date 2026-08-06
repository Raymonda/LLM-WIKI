-- Fix: autoResolve incorrectly set archivedAt, causing auto_resolved findings
-- to be invisible in the "AI处理" tab.
-- Clear archivedAt only for the LATEST execution's auto_resolved findings per scope,
-- so users immediately see the most recent results. Older executions' auto_resolved
-- findings will be archived by autoArchiveSupersededFindings on next lint run.
UPDATE lint_finding f
INNER JOIN (
    SELECT scope_id, MAX(execution_id) AS max_exec_id
    FROM lint_finding
    WHERE status = 'auto_resolved' AND archived_at IS NOT NULL
    GROUP BY scope_id
) latest ON f.scope_id = latest.scope_id AND f.execution_id = latest.max_exec_id
SET f.archived_at = NULL
WHERE f.status = 'auto_resolved' AND f.archived_at IS NOT NULL;
