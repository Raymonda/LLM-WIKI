-- Fix: several notification emitters mistakenly passed scopeId into the user_id column
-- (signature: user_id = scope_id), so notifications were routed to a non-existent user
-- in personal scopes and to the wrong recipient in team scopes.
-- Remap only rows that match the bug signature AND whose user_id is neither the scope
-- owner nor a scope member (legitimate member notifications are left untouched).
UPDATE notification n
INNER JOIN scope s ON s.id = n.scope_id
LEFT JOIN scope_member sm ON sm.scope_id = n.scope_id AND sm.user_id = n.user_id
SET n.user_id = s.owner_id
WHERE n.user_id = n.scope_id
  AND s.owner_id <> n.user_id
  AND sm.id IS NULL;
