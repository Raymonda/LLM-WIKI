-- ============================================================================
-- V52: 个人 scope 落表 —— 彻底消除“个人 scope id 复用 userId”与团队 scope 自增 id 撞车的缺陷
--
-- 改造前：个人知识库是“虚拟 scope”，其 id 直接复用 userId（ScopeServiceImpl.buildPersonalScope
--         中 personal.setId(userId)）。个人 scope 因此与团队 scope（自增 id）共用同一 ID 空间，
--         可能撞车：例如 admin(id=1) 的个人 scope_id=1 与首个团队 scope(id=1) 重合，
--         selectById(1) 返回团队 scope，导致个人数据查询“页面不存在”。
--
-- 改造后：userId 与 scopeId 成为两个完全独立的 ID 空间：
--         - 个人 scope 是 scope 表中 type='personal' 的真实记录，user.scope_id 指向其自增 id
--         - 一对一关系由 scope.owner_id 表达；多对多加入团队由 scope_member 表达
--
-- 撞车区归属策略：
--         凡 scope_id 命中任何组织型 scope id 集合（scope.type <> 'personal' 的 id，含 team/department）
--         的行，判为组织数据，保持不动；其余 scope_id（等于某个 userId）的行判为个人数据，
--         重映射到该用户 personal scope 的自增 id。
--         撞车用户的个人历史数据（其 scope_id 与某组织型 scope id 重合）在改造前即已不可达，
--         此处与运行时 selectById 行为保持一致（视为组织数据）。
-- ============================================================================

-- 1) 为每个用户补建 personal scope（幂等：已存在则跳过）
INSERT INTO scope (name, description, type, owner_id, monthly_budget, default_approval,
                   max_file_size, max_concurrent, visibility, language)
SELECT CONCAT(u.username, '的个人知识库'),
       '个人知识库',
       'personal',
       u.id,
       1000000,
       'auto',
       10485760,
       1,
       'private',
       COALESCE(u.language, 'zh-CN')
FROM `user` u
WHERE NOT EXISTS (SELECT 1 FROM scope s WHERE s.type = 'personal' AND s.owner_id = u.id);

-- 2) 重映射 scope_budget 中的个人行（非撞车）：旧 scope_id = userId → 个人 scope 自增 id
UPDATE scope_budget b
JOIN scope persona ON persona.type = 'personal' AND persona.owner_id = b.scope_id
SET b.scope_id = persona.id
WHERE b.scope_id NOT IN (SELECT id FROM scope WHERE type <> 'personal');

-- 3) 为每个个人 scope 补齐预算行（覆盖撞车用户：其旧预算行已归属团队 scope）
INSERT INTO scope_budget (scope_id, monthly_budget, used_tokens, reset_date, warning_notified, exceeded_notified)
SELECT s.id, s.monthly_budget, 0, DATE_ADD(LAST_DAY(CURDATE()), INTERVAL 1 DAY), 0, 0
FROM scope s
WHERE s.type = 'personal'
  AND NOT EXISTS (SELECT 1 FROM scope_budget b WHERE b.scope_id = s.id);

-- 4) 回填 user.scope_id 指向其个人 scope 的自增 id
UPDATE `user` u
JOIN scope persona ON persona.type = 'personal' AND persona.owner_id = u.id
SET u.scope_id = persona.id;

-- ============================================================================
-- 5) 重映射各业务表中的个人 scope_id
--    统一模式：scope_id = 某 userId 且不在任何组织型 scope id 集合内 → 映射到该用户 personal scope 的自增 id
--    例外：scope_member / scope_subscription 仅承载团队关系，personal scope 不写入，无需重映射
-- ============================================================================

UPDATE system_config t
JOIN scope persona ON persona.type = 'personal' AND persona.owner_id = t.scope_id
SET t.scope_id = persona.id
WHERE t.scope_id NOT IN (SELECT id FROM scope WHERE type <> 'personal');

UPDATE source t
JOIN scope persona ON persona.type = 'personal' AND persona.owner_id = t.scope_id
SET t.scope_id = persona.id
WHERE t.scope_id NOT IN (SELECT id FROM scope WHERE type <> 'personal');

UPDATE wiki_page t
JOIN scope persona ON persona.type = 'personal' AND persona.owner_id = t.scope_id
SET t.scope_id = persona.id
WHERE t.scope_id NOT IN (SELECT id FROM scope WHERE type <> 'personal');

UPDATE wiki_page_tag t
JOIN scope persona ON persona.type = 'personal' AND persona.owner_id = t.scope_id
SET t.scope_id = persona.id
WHERE t.scope_id NOT IN (SELECT id FROM scope WHERE type <> 'personal');

UPDATE wiki_page_keyword t
JOIN scope persona ON persona.type = 'personal' AND persona.owner_id = t.scope_id
SET t.scope_id = persona.id
WHERE t.scope_id NOT IN (SELECT id FROM scope WHERE type <> 'personal');

UPDATE wiki_page_link t
JOIN scope persona ON persona.type = 'personal' AND persona.owner_id = t.scope_id
SET t.scope_id = persona.id
WHERE t.scope_id NOT IN (SELECT id FROM scope WHERE type <> 'personal');

UPDATE wiki_page_source t
JOIN scope persona ON persona.type = 'personal' AND persona.owner_id = t.scope_id
SET t.scope_id = persona.id
WHERE t.scope_id NOT IN (SELECT id FROM scope WHERE type <> 'personal');

UPDATE schema_config t
JOIN scope persona ON persona.type = 'personal' AND persona.owner_id = t.scope_id
SET t.scope_id = persona.id
WHERE t.scope_id NOT IN (SELECT id FROM scope WHERE type <> 'personal');

UPDATE execution t
JOIN scope persona ON persona.type = 'personal' AND persona.owner_id = t.scope_id
SET t.scope_id = persona.id
WHERE t.scope_id NOT IN (SELECT id FROM scope WHERE type <> 'personal');

UPDATE search_index_retry t
JOIN scope persona ON persona.type = 'personal' AND persona.owner_id = t.scope_id
SET t.scope_id = persona.id
WHERE t.scope_id NOT IN (SELECT id FROM scope WHERE type <> 'personal');

UPDATE step_baseline t
JOIN scope persona ON persona.type = 'personal' AND persona.owner_id = t.scope_id
SET t.scope_id = persona.id
WHERE t.scope_id NOT IN (SELECT id FROM scope WHERE type <> 'personal');

UPDATE schema_patch t
JOIN scope persona ON persona.type = 'personal' AND persona.owner_id = t.scope_id
SET t.scope_id = persona.id
WHERE t.scope_id NOT IN (SELECT id FROM scope WHERE type <> 'personal');

UPDATE schema_config_version t
JOIN scope persona ON persona.type = 'personal' AND persona.owner_id = t.scope_id
SET t.scope_id = persona.id
WHERE t.scope_id NOT IN (SELECT id FROM scope WHERE type <> 'personal');

UPDATE lint_finding t
JOIN scope persona ON persona.type = 'personal' AND persona.owner_id = t.scope_id
SET t.scope_id = persona.id
WHERE t.scope_id NOT IN (SELECT id FROM scope WHERE type <> 'personal');

UPDATE conflict_review t
JOIN scope persona ON persona.type = 'personal' AND persona.owner_id = t.scope_id
SET t.scope_id = persona.id
WHERE t.scope_id NOT IN (SELECT id FROM scope WHERE type <> 'personal');

UPDATE token_usage_daily t
JOIN scope persona ON persona.type = 'personal' AND persona.owner_id = t.scope_id
SET t.scope_id = persona.id
WHERE t.scope_id NOT IN (SELECT id FROM scope WHERE type <> 'personal');

UPDATE wiki_page_draft t
JOIN scope persona ON persona.type = 'personal' AND persona.owner_id = t.scope_id
SET t.scope_id = persona.id
WHERE t.scope_id NOT IN (SELECT id FROM scope WHERE type <> 'personal');

UPDATE edit_session t
JOIN scope persona ON persona.type = 'personal' AND persona.owner_id = t.scope_id
SET t.scope_id = persona.id
WHERE t.scope_id NOT IN (SELECT id FROM scope WHERE type <> 'personal');

UPDATE audit_log t
JOIN scope persona ON persona.type = 'personal' AND persona.owner_id = t.scope_id
SET t.scope_id = persona.id
WHERE t.scope_id NOT IN (SELECT id FROM scope WHERE type <> 'personal');

UPDATE scope_join_request t
JOIN scope persona ON persona.type = 'personal' AND persona.owner_id = t.scope_id
SET t.scope_id = persona.id
WHERE t.scope_id NOT IN (SELECT id FROM scope WHERE type <> 'personal');

UPDATE api_key t
JOIN scope persona ON persona.type = 'personal' AND persona.owner_id = t.scope_id
SET t.scope_id = persona.id
WHERE t.scope_id NOT IN (SELECT id FROM scope WHERE type <> 'personal');

UPDATE notification t
JOIN scope persona ON persona.type = 'personal' AND persona.owner_id = t.scope_id
SET t.scope_id = persona.id
WHERE t.scope_id NOT IN (SELECT id FROM scope WHERE type <> 'personal');

-- 6) 重映射知识上浮来源 scope 引用（wiki_page.promoted_from_scope_id）
UPDATE wiki_page p
JOIN scope persona ON persona.type = 'personal' AND persona.owner_id = p.promoted_from_scope_id
SET p.promoted_from_scope_id = persona.id
WHERE p.promoted_from_scope_id IS NOT NULL
  AND p.promoted_from_scope_id NOT IN (SELECT id FROM scope WHERE type <> 'personal');