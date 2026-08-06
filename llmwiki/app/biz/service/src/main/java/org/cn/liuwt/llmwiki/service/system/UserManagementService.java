package org.cn.liuwt.llmwiki.service.system;

import com.baomidou.mybatisplus.core.metadata.IPage;
import org.cn.liuwt.llmwiki.common.util.exception.BusinessException;
import org.cn.liuwt.llmwiki.common.util.exception.ErrorCode;
import org.cn.liuwt.llmwiki.domain.model.system.UserModel;
import org.cn.liuwt.llmwiki.domain.service.system.UserService;
import org.cn.liuwt.llmwiki.domain.service.wiki.WikiFileServiceImpl;
import org.cn.liuwt.llmwiki.facade.model.CreateUserRequest;
import org.cn.liuwt.llmwiki.facade.model.PageResult;
import org.cn.liuwt.llmwiki.facade.model.UserManageInfo;
import org.cn.liuwt.llmwiki.facade.model.UserSearchInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

@Service
public class UserManagementService {

    private static final Logger LOGGER = LoggerFactory.getLogger(UserManagementService.class);
    private static final Set<String> VALID_SYSTEM_ROLES = Set.of("admin", "user");
    private static final String TEMP_PASSWORD_ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZabcdefghjkmnpqrstuvwxyz23456789";
    private static final SecureRandom RANDOM = new SecureRandom();

    @Autowired
    private UserService userService;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private WikiFileServiceImpl wikiFileService;

    public PageResult<UserManageInfo> listUsers(String keyword, String role, String status, int page, int size) {
        IPage<UserModel> result = userService.listUsers(keyword, role, status, page, size);
        List<UserManageInfo> records = new ArrayList<>();
        for (UserModel model : result.getRecords()) {
            records.add(toManageInfo(model));
        }
        PageResult<UserManageInfo> pr = new PageResult<>();
        pr.setItems(records);
        pr.setTotal(result.getTotal());
        pr.setPage(result.getCurrent());
        pr.setSize(result.getSize());
        pr.setTotalPages(result.getPages());
        return pr;
    }

    public List<UserSearchInfo> searchUsers(String keyword, int limit) {
        List<UserModel> list = userService.searchUsers(keyword, limit);
        List<UserSearchInfo> result = new ArrayList<>();
        for (UserModel model : list) {
            UserSearchInfo info = new UserSearchInfo();
            info.setId(model.getId());
            info.setUserName(model.getUsername());
            info.setEmail(model.getEmail());
            result.add(info);
        }
        return result;
    }

    public UserManageInfo getUserById(Long id) {
        UserModel model = userService.getUserById(id);
        if (model == null) {
            throw new BusinessException(ErrorCode.USER_NOT_FOUND);
        }
        return toManageInfo(model);
    }

    public UserManageInfo adminCreateUser(CreateUserRequest request, String generatedPasswordHolder[]) {
        if (request == null || request.getUserName() == null || request.getUserName().isBlank()) {
            throw new BusinessException(ErrorCode.USER_INVALID_USERNAME);
        }
        String role = request.getRole() == null || request.getRole().isBlank() ? "user" : request.getRole().trim();
        if (!VALID_SYSTEM_ROLES.contains(role)) {
            throw new BusinessException(ErrorCode.USER_INVALID_ROLE);
        }
        UserModel existing = userService.getUserByUsername(request.getUserName().trim());
        if (existing != null) {
            throw new BusinessException(ErrorCode.USERNAME_EXISTS);
        }
        String rawPassword = request.getPassword();
        boolean generated = false;
        if (rawPassword == null || rawPassword.isBlank()) {
            rawPassword = generateTempPassword();
            generated = true;
        }
        UserModel newUser = new UserModel();
        newUser.setUsername(request.getUserName().trim());
        newUser.setEmail(request.getEmail());
        newUser.setPasswordHash(passwordEncoder.encode(rawPassword));
        newUser.setRole(role);
        newUser.setStatus("active");
        newUser.setConsentKnowledgePromotion(1);
        UserModel created = userService.createUser(newUser);
        wikiFileService.initWikiData(created.getScopeId());
        LOGGER.info("admin created user id={} username={} role={}", created.getId(), created.getUsername(), created.getRole());
        if (generated && generatedPasswordHolder != null && generatedPasswordHolder.length >= 1) {
            generatedPasswordHolder[0] = rawPassword;
        }
        return toManageInfo(created);
    }

    public void updateStatus(Long id, String status) {
        userService.updateStatus(id, status);
        LOGGER.info("admin updated user id={} status={}", id, status);
    }

    public void updateRole(Long id, String role) {
        userService.updateRole(id, role);
        LOGGER.info("admin updated user id={} role={}", id, role);
    }

    public String resetPassword(Long id) {
        String temp = generateTempPassword();
        userService.updatePasswordHash(id, passwordEncoder.encode(temp));
        LOGGER.info("admin reset password for user id={}", id);
        return temp;
    }

    private UserManageInfo toManageInfo(UserModel model) {
        UserManageInfo info = new UserManageInfo();
        info.setId(model.getId());
        info.setUserName(model.getUsername());
        info.setEmail(model.getEmail());
        info.setRole(model.getRole());
        info.setStatus(model.getStatus());
        info.setScopeId(model.getScopeId());
        info.setConsentKnowledgePromotion(model.getConsentKnowledgePromotion());
        info.setCreatedAt(model.getCreatedAt());
        info.setUpdatedAt(model.getUpdatedAt());
        return info;
    }

    private String generateTempPassword() {
        StringBuilder sb = new StringBuilder(8);
        for (int i = 0; i < 8; i++) {
            sb.append(TEMP_PASSWORD_ALPHABET.charAt(RANDOM.nextInt(TEMP_PASSWORD_ALPHABET.length())));
        }
        return sb.toString();
    }
}
