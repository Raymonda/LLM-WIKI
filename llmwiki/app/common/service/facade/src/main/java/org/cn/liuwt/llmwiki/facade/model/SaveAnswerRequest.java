package org.cn.liuwt.llmwiki.facade.model;

import lombok.Data;

@Data
public class SaveAnswerRequest {
    private String question;
    private String answer;
    private String sessionId;
}