package org.cn.liuwt.llmwiki.domain.service.system;

import org.cn.liuwt.llmwiki.domain.model.system.AuditLogModel;
import org.cn.liuwt.llmwiki.facade.model.PageResult;

public interface AuditLogService {
    void log(AuditLogModel model);
    PageResult<AuditLogModel> queryLogs(Long scopeId, String action, String from, String to, int page, int size);
    long countByScope(Long scopeId);
}
