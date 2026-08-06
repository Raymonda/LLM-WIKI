package org.cn.liuwt.llmwiki.domain.service.system;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.cn.liuwt.llmwiki.common.dal.dataobject.AuditLogDO;
import org.cn.liuwt.llmwiki.common.dal.mapper.AuditLogMapper;
import org.cn.liuwt.llmwiki.domain.model.system.AuditLogModel;
import org.cn.liuwt.llmwiki.facade.model.PageResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

@Service
public class AuditLogServiceImpl implements AuditLogService {

    private static final Logger log = LoggerFactory.getLogger(AuditLogServiceImpl.class);

    @Autowired
    private AuditLogMapper auditLogMapper;

    @Override
    @Async
    public void log(AuditLogModel model) {
        try {
            AuditLogDO logDO = toDO(model);
            auditLogMapper.insert(logDO);
        } catch (Exception e) {
            log.error("审计日志写入失败: action={}, targetType={}, actorUserId={}",
                    model.getAction(), model.getTargetType(), model.getActorUserId(), e);
        }
    }

    @Override
    public PageResult<AuditLogModel> queryLogs(Long scopeId, String action, String from, String to, int page, int size) {
        LambdaQueryWrapper<AuditLogDO> wrapper = new LambdaQueryWrapper<>();
        if (scopeId != null) {
            wrapper.eq(AuditLogDO::getScopeId, scopeId);
        }
        if (action != null && !action.isBlank()) {
            wrapper.eq(AuditLogDO::getAction, action);
        }
        if (from != null && !from.isBlank()) {
            wrapper.ge(AuditLogDO::getCreatedAt, LocalDateTime.parse(from, DateTimeFormatter.ISO_DATE_TIME));
        }
        if (to != null && !to.isBlank()) {
            wrapper.le(AuditLogDO::getCreatedAt, LocalDateTime.parse(to, DateTimeFormatter.ISO_DATE_TIME));
        }
        wrapper.orderByDesc(AuditLogDO::getCreatedAt);

        Page<AuditLogDO> pageParam = new Page<>(page, size);
        Page<AuditLogDO> result = auditLogMapper.selectPage(pageParam, wrapper);

        PageResult<AuditLogModel> pageResult = new PageResult<>();
        pageResult.setItems(result.getRecords().stream().map(this::toModel).toList());
        pageResult.setTotal(result.getTotal());
        pageResult.setPage(result.getCurrent());
        pageResult.setSize(result.getSize());
        pageResult.setTotalPages(result.getPages());
        return pageResult;
    }

    @Override
    public long countByScope(Long scopeId) {
        return auditLogMapper.selectCount(
                new LambdaQueryWrapper<AuditLogDO>().eq(AuditLogDO::getScopeId, scopeId));
    }

    private AuditLogDO toDO(AuditLogModel model) {
        AuditLogDO logDO = new AuditLogDO();
        logDO.setActorUserId(model.getActorUserId());
        logDO.setActorUsername(model.getActorUsername());
        logDO.setAction(model.getAction());
        logDO.setTargetType(model.getTargetType());
        logDO.setTargetId(model.getTargetId());
        logDO.setTargetName(model.getTargetName());
        logDO.setScopeId(model.getScopeId());
        logDO.setDetailJson(model.getDetailJson());
        logDO.setIpAddress(model.getIpAddress());
        logDO.setUserAgent(model.getUserAgent());
        return logDO;
    }

    private AuditLogModel toModel(AuditLogDO logDO) {
        AuditLogModel model = new AuditLogModel();
        model.setId(logDO.getId());
        model.setActorUserId(logDO.getActorUserId());
        model.setActorUsername(logDO.getActorUsername());
        model.setAction(logDO.getAction());
        model.setTargetType(logDO.getTargetType());
        model.setTargetId(logDO.getTargetId());
        model.setTargetName(logDO.getTargetName());
        model.setScopeId(logDO.getScopeId());
        model.setDetailJson(logDO.getDetailJson());
        model.setIpAddress(logDO.getIpAddress());
        model.setUserAgent(logDO.getUserAgent());
        model.setCreatedAt(logDO.getCreatedAt());
        return model;
    }
}
