package org.cn.liuwt.llmwiki.facade.model;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class EditStepInfo {
    private Long id;
    private Long sessionId;
    private Integer stepNumber;
    private String selectedLines;
    private String instruction;
    private String diffRemoved;
    private String diffAdded;
    private LocalDateTime createdAt;
}
