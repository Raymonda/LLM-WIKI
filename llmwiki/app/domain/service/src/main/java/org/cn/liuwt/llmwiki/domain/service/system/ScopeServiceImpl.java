package org.cn.liuwt.llmwiki.domain.service.system;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.cn.liuwt.llmwiki.common.dal.dataobject.ScopeDO;
import org.cn.liuwt.llmwiki.common.dal.dataobject.ScopeMemberDO;
import org.cn.liuwt.llmwiki.common.dal.dataobject.ScopeJoinRequestDO;
import org.cn.liuwt.llmwiki.common.dal.mapper.ScopeMapper;
import org.cn.liuwt.llmwiki.common.dal.mapper.ScopeMemberMapper;
import org.cn.liuwt.llmwiki.common.dal.mapper.ScopeJoinRequestMapper;
import org.cn.liuwt.llmwiki.common.util.exception.BusinessException;
import org.cn.liuwt.llmwiki.common.util.exception.ErrorCode;
import org.cn.liuwt.llmwiki.domain.model.system.ScopeMemberModel;
import org.cn.liuwt.llmwiki.domain.model.system.ScopeModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import org.cn.liuwt.llmwiki.common.dal.dataobject.UserDO;
import org.cn.liuwt.llmwiki.common.dal.mapper.UserMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.time.LocalDateTime;

@Service
public class ScopeServiceImpl implements ScopeService {

    private static final Map<String, Set<String>> ROLE_PERMISSIONS = Map.of(
            "owner", Set.of("read", "write", "delete", "manage", "approve"),
            "admin", Set.of("read", "write", "delete", "manage", "approve"),
            "editor", Set.of("read", "write", "approve"),
            "viewer", Set.of("read")
    );

    @Autowired
    private ScopeMapper scopeMapper;

    @Autowired
    private ScopeMemberMapper scopeMemberMapper;

    @Autowired
    private ScopeJoinRequestMapper joinRequestMapper;

    @Autowired
    private UserMapper userMapper;

    @Override
    public ScopeModel createScope(ScopeModel scopeModel) {
        ScopeDO scopeDO = toScopeDO(scopeModel);
        scopeDO.setType("team");
        scopeDO.setVisibility("members_only");
        scopeDO.setDefaultApproval("confirm");
        scopeDO.setMonthlyBudget(10000000);
        scopeDO.setMaxFileSize(52428800);
        scopeDO.setMaxConcurrent(3);
        scopeMapper.insert(scopeDO);

        ScopeMemberDO ownerMember = new ScopeMemberDO();
        ownerMember.setScopeId(scopeDO.getId());
        ownerMember.setUserId(scopeDO.getOwnerId());
        ownerMember.setRole("owner");
        scopeMemberMapper.insert(ownerMember);

        return toScopeModel(scopeDO);
    }

    @Override
    public ScopeModel getScope(Long scopeId) {
        ScopeDO scopeDO = scopeMapper.selectById(scopeId);
        if (scopeDO == null) {
            throw new BusinessException(ErrorCode.SCOPE_NOT_FOUND);
        }
        return toScopeModel(scopeDO);
    }

    @Override
    public List<ScopeModel> listScopesByUserId(Long userId) {
        List<ScopeModel> result = new ArrayList<>();
        ScopeModel personalScope = buildPersonalScope(userId);
        result.add(personalScope);
        LambdaQueryWrapper<ScopeMemberDO> memberQuery = new LambdaQueryWrapper<ScopeMemberDO>()
                .eq(ScopeMemberDO::getUserId, userId);
        List<ScopeMemberDO> memberships = scopeMemberMapper.selectList(memberQuery);
        List<Long> scopeIds = memberships.stream().map(ScopeMemberDO::getScopeId).toList();
        if (!scopeIds.isEmpty()) {
            List<ScopeDO> scopes = scopeMapper.selectBatchIds(scopeIds);
            scopes.stream().map(this::toScopeModel).forEach(result::add);
        }
        return result;
    }

    private ScopeModel buildPersonalScope(Long userId) {
        UserDO user = userMapper.selectById(userId);
        ScopeModel personal = new ScopeModel();
        personal.setId(userId);
        personal.setName(user != null ? user.getUsername() + "的个人知识库" : "个人知识库");
        personal.setDescription("个人知识库");
        personal.setType("personal");
        personal.setOwnerId(userId);
        personal.setMonthlyBudget(1000000);
        personal.setDefaultApproval("auto");
        personal.setMaxFileSize(10485760);
        personal.setMaxConcurrent(1);
        return personal;
    }

    @Override
    public void updateScope(ScopeModel scopeModel) {
        ScopeDO scopeDO = toScopeDO(scopeModel);
        scopeMapper.updateById(scopeDO);
    }

    @Override
    public void deleteScope(Long scopeId, Long operatorId) {
        if (!isOwnerOrAdmin(scopeId, operatorId)) {
            throw new BusinessException(ErrorCode.SCOPE_PERMISSION_DELETE);
        }
        ScopeDO scope = scopeMapper.selectById(scopeId);
        if (scope == null) {
            throw new BusinessException(ErrorCode.SCOPE_NOT_FOUND);
        }
        if (!scope.getOwnerId().equals(operatorId)) {
            throw new BusinessException(ErrorCode.SCOPE_PERMISSION_DELETE_OWNER);
        }
        scopeMemberMapper.delete(new LambdaQueryWrapper<ScopeMemberDO>()
                .eq(ScopeMemberDO::getScopeId, scopeId));
        scopeMapper.deleteById(scopeId);
    }

    @Override
    public ScopeMemberModel addMember(Long scopeId, Long userId, String role, Long operatorId) {
        if (!isOwnerOrAdmin(scopeId, operatorId)) {
            throw new BusinessException(ErrorCode.SCOPE_PERMISSION_ADD_MEMBER);
        }
        if (!ROLE_PERMISSIONS.containsKey(role)) {
            throw new BusinessException(ErrorCode.SCOPE_INVALID_ROLE, role);
        }
        ScopeMemberDO existing = scopeMemberMapper.selectOne(new LambdaQueryWrapper<ScopeMemberDO>()
                .eq(ScopeMemberDO::getScopeId, scopeId)
                .eq(ScopeMemberDO::getUserId, userId));
        if (existing != null) {
            throw new BusinessException(ErrorCode.SCOPE_MEMBER_EXISTS);
        }
        ScopeMemberDO memberDO = new ScopeMemberDO();
        memberDO.setScopeId(scopeId);
        memberDO.setUserId(userId);
        memberDO.setRole(role);
        scopeMemberMapper.insert(memberDO);
        return toMemberModel(memberDO);
    }

    @Override
    public void removeMember(Long scopeId, Long userId, Long operatorId) {
        if (!isOwnerOrAdmin(scopeId, operatorId)) {
            throw new BusinessException(ErrorCode.SCOPE_PERMISSION_REMOVE_MEMBER);
        }
        ScopeDO scope = scopeMapper.selectById(scopeId);
        if (scope != null && scope.getOwnerId().equals(userId)) {
            throw new BusinessException(ErrorCode.SCOPE_CANNOT_REMOVE_OWNER);
        }
        scopeMemberMapper.delete(new LambdaQueryWrapper<ScopeMemberDO>()
                .eq(ScopeMemberDO::getScopeId, scopeId)
                .eq(ScopeMemberDO::getUserId, userId));
    }

    @Override
    public void updateMemberRole(Long scopeId, Long userId, String newRole, Long operatorId) {
        if (!isOwnerOrAdmin(scopeId, operatorId)) {
            throw new BusinessException(ErrorCode.SCOPE_PERMISSION_CHANGE_ROLE);
        }
        if (!ROLE_PERMISSIONS.containsKey(newRole)) {
            throw new BusinessException(ErrorCode.SCOPE_INVALID_ROLE, newRole);
        }
        ScopeDO scope = scopeMapper.selectById(scopeId);
        if (scope != null && scope.getOwnerId().equals(userId) && !"owner".equals(newRole)) {
            throw new BusinessException(ErrorCode.SCOPE_CANNOT_CHANGE_OWNER_ROLE);
        }
        ScopeMemberDO member = scopeMemberMapper.selectOne(new LambdaQueryWrapper<ScopeMemberDO>()
                .eq(ScopeMemberDO::getScopeId, scopeId)
                .eq(ScopeMemberDO::getUserId, userId));
        if (member == null) {
            throw new BusinessException(ErrorCode.SCOPE_MEMBER_NOT_FOUND);
        }
        member.setRole(newRole);
        scopeMemberMapper.updateById(member);
    }

    @Override
    public List<ScopeMemberModel> listMembers(Long scopeId) {
        List<ScopeMemberDO> members = scopeMemberMapper.selectList(
                new LambdaQueryWrapper<ScopeMemberDO>().eq(ScopeMemberDO::getScopeId, scopeId));
        return members.stream().map(this::toMemberModel).toList();
    }

    @Override
    public String getMemberRole(Long scopeId, Long userId) {
        if (scopeId.equals(userId)) {
            return "owner";
        }
        ScopeDO scope = scopeMapper.selectById(scopeId);
        if (scope != null && scope.getOwnerId().equals(userId)) {
            return "owner";
        }
        ScopeMemberDO member = scopeMemberMapper.selectOne(new LambdaQueryWrapper<ScopeMemberDO>()
                .eq(ScopeMemberDO::getScopeId, scopeId)
                .eq(ScopeMemberDO::getUserId, userId));
        return member != null ? member.getRole() : null;
    }

    @Override
    public boolean hasPermission(Long scopeId, Long userId, String permission) {
        String role = getMemberRole(scopeId, userId);
        if (role == null) {
            return false;
        }
        Set<String> permissions = ROLE_PERMISSIONS.get(role);
        return permissions != null && permissions.contains(permission);
    }

    @Override
    public boolean isOwnerOrAdmin(Long scopeId, Long userId) {
        String role = getMemberRole(scopeId, userId);
        return "owner".equals(role) || "admin".equals(role);
    }

    @Override
    public boolean canEdit(Long scopeId, Long userId) {
        return hasPermission(scopeId, userId, "write");
    }

    @Override
    public boolean canView(Long scopeId, Long userId) {
        return hasPermission(scopeId, userId, "read");
    }

    @Override
    public List<ScopeModel> listOrgVisibleScopes() {
        List<ScopeDO> scopes = scopeMapper.selectList(new LambdaQueryWrapper<ScopeDO>()
                .eq(ScopeDO::getType, "team")
                .eq(ScopeDO::getVisibility, "org"));
        return scopes.stream().map(this::toScopeModel).toList();
    }

    @Override
    public void createJoinRequest(Long scopeId, Long userId, String message) {
        ScopeMemberDO existing = scopeMemberMapper.selectOne(new LambdaQueryWrapper<ScopeMemberDO>()
                .eq(ScopeMemberDO::getScopeId, scopeId)
                .eq(ScopeMemberDO::getUserId, userId));
        if (existing != null) {
            throw new BusinessException(ErrorCode.SCOPE_ALREADY_MEMBER);
        }
        ScopeJoinRequestDO pending = joinRequestMapper.selectOne(new LambdaQueryWrapper<ScopeJoinRequestDO>()
                .eq(ScopeJoinRequestDO::getScopeId, scopeId)
                .eq(ScopeJoinRequestDO::getUserId, userId)
                .eq(ScopeJoinRequestDO::getStatus, "pending"));
        if (pending != null) {
            throw new BusinessException(ErrorCode.SCOPE_REQUEST_EXISTS);
        }
        ScopeJoinRequestDO request = new ScopeJoinRequestDO();
        request.setScopeId(scopeId);
        request.setUserId(userId);
        request.setMessage(message);
        request.setStatus("pending");
        joinRequestMapper.insert(request);
    }

    @Override
    public List<ScopeJoinRequestDO> listPendingRequests(Long scopeId) {
        return joinRequestMapper.selectList(new LambdaQueryWrapper<ScopeJoinRequestDO>()
                .eq(ScopeJoinRequestDO::getScopeId, scopeId)
                .eq(ScopeJoinRequestDO::getStatus, "pending")
                .orderByDesc(ScopeJoinRequestDO::getCreatedAt));
    }

    @Override
    public void reviewJoinRequest(Long requestId, Long scopeId, Long reviewerId, boolean approve, String reviewMessage) {
        ScopeJoinRequestDO request = joinRequestMapper.selectById(requestId);
        if (request == null || !request.getScopeId().equals(scopeId)) {
            throw new BusinessException(ErrorCode.SCOPE_REQUEST_NOT_FOUND);
        }
        if (!"pending".equals(request.getStatus())) {
            throw new BusinessException(ErrorCode.SCOPE_REQUEST_ALREADY_REVIEWED);
        }
        request.setStatus(approve ? "approved" : "rejected");
        request.setReviewerId(reviewerId);
        request.setReviewMessage(reviewMessage);
        request.setReviewedAt(LocalDateTime.now());
        joinRequestMapper.updateById(request);
        if (approve) {
            ScopeMemberDO member = new ScopeMemberDO();
            member.setScopeId(scopeId);
            member.setUserId(request.getUserId());
            member.setRole("viewer");
            scopeMemberMapper.insert(member);
        }
    }

    private ScopeModel toScopeModel(ScopeDO scopeDO) {
        ScopeModel model = new ScopeModel();
        model.setId(scopeDO.getId());
        model.setName(scopeDO.getName());
        model.setDescription(scopeDO.getDescription());
        model.setType(scopeDO.getType());
        model.setOwnerId(scopeDO.getOwnerId());
        model.setMonthlyBudget(scopeDO.getMonthlyBudget());
        model.setDefaultApproval(scopeDO.getDefaultApproval());
        model.setMaxFileSize(scopeDO.getMaxFileSize());
        model.setMaxConcurrent(scopeDO.getMaxConcurrent());
        model.setVisibility(scopeDO.getVisibility());
        model.setLanguage(scopeDO.getLanguage());
        model.setCreatedAt(scopeDO.getCreatedAt());
        model.setUpdatedAt(scopeDO.getUpdatedAt());
        return model;
    }

    private ScopeDO toScopeDO(ScopeModel model) {
        ScopeDO scopeDO = new ScopeDO();
        scopeDO.setId(model.getId());
        scopeDO.setName(model.getName());
        scopeDO.setDescription(model.getDescription());
        scopeDO.setType(model.getType());
        scopeDO.setOwnerId(model.getOwnerId());
        scopeDO.setMonthlyBudget(model.getMonthlyBudget());
        scopeDO.setDefaultApproval(model.getDefaultApproval());
        scopeDO.setMaxFileSize(model.getMaxFileSize());
        scopeDO.setMaxConcurrent(model.getMaxConcurrent());
        scopeDO.setUpstreamScopeIds(model.getUpstreamScopeIds());
        scopeDO.setVisibility(model.getVisibility());
        scopeDO.setLanguage(model.getLanguage());
        return scopeDO;
    }

    private ScopeMemberModel toMemberModel(ScopeMemberDO memberDO) {
        ScopeMemberModel model = new ScopeMemberModel();
        model.setId(memberDO.getId());
        model.setScopeId(memberDO.getScopeId());
        model.setUserId(memberDO.getUserId());
        model.setRole(memberDO.getRole());
        model.setJoinedAt(memberDO.getJoinedAt());
        return model;
    }

    @Override
    public void updateLanguage(Long scopeId, String language) {
        ScopeDO scopeDO = scopeMapper.selectById(scopeId);
        if (scopeDO == null) {
            throw new BusinessException(ErrorCode.SCOPE_NOT_FOUND);
        }
        scopeDO.setLanguage(language);
        scopeMapper.updateById(scopeDO);
    }

    @Override
    public String getLanguage(Long scopeId) {
        ScopeDO scopeDO = scopeMapper.selectById(scopeId);
        if (scopeDO == null || scopeDO.getLanguage() == null) {
            return "zh-CN";
        }
        return scopeDO.getLanguage();
    }
}