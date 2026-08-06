package org.cn.liuwt.llmwiki.facade.model;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

@Data
public class HealthInfo {
    private String status;
    private LocalDateTime lastCheckedAt;
    private List<String> issues;
    private List<String> suggestions;
}