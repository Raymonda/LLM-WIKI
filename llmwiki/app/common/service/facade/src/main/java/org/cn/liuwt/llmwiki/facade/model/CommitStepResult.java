package org.cn.liuwt.llmwiki.facade.model;

import lombok.Data;

@Data
public class CommitStepResult {

    private EditSessionInfo session;

    private boolean versionBehind;
}
