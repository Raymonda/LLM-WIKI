package org.cn.liuwt.llmwiki.web.controller;

import org.cn.liuwt.llmwiki.common.util.result.Result;
import org.cn.liuwt.llmwiki.facade.model.CreateUserRequest;
import org.cn.liuwt.llmwiki.facade.model.PageResult;
import org.cn.liuwt.llmwiki.facade.model.ResetPasswordResult;
import org.cn.liuwt.llmwiki.facade.model.UpdateUserRoleRequest;
import org.cn.liuwt.llmwiki.facade.model.UpdateUserStatusRequest;
import org.cn.liuwt.llmwiki.facade.model.UserManageInfo;
import org.cn.liuwt.llmwiki.facade.model.UserSearchInfo;
import org.cn.liuwt.llmwiki.service.system.UserManagementService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/users")
public class UserController {

    @Autowired
    private UserManagementService userManagementService;

    @GetMapping
    @PreAuthorize("hasRole('ADMIN')")
    public Result<PageResult<UserManageInfo>> listUsers(
        @RequestParam(value = "q", required = false) String keyword,
        @RequestParam(value = "role", required = false) String role,
        @RequestParam(value = "status", required = false) String status,
        @RequestParam(value = "page", defaultValue = "1") int page,
        @RequestParam(value = "size", defaultValue = "20") int size
    ) {
        return Result.success(userManagementService.listUsers(keyword, role, status, page, size));
    }

    @GetMapping("/search")
    public Result<List<UserSearchInfo>> searchUsers(
        @RequestParam(value = "q", required = false) String keyword,
        @RequestParam(value = "limit", defaultValue = "10") int limit
    ) {
        return Result.success(userManagementService.searchUsers(keyword, limit));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public Result<UserManageInfo> getUser(@PathVariable Long id) {
        return Result.success(userManagementService.getUserById(id));
    }

    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    public Result<CreateUserResponse> createUser(@RequestBody CreateUserRequest request) {
        String[] tempHolder = new String[1];
        UserManageInfo created = userManagementService.adminCreateUser(request, tempHolder);
        CreateUserResponse response = new CreateUserResponse();
        response.setUser(created);
        response.setTempPassword(tempHolder[0]);
        return Result.success(response);
    }

    @PatchMapping("/{id}/status")
    @PreAuthorize("hasRole('ADMIN')")
    public Result<Void> updateStatus(@PathVariable Long id, @RequestBody UpdateUserStatusRequest request) {
        userManagementService.updateStatus(id, request.getStatus());
        return Result.success();
    }

    @PatchMapping("/{id}/role")
    @PreAuthorize("hasRole('ADMIN')")
    public Result<Void> updateRole(@PathVariable Long id, @RequestBody UpdateUserRoleRequest request) {
        userManagementService.updateRole(id, request.getRole());
        return Result.success();
    }

    @PostMapping("/{id}/reset-password")
    @PreAuthorize("hasRole('ADMIN')")
    public Result<ResetPasswordResult> resetPassword(@PathVariable Long id) {
        String tempPassword = userManagementService.resetPassword(id);
        ResetPasswordResult result = new ResetPasswordResult();
        result.setTempPassword(tempPassword);
        return Result.success(result);
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public Result<Void> disableUser(@PathVariable Long id) {
        userManagementService.updateStatus(id, "disabled");
        return Result.success();
    }

    public static class CreateUserResponse {
        private UserManageInfo user;
        private String tempPassword;

        public UserManageInfo getUser() {
            return user;
        }

        public void setUser(UserManageInfo user) {
            this.user = user;
        }

        public String getTempPassword() {
            return tempPassword;
        }

        public void setTempPassword(String tempPassword) {
            this.tempPassword = tempPassword;
        }
    }
}
