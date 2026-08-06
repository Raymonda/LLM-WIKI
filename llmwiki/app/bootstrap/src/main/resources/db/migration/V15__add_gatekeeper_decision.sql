-- V15: P2-i Gatekeeper 决定结构化持久化
-- 宪法规则 3：Proposer + Gatekeeper 双 LLM 共识。此前 gatekeeper 的 decision/reason
-- 只拼入 rationale 前缀，无法结构化检索与回流准确率指标。
-- 本次补两列：
--   gatekeeper_decision: APPROVE / OBSERVE / REJECT（REJECT 不入库，实际只会出现 APPROVE/OBSERVE）
--   gatekeeper_reason:   Gatekeeper 给出的一句话理由
-- accept/reject/ignore 时可通过 decision 字段与最终用户意图对比，日志记录准确率。

ALTER TABLE schema_patch
    ADD COLUMN gatekeeper_decision VARCHAR(16) DEFAULT NULL COMMENT 'Gatekeeper 守门决定：APPROVE / OBSERVE',
    ADD COLUMN gatekeeper_reason   VARCHAR(200) DEFAULT NULL COMMENT 'Gatekeeper 理由（<=40 字）';

CREATE INDEX idx_schema_patch_gatekeeper ON schema_patch(scope_id, gatekeeper_decision);
