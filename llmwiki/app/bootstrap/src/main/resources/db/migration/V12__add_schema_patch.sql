-- Schema共治宪法规则3：Ingest/Query/Lint产出的Schema补丁提案需落库等待审批
-- 提案闭环：PENDING -> ACCEPTED/REJECTED/IGNORED/SUPERSEDED
-- 相关设计：AGENTS.md「Schema共治宪法」规则3 + DESIGN.md「Schema生长」

CREATE TABLE schema_patch (
    id                  BIGINT          NOT NULL AUTO_INCREMENT,
    scope_id            BIGINT          NOT NULL,
    source_execution_id BIGINT          DEFAULT NULL COMMENT '触发本次提案的execution_id；手工提案可为空',
    source_type         VARCHAR(16)     NOT NULL COMMENT 'INGEST/QUERY/LINT/MANUAL',
    section_title       VARCHAR(128)    NOT NULL COMMENT '目标section的H2标题，例如"## 2. 分类体系"',
    operation           VARCHAR(16)     NOT NULL COMMENT 'ADD/MODIFY/DELETE',
    diff_before         MEDIUMTEXT      DEFAULT NULL COMMENT '操作前section内容，MODIFY/DELETE时必填',
    diff_after          MEDIUMTEXT      DEFAULT NULL COMMENT '操作后section内容，ADD/MODIFY时必填',
    rationale           TEXT            DEFAULT NULL COMMENT 'AI提案理由，用于用户审阅',
    status              VARCHAR(16)     NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/ACCEPTED/REJECTED/IGNORED/SUPERSEDED',
    decided_by          BIGINT          DEFAULT NULL COMMENT '决策用户id',
    decided_at          DATETIME        DEFAULT NULL,
    applied_schema_id   BIGINT          DEFAULT NULL COMMENT 'ACCEPTED时写入的schema_config.id，用于回滚溯源',
    created_at          DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_scope_status (scope_id, status),
    KEY idx_source_execution (source_execution_id),
    KEY idx_created_at (created_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = 'Schema补丁提案表';
