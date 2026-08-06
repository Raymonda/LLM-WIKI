package org.cn.liuwt.llmwiki.facade.model;

import lombok.Data;

import java.util.List;

@Data
public class ModifyPreCheckResponse {
    private boolean hasConflict;
    private List<ConflictItem> conflicts;

    @Data
    public static class ConflictItem {
        private String rule;
        private String description;
        private String severity;
        private String section;
    }
}
