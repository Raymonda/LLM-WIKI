package org.cn.liuwt.llmwiki.domain.service.system;

import org.cn.liuwt.llmwiki.domain.model.system.ScopeModel;
import org.cn.liuwt.llmwiki.domain.model.system.ScopeMemberModel;
import org.cn.liuwt.llmwiki.common.dal.dataobject.ScopeJoinRequestDO;

import java.util.List;

public interface ScopeService {
    ScopeModel createScope(ScopeModel scopeModel);
    ScopeModel getScope(Long scopeId);
    List<ScopeModel> listScopesByUserId(Long userId);
    void updateScope(ScopeModel scopeModel);
    void deleteScope(Long scopeId, Long operatorId);

    ScopeMemberModel addMember(Long scopeId, Long userId, String role, Long operatorId);
    void removeMember(Long scopeId, Long userId, Long operatorId);
    void updateMemberRole(Long scopeId, Long userId, String newRole, Long operatorId);
    List<ScopeMemberModel> listMembers(Long scopeId);

    String getMemberRole(Long scopeId, Long userId);
    boolean hasPermission(Long scopeId, Long userId, String permission);
    boolean isOwnerOrAdmin(Long scopeId, Long userId);
    boolean canEdit(Long scopeId, Long userId);
    boolean canView(Long scopeId, Long userId);

    List<ScopeModel> listOrgVisibleScopes();
    void createJoinRequest(Long scopeId, Long userId, String message);
    List<ScopeJoinRequestDO> listPendingRequests(Long scopeId);
    void reviewJoinRequest(Long requestId, Long scopeId, Long reviewerId, boolean approve, String reviewMessage);

    void updateLanguage(Long scopeId, String language);
    String getLanguage(Long scopeId);
}