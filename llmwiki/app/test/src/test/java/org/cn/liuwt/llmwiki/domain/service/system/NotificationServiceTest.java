package org.cn.liuwt.llmwiki.domain.service.system;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.cn.liuwt.llmwiki.common.dal.dataobject.NotificationDO;
import org.cn.liuwt.llmwiki.common.dal.dataobject.ScopeDO;
import org.cn.liuwt.llmwiki.common.dal.dataobject.ScopeMemberDO;
import org.cn.liuwt.llmwiki.common.dal.mapper.NotificationMapper;
import org.cn.liuwt.llmwiki.common.dal.mapper.ScopeMapper;
import org.cn.liuwt.llmwiki.common.dal.mapper.ScopeMemberMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class NotificationServiceTest {

    private NotificationService service;
    private NotificationMapper notificationMapper;
    private ScopeMapper scopeMapper;
    private ScopeMemberMapper scopeMemberMapper;

    @BeforeAll
    static void initTableInfo() {
        TableInfoHelper.initTableInfo(
            new MapperBuilderAssistant(new MybatisConfiguration(), ""), ScopeMemberDO.class);
    }

    @BeforeEach
    void setUp() {
        service = new NotificationService();
        notificationMapper = mock(NotificationMapper.class);
        scopeMapper = mock(ScopeMapper.class);
        scopeMemberMapper = mock(ScopeMemberMapper.class);
        ReflectionTestUtils.setField(service, "notificationMapper", notificationMapper);
        ReflectionTestUtils.setField(service, "scopeMapper", scopeMapper);
        ReflectionTestUtils.setField(service, "scopeMemberMapper", scopeMemberMapper);
    }

    private static ScopeDO scopeWithOwner(Long scopeId, Long ownerId) {
        ScopeDO scope = new ScopeDO();
        scope.setId(scopeId);
        scope.setOwnerId(ownerId);
        return scope;
    }

    private static ScopeMemberDO member(Long scopeId, Long userId, String role) {
        ScopeMemberDO member = new ScopeMemberDO();
        member.setScopeId(scopeId);
        member.setUserId(userId);
        member.setRole(role);
        return member;
    }

    @Test
    void shouldDeliverPersonalNotificationToSubmitter() {
        service.createPersonalNotification(42L, "ingest_completed", "t", "c", 7L, null, 100L);

        ArgumentCaptor<NotificationDO> captor = ArgumentCaptor.forClass(NotificationDO.class);
        verify(notificationMapper).insert(captor.capture());
        assertEquals(42L, captor.getValue().getUserId());
        assertEquals(7L, captor.getValue().getScopeId());
        assertEquals(100L, captor.getValue().getExecutionId());
        verify(scopeMapper, never()).selectById(any(Long.class));
    }

    @Test
    void shouldFallbackToScopeOwnerWhenSubmitterMissing() {
        when(scopeMapper.selectById(7L)).thenReturn(scopeWithOwner(7L, 9L));

        service.createPersonalNotification(null, "ingest_completed", "t", "c", 7L, null, 100L);

        ArgumentCaptor<NotificationDO> captor = ArgumentCaptor.forClass(NotificationDO.class);
        verify(notificationMapper).insert(captor.capture());
        assertEquals(9L, captor.getValue().getUserId());
    }

    @Test
    void shouldSkipPersonalNotificationWhenNoRecipientResolvable() {
        when(scopeMapper.selectById(7L)).thenReturn(null);

        service.createPersonalNotification(null, "ingest_completed", "t", "c", 7L, null, 100L);

        verify(notificationMapper, never()).insert(any(NotificationDO.class));
    }

    @Test
    void shouldFanOutScopeNotificationToOwnerAdminEditorOnly() {
        when(scopeMapper.selectById(7L)).thenReturn(scopeWithOwner(7L, 9L));
        when(scopeMemberMapper.selectList(any())).thenReturn(List.of(
            member(7L, 9L, "owner"),
            member(7L, 11L, "admin"),
            member(7L, 12L, "editor"),
            member(7L, 13L, "viewer")
        ));

        service.createScopeNotification(7L, "budget_exceeded", "t", "c", null, null);

        ArgumentCaptor<NotificationDO> captor = ArgumentCaptor.forClass(NotificationDO.class);
        verify(notificationMapper, times(3)).insert(captor.capture());
        List<Long> recipients = captor.getAllValues().stream().map(NotificationDO::getUserId).toList();
        assertEquals(List.of(9L, 11L, 12L), recipients);
        assertTrue(captor.getAllValues().stream().allMatch(n -> n.getScopeId().equals(7L)));
    }

    @Test
    void shouldDeliverScopeNotificationToOwnerWhenNoMemberRows() {
        when(scopeMapper.selectById(7L)).thenReturn(scopeWithOwner(7L, 9L));
        when(scopeMemberMapper.selectList(any())).thenReturn(List.of());

        service.createScopeNotification(7L, "budget_warning", "t", "c", null, null);

        ArgumentCaptor<NotificationDO> captor = ArgumentCaptor.forClass(NotificationDO.class);
        verify(notificationMapper, times(1)).insert(captor.capture());
        assertEquals(9L, captor.getValue().getUserId());
    }

    @Test
    void shouldContinueFanOutWhenSingleRecipientFails() {
        when(scopeMapper.selectById(7L)).thenReturn(scopeWithOwner(7L, 9L));
        when(scopeMemberMapper.selectList(any())).thenReturn(List.of(member(7L, 11L, "admin")));
        when(notificationMapper.insert(any(NotificationDO.class)))
            .thenThrow(new RuntimeException("db down"))
            .thenReturn(1);

        service.createScopeNotification(7L, "budget_exceeded", "t", "c", null, null);

        verify(notificationMapper, times(2)).insert(any(NotificationDO.class));
    }

    @Test
    void shouldDeliverScopeNotificationToOwnerWhenMemberQueryFails() {
        when(scopeMapper.selectById(7L)).thenReturn(scopeWithOwner(7L, 9L));
        when(scopeMemberMapper.selectList(any())).thenThrow(new RuntimeException("db down"));

        service.createScopeNotification(7L, "budget_exceeded", "t", "c", null, null);

        ArgumentCaptor<NotificationDO> captor = ArgumentCaptor.forClass(NotificationDO.class);
        verify(notificationMapper).insert(captor.capture());
        assertEquals(9L, captor.getValue().getUserId());
    }

    @Test
    void shouldSkipScopeNotificationWhenScopeIdNull() {
        service.createScopeNotification(null, "budget_exceeded", "t", "c", null, null);

        verify(notificationMapper, never()).insert(any(NotificationDO.class));
    }
}
