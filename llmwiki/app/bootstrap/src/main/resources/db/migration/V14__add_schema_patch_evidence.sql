-- 宪法规则 5+7：Schema 补丁必须带证据字段与置信度
-- evidence_json: LLM 产出时记录"从本次产出摘要中引用的 N 条具体片段"，不足则进观察期
-- confidence: 0.00 ~ 1.00 之间，>=0.7 视为高置信（进 PENDING 红点），< 0.7 视为低置信（进 OBSERVING）

ALTER TABLE schema_patch
    ADD COLUMN evidence_json TEXT DEFAULT NULL COMMENT '证据数组 JSON，每条为一条引用片段',
    ADD COLUMN confidence DECIMAL(3,2) DEFAULT NULL COMMENT '置信度 0.00-1.00，NULL 表示历史数据未评估';

CREATE INDEX idx_schema_patch_confidence ON schema_patch(scope_id, status, confidence);
