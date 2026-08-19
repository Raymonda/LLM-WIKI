package org.cn.liuwt.llmwiki.service.auth;

import org.cn.liuwt.llmwiki.common.util.exception.AuthenticationException;
import org.cn.liuwt.llmwiki.common.util.exception.BusinessException;
import org.cn.liuwt.llmwiki.common.util.exception.ErrorCode;
import org.cn.liuwt.llmwiki.domain.model.system.UserModel;
import org.cn.liuwt.llmwiki.domain.service.system.UserService;
import org.cn.liuwt.llmwiki.domain.service.wiki.WikiFileServiceImpl;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Service
public class AuthService {

    @Autowired
    private UserService userService;

    @Autowired
    private WikiFileServiceImpl wikiFileService;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Value("${llmwiki.auth.allow-self-register:false}")
    private boolean allowSelfRegister;

    public UserModel authenticate(String username, String password) {
        UserModel user = userService.getUserByUsername(username);
        if (user == null) {
            throw new AuthenticationException(ErrorCode.AUTH_INVALID_CREDENTIALS);
        }
        if (!passwordEncoder.matches(password, user.getPasswordHash())) {
            throw new AuthenticationException(ErrorCode.AUTH_INVALID_CREDENTIALS);
        }
        if (!"active".equals(user.getStatus())) {
            throw new AuthenticationException(ErrorCode.AUTH_USER_DISABLED);
        }
        return user;
    }

    public UserModel register(String username, String password, String email) {
        if (!allowSelfRegister) {
            throw new BusinessException(ErrorCode.AUTH_SELF_REGISTER_DISABLED);
        }
        UserModel existing = userService.getUserByUsername(username);
        if (existing != null) {
            throw new AuthenticationException(ErrorCode.USERNAME_EXISTS);
        }
        UserModel newUser = new UserModel();
        newUser.setUsername(username);
        newUser.setPasswordHash(passwordEncoder.encode(password));
        newUser.setEmail(email);
        newUser.setRole("user");
        newUser.setStatus("active");
        UserModel created = userService.createUser(newUser);
        wikiFileService.initWikiData(created.getScopeId());
        return created;
    }
}
