-- 宪法规则 5：Schema 版本化，页面溯源
-- 每次 Schema 变更（冷启动 finalize / Patch accept / 未来的手动调整）都追加一条版本记录，历史永不删除
-- parent_version_id 串联版本链，支持回滚和 diff

CREATE TABLE schema_config_version (
    id                  BIGINT          NOT NULL AUTO_INCREMENT,
    scope_id            BIGINT          NOT NULL,
    config_key          VARCHAR(64)     NOT NULL COMMENT '如 wiki_schema',
    config_value        MEDIUMTEXT      NOT NULL COMMENT '该版本的完整 Schema Markdown 内容',
    parent_version_id   BIGINT          DEFAULT NULL COMMENT '上一版本id，首版为 NULL',
    version_number      INT             NOT NULL DEFAULT 1 COMMENT '版本号，同 scope+key 内自增',
    source_type         VARCHAR(16)     NOT NULL COMMENT 'BOOTSTRAP / PATCH / MANUAL',
    source_op_id        BIGINT          DEFAULT NULL COMMENT 'PATCH 时记 schema_patch.id；BOOTSTRAP 时记 execution_id；MANUAL 为空',
    created_by          BIGINT          DEFAULT NULL COMMENT '触发变更的用户id',
    created_at          DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_scope_key_version (scope_id, config_key, version_number),
    KEY idx_parent (parent_version_id),
    KEY idx_created_at (created_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = 'Schema 版本历史链，每次变更追加不删除';

-- wiki_page 记录该页面创建/最近更新时遵循的 Schema 版本
-- Schema 升级后已有页面不重写，由 Lint 产出"遗留页面迁移报告"走标准 Ingest 迁移
ALTER TABLE wiki_page ADD COLUMN schema_version BIGINT DEFAULT NULL COMMENT '指向 schema_config_version.id，记录本页遵循的 Schema 版本';
CREATE INDEX idx_wiki_page_schema_version ON wiki_page(schema_version);
