package org.cn.liuwt.llmwiki.domain.service.system;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.cn.liuwt.llmwiki.common.dal.dataobject.ScopeBudgetDO;
import org.cn.liuwt.llmwiki.common.dal.dataobject.ScopeDO;
import org.cn.liuwt.llmwiki.common.dal.dataobject.UserDO;
import org.cn.liuwt.llmwiki.common.dal.mapper.ScopeBudgetMapper;
import org.cn.liuwt.llmwiki.common.dal.mapper.ScopeMapper;
import org.cn.liuwt.llmwiki.common.dal.mapper.UserMapper;
import org.cn.liuwt.llmwiki.common.util.exception.BusinessException;
import org.cn.liuwt.llmwiki.common.util.exception.ErrorCode;
import org.cn.liuwt.llmwiki.domain.model.system.UserModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

@Service
public class UserServiceImpl implements UserService {

    private static final Set<String> VALID_SYSTEM_ROLES = Set.of("admin", "user");
    private static final Set<String> VALID_STATUSES = Set.of("active", "disabled");

    @Autowired
    private UserMapper userMapper;

    @Autowired
    private ScopeBudgetMapper scopeBudgetMapper;

    @Autowired
    private ScopeMapper scopeMapper;

    @Override
    public UserModel getUserByUsername(String username) {
        UserDO userDO = userMapper.selectOne(
            new LambdaQueryWrapper<UserDO>().eq(UserDO::getUsername, username)
        );
        if (userDO == null) {
            return null;
        }
        return toModel(userDO);
    }

    @Override
    public UserModel getUserById(Long id) {
        UserDO userDO = userMapper.selectById(id);
        if (userDO == null) {
            return null;
        }
        return toModel(userDO);
    }

    @Override
    public UserModel createUser(UserModel userModel) {
        UserDO userDO = toDO(userModel);
        if (userDO.getStatus() == null) {
            userDO.setStatus("active");
        }
        if (userDO.getRole() == null) {
            userDO.setRole("user");
        }
        userMapper.insert(userDO);

        ScopeDO personalScope = new ScopeDO();
        personalScope.setName(userDO.getUsername() + "的个人知识库");
        personalScope.setDescription("个人知识库");
        personalScope.setType("personal");
        personalScope.setOwnerId(userDO.getId());
        personalScope.setMonthlyBudget(1000000);
        personalScope.setDefaultApproval("auto");
        personalScope.setMaxFileSize(10);
        personalScope.setMaxConcurrent(25);
        personalScope.setVisibility("private");
        scopeMapper.insert(personalScope);

        userDO.setScopeId(personalScope.getId());
        userMapper.updateById(userDO);

        ScopeBudgetDO budget = new ScopeBudgetDO();
        budget.setScopeId(personalScope.getId());
        budget.setMonthlyBudget(1000000);
        budget.setUsedTokens(0);
        budget.setResetDate(java.time.LocalDateTime.now().toLocalDate().withDayOfMonth(1).plusMonths(1).atStartOfDay());
        scopeBudgetMapper.insert(budget);

        return toModel(userDO);
    }

    @Override
    public IPage<UserModel> listUsers(String keyword, String role, String status, int page, int size) {
        Page<UserDO> pageReq = new Page<>(Math.max(page, 1), Math.max(size, 1));
        LambdaQueryWrapper<UserDO> wrapper = new LambdaQueryWrapper<>();
        if (keyword != null && !keyword.isBlank()) {
            String like = keyword.trim();
            wrapper.and(w -> w.like(UserDO::getUsername, like).or().like(UserDO::getEmail, like));
        }
        if (role != null && !role.isBlank()) {
            wrapper.eq(UserDO::getRole, role);
        }
        if (status != null && !status.isBlank()) {
            wrapper.eq(UserDO::getStatus, status);
        }
        wrapper.orderByDesc(UserDO::getId);
        IPage<UserDO> result = userMapper.selectPage(pageReq, wrapper);
        Page<UserModel> modelPage = new Page<>(result.getCurrent(), result.getSize(), result.getTotal());
        List<UserModel> records = new ArrayList<>();
        for (UserDO userDO : result.getRecords()) {
            records.add(toModel(userDO));
        }
        modelPage.setRecords(records);
        return modelPage;
    }

    @Override
    public List<UserModel> searchUsers(String keyword, int limit) {
        int capped = Math.min(Math.max(limit, 1), 20);
        LambdaQueryWrapper<UserDO> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(UserDO::getStatus, "active");
        if (keyword != null && !keyword.isBlank()) {
            String like = keyword.trim();
            wrapper.and(w -> w.like(UserDO::getUsername, like).or().like(UserDO::getEmail, like));
        }
        wrapper.orderByAsc(UserDO::getUsername);
        wrapper.last("LIMIT " + capped);
        List<UserDO> list = userMapper.selectList(wrapper);
        List<UserModel> models = new ArrayList<>();
        for (UserDO userDO : list) {
            models.add(toModel(userDO));
        }
        return models;
    }

    @Override
    public void updateStatus(Long id, String status) {
        if (!VALID_STATUSES.contains(status)) {
            throw new BusinessException(ErrorCode.USER_INVALID_STATUS);
        }
        UserDO userDO = userMapper.selectById(id);
        if (userDO == null) {
            throw new BusinessException(ErrorCode.USER_NOT_FOUND);
        }
        if ("disabled".equals(status) && "admin".equals(userDO.getRole()) && countByRole("admin") <= 1) {
            throw new BusinessException(ErrorCode.USER_LAST_ADMIN_PROTECTED);
        }
        userDO.setStatus(status);
        userMapper.updateById(userDO);
    }

    @Override
    public void updateRole(Long id, String role) {
        if (!VALID_SYSTEM_ROLES.contains(role)) {
            throw new BusinessException(ErrorCode.USER_INVALID_ROLE);
        }
        UserDO userDO = userMapper.selectById(id);
        if (userDO == null) {
            throw new BusinessException(ErrorCode.USER_NOT_FOUND);
        }
        if ("admin".equals(userDO.getRole()) && !"admin".equals(role) && countByRole("admin") <= 1) {
            throw new BusinessException(ErrorCode.USER_LAST_ADMIN_PROTECTED);
        }
        userDO.setRole(role);
        userMapper.updateById(userDO);
    }

    @Override
    public void updatePasswordHash(Long id, String passwordHash) {
        UserDO userDO = userMapper.selectById(id);
        if (userDO == null) {
            throw new BusinessException(ErrorCode.USER_NOT_FOUND);
        }
        userDO.setPasswordHash(passwordHash);
        userMapper.updateById(userDO);
    }

    @Override
    public long countByRole(String role) {
        return userMapper.selectCount(new LambdaQueryWrapper<UserDO>().eq(UserDO::getRole, role));
    }

    private UserModel toModel(UserDO userDO) {
        UserModel model = new UserModel();
        model.setId(userDO.getId());
        model.setUsername(userDO.getUsername());
        model.setPasswordHash(userDO.getPasswordHash());
        model.setEmail(userDO.getEmail());
        model.setAvatar(userDO.getAvatar());
        model.setRole(userDO.getRole());
        model.setStatus(userDO.getStatus());
        model.setScopeId(userDO.getScopeId());
        model.setCreatedAt(userDO.getCreatedAt());
        model.setUpdatedAt(userDO.getUpdatedAt());
        return model;
    }

    private UserDO toDO(UserModel model) {
        UserDO userDO = new UserDO();
        userDO.setId(model.getId());
        userDO.setUsername(model.getUsername());
        userDO.setPasswordHash(model.getPasswordHash());
        userDO.setEmail(model.getEmail());
        userDO.setAvatar(model.getAvatar());
        userDO.setRole(model.getRole());
        userDO.setStatus(model.getStatus());
        userDO.setScopeId(model.getScopeId());
        return userDO;
    }
}
