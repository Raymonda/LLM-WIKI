package org.cn.liuwt.llmwiki.web.controller;

import org.cn.liuwt.llmwiki.common.util.result.Result;
import org.cn.liuwt.llmwiki.domain.service.system.NotificationService;
import org.cn.liuwt.llmwiki.facade.model.NotificationInfo;
import org.cn.liuwt.llmwiki.web.security.JwtTokenProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/notifications")
public class NotificationController {

    @Autowired
    private NotificationService notificationService;

    @Autowired
    private JwtTokenProvider jwtTokenProvider;

    @GetMapping
    public Result<List<NotificationInfo>> listNotifications() {
        Long userId = jwtTokenProvider.getCurrentUserId();
        List<NotificationInfo> notifications = notificationService.listNotifications(userId);
        return Result.success(notifications);
    }

    @GetMapping("/unread-count")
    public Result<Integer> getUnreadCount() {
        Long userId = jwtTokenProvider.getCurrentUserId();
        int count = notificationService.getUnreadCount(userId);
        return Result.success(count);
    }

    @PutMapping("/{id}/read")
    public Result<Void> markAsRead(@PathVariable Long id) {
        Long userId = jwtTokenProvider.getCurrentUserId();
        notificationService.markAsRead(id, userId);
        return Result.success();
    }

    @PutMapping("/read-all")
    public Result<Void> markAllAsRead() {
        Long userId = jwtTokenProvider.getCurrentUserId();
        notificationService.markAllAsRead(userId);
        return Result.success();
    }

    @DeleteMapping("/{id}")
    public Result<Void> deleteNotification(@PathVariable Long id) {
        Long userId = jwtTokenProvider.getCurrentUserId();
        notificationService.deleteNotification(id, userId);
        return Result.success();
    }
}