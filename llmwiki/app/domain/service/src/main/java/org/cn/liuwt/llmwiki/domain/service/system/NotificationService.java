package org.cn.liuwt.llmwiki.domain.service.system;

import lombok.extern.slf4j.Slf4j;
import org.cn.liuwt.llmwiki.common.dal.dataobject.NotificationDO;
import org.cn.liuwt.llmwiki.common.dal.dataobject.ScopeDO;
import org.cn.liuwt.llmwiki.common.dal.dataobject.ScopeMemberDO;
import org.cn.liuwt.llmwiki.common.dal.mapper.NotificationMapper;
import org.cn.liuwt.llmwiki.common.dal.mapper.ScopeMapper;
import org.cn.liuwt.llmwiki.common.dal.mapper.ScopeMemberMapper;
import org.cn.liuwt.llmwiki.facade.model.NotificationInfo;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

@Slf4j
@Service
public class NotificationService {

    private static final Set<String> SCOPE_AUDIENCE_ROLES = Set.of("owner", "admin", "editor");

    @Autowired
    private NotificationMapper notificationMapper;

    @Autowired
    private ScopeMapper scopeMapper;

    @Autowired
    private ScopeMemberMapper scopeMemberMapper;

    public void createNotification(Long userId, String type, String title, String content, Long scopeId, Long relatedPageId) {
        createNotification(userId, type, title, content, scopeId, relatedPageId, null);
    }

    public void createNotification(Long userId, String type, String title, String content, Long scopeId, Long relatedPageId, Long executionId) {
        createNotification(userId, type, title, content, scopeId, relatedPageId, executionId, null);
    }

    public void createNotification(Long userId, String type, String title, String content,
                                   Long scopeId, Long relatedPageId, Long executionId, Long batchId) {
        NotificationDO notification = new NotificationDO();
        notification.setUserId(userId);
        notification.setType(type);
        notification.setTitle(title);
        notification.setContent(content);
        notification.setScopeId(scopeId);
        notification.setRelatedPageId(relatedPageId);
        notification.setExecutionId(executionId);
        notification.setBatchId(batchId);
        notification.setIsRead(0);
        notificationMapper.insert(notification);
    }

    public void createPersonalNotification(Long userId, String type, String title, String content,
                                           Long scopeId, Long relatedPageId, Long executionId) {
        Long recipient = userId != null ? userId : resolveScopeOwner(scopeId);
        if (recipient == null) {
            log.warn("Notification skipped, no recipient resolved: type={}, scopeId={}", type, scopeId);
            return;
        }
        createNotification(recipient, type, title, content, scopeId, relatedPageId, executionId);
    }

    public void createScopeNotification(Long scopeId, String type, String title, String content,
                                        Long relatedPageId, Long executionId) {
        if (scopeId == null) {
            return;
        }
        Set<Long> recipients = new LinkedHashSet<>();
        Long ownerId = resolveScopeOwner(scopeId);
        if (ownerId != null) {
            recipients.add(ownerId);
        }
        try {
            scopeMemberMapper.selectList(new LambdaQueryWrapper<ScopeMemberDO>()
                    .eq(ScopeMemberDO::getScopeId, scopeId))
                .stream()
                .filter(m -> SCOPE_AUDIENCE_ROLES.contains(m.getRole()))
                .forEach(m -> recipients.add(m.getUserId()));
        } catch (Exception e) {
            log.error("Failed to list scope members for notification fan-out: scopeId={}", scopeId, e);
        }
        for (Long recipient : recipients) {
            try {
                createNotification(recipient, type, title, content, scopeId, relatedPageId, executionId);
            } catch (Exception e) {
                log.error("Failed to deliver scope notification: type={}, scopeId={}, userId={}", type, scopeId, recipient, e);
            }
        }
    }

    private Long resolveScopeOwner(Long scopeId) {
        if (scopeId == null) {
            return null;
        }
        try {
            ScopeDO scope = scopeMapper.selectById(scopeId);
            return scope != null ? scope.getOwnerId() : null;
        } catch (Exception e) {
            log.error("Failed to resolve scope owner: scopeId={}", scopeId, e);
            return null;
        }
    }

    public List<NotificationInfo> listNotifications(Long userId) {
        List<NotificationDO> notifications = notificationMapper.selectList(
            new LambdaQueryWrapper<NotificationDO>()
                .eq(NotificationDO::getUserId, userId)
                .orderByDesc(NotificationDO::getCreatedAt)
                .last("LIMIT 50")
        );
        return notifications.stream().map(this::toInfo).toList();
    }

    public int getUnreadCount(Long userId) {
        return Math.toIntExact(notificationMapper.selectCount(
            new LambdaQueryWrapper<NotificationDO>()
                .eq(NotificationDO::getUserId, userId)
                .eq(NotificationDO::getIsRead, 0)
        ));
    }

    public void markAsRead(Long notificationId, Long userId) {
        NotificationDO notification = notificationMapper.selectById(notificationId);
        if (notification != null && notification.getUserId().equals(userId)) {
            notification.setIsRead(1);
            notificationMapper.updateById(notification);
        }
    }

    public void markAllAsRead(Long userId) {
        List<NotificationDO> unread = notificationMapper.selectList(
            new LambdaQueryWrapper<NotificationDO>()
                .eq(NotificationDO::getUserId, userId)
                .eq(NotificationDO::getIsRead, 0)
        );
        for (NotificationDO notification : unread) {
            notification.setIsRead(1);
            notificationMapper.updateById(notification);
        }
    }

    public void deleteNotification(Long notificationId, Long userId) {
        NotificationDO notification = notificationMapper.selectById(notificationId);
        if (notification != null && notification.getUserId().equals(userId)) {
            notificationMapper.deleteById(notificationId);
        }
    }

    private NotificationInfo toInfo(NotificationDO notificationDO) {
        NotificationInfo info = new NotificationInfo();
        info.setId(notificationDO.getId());
        info.setType(notificationDO.getType());
        info.setTitle(notificationDO.getTitle());
        info.setContent(notificationDO.getContent());
        info.setScopeId(notificationDO.getScopeId());
        info.setRelatedPageId(notificationDO.getRelatedPageId());
        info.setExecutionId(notificationDO.getExecutionId());
        info.setBatchId(notificationDO.getBatchId());
        info.setIsRead(notificationDO.getIsRead());
        info.setCreatedAt(notificationDO.getCreatedAt() != null ? notificationDO.getCreatedAt().toString() : "");
        return info;
    }
}