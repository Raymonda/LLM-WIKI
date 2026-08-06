package org.cn.liuwt.llmwiki.facade.model;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 宪法规则 5：Schema 升级后已有页面不重写，由 Lint 产出"遗留页面迁移报告"。
 * 本 DTO 返回当前 scope 下仍遵循旧 Schema 版本或尚未打版本戳的页面清单。
 */
@Data
public class SchemaMigrationReport {

    /** 当前 scope 最新 Schema 版本 id（null 表示 scope 未完成冷启动）。 */
    private Long currentVersionId;

    /** 当前 scope 最新 Schema 版本号（为方便前端展示）。 */
    private Integer currentVersionNumber;

    /** 未打版本戳的页面数量（schema_version IS NULL，多为 V13 之前入库的历史页面）。 */
    private Integer untaggedCount;

    /** 遵循非当前版本的页面数量（schema_version != currentVersionId）。 */
    private Integer outdatedCount;

    /** 示例页面清单（最多 50 条），供 UI 展示。 */
    private List<LegacyPage> legacyPages;

    @Data
    public static class LegacyPage {
        private Long pageId;
        private String title;
        private String category;
        private Long schemaVersion;
        private Integer versionNumber;
        private LocalDateTime updatedAt;
    }
}
