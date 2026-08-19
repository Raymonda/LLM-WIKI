package org.cn.liuwt.llmwiki.domain.service.system;

import com.baomidou.mybatisplus.core.metadata.IPage;
import org.cn.liuwt.llmwiki.domain.model.system.UserModel;

import java.util.List;

public interface UserService {
    UserModel getUserByUsername(String username);

    UserModel getUserById(Long id);

    UserModel createUser(UserModel userModel);

    IPage<UserModel> listUsers(String keyword, String role, String status, int page, int size);

    List<UserModel> searchUsers(String keyword, int limit);

    void updateStatus(Long id, String status);

    void updateRole(Long id, String role);

    void updatePasswordHash(Long id, String passwordHash);

    long countByRole(String role);
}
