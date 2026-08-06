package org.cn.liuwt.llmwiki.web.controller;

import org.cn.liuwt.llmwiki.common.dal.dataobject.UserDO;
import org.cn.liuwt.llmwiki.common.dal.mapper.UserMapper;
import org.cn.liuwt.llmwiki.common.util.exception.ErrorCode;
import org.cn.liuwt.llmwiki.common.util.result.Result;
import org.cn.liuwt.llmwiki.domain.model.system.AuditLogModel;
import org.cn.liuwt.llmwiki.domain.service.system.AuditLogService;
import org.cn.liuwt.llmwiki.domain.service.system.ScopeService;
import org.cn.liuwt.llmwiki.facade.model.AuditLogInfo;
import org.cn.liuwt.llmwiki.facade.model.PageResult;
import org.cn.liuwt.llmwiki.web.security.JwtTokenProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/audit")
public class AuditLogController {

    @Autowired
    private AuditLogService auditLogService;

    @Autowired
    private ScopeService scopeService;

    @Autowired
    private JwtTokenProvider jwtTokenProvider;

    @Autowired
    private UserMapper userMapper;

    @GetMapping("/logs")
    public Result<PageResult<AuditLogInfo>> queryLogs(
            @RequestParam Long scopeId,
            @RequestParam(required = false) String action,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        Long userId = jwtTokenProvider.getCurrentUserId();
        if (!scopeService.isOwnerOrAdmin(scopeId, userId) && !isSystemAdmin(userId)) {
            return Result.failed(ErrorCode.SCOPE_PERMISSION_VIEW_AUDIT_LOG);
        }
        PageResult<AuditLogModel> modelResult = auditLogService.queryLogs(scopeId, action, from, to, page, size);
        PageResult<AuditLogInfo> infoResult = new PageResult<>();
        infoResult.setItems(modelResult.getItems().stream().map(this::toInfo).toList());
        infoResult.setTotal(modelResult.getTotal());
        infoResult.setPage(modelResult.getPage());
        infoResult.setSize(modelResult.getSize());
        infoResult.setTotalPages(modelResult.getTotalPages());
        return Result.success(infoResult);
    }

    @GetMapping("/stats")
    public Result<java.util.Map<String, Object>> getStats(@RequestParam Long scopeId) {
        Long userId = jwtTokenProvider.getCurrentUserId();
        if (!scopeService.isOwnerOrAdmin(scopeId, userId) && !isSystemAdmin(userId)) {
            return Result.failed(ErrorCode.SCOPE_PERMISSION_VIEW_AUDIT_STATS);
        }
        long total = auditLogService.countByScope(scopeId);
        java.util.Map<String, Object> stats = new java.util.HashMap<>();
        stats.put("totalLogs", total);
        stats.put("scopeId", scopeId);
        return Result.success(stats);
    }

    private boolean isSystemAdmin(Long userId) {
        if (userId == null) return false;
        UserDO user = userMapper.selectById(userId);
        return user != null && "admin".equals(user.getRole());
    }

    private AuditLogInfo toInfo(AuditLogModel model) {
        AuditLogInfo info = new AuditLogInfo();
        info.setId(model.getId());
        info.setActorUserId(model.getActorUserId());
        info.setActorUsername(model.getActorUsername());
        info.setAction(model.getAction());
        info.setTargetType(model.getTargetType());
        info.setTargetId(model.getTargetId());
        info.setTargetName(model.getTargetName());
        info.setScopeId(model.getScopeId());
        info.setDetailJson(model.getDetailJson());
        info.setIpAddress(model.getIpAddress());
        info.setCreatedAt(model.getCreatedAt());
        return info;
    }
}
