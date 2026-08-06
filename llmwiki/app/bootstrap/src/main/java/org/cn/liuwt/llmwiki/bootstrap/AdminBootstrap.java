package org.cn.liuwt.llmwiki.bootstrap;

import org.cn.liuwt.llmwiki.domain.model.system.UserModel;
import org.cn.liuwt.llmwiki.domain.service.system.UserService;
import org.cn.liuwt.llmwiki.facade.model.CreateUserRequest;
import org.cn.liuwt.llmwiki.service.system.UserManagementService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.cn.liuwt.llmwiki.integration.ai.LlmClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class AdminBootstrap implements ApplicationRunner {

    private static final Logger LOGGER = LoggerFactory.getLogger(AdminBootstrap.class);

    @Autowired
    private UserService userService;

    @Autowired
    private UserManagementService userManagementService;

    @Autowired(required = false)
    private LlmClient llmClient;

    @Value("${llmwiki.bootstrap.admin-usernames:}")
    private List<String> adminUsernames;

    @Override
    public void run(org.springframework.boot.ApplicationArguments args) {
        checkAiConfigured();
        if (adminUsernames == null || adminUsernames.isEmpty()) {
            LOGGER.info("no admin candidate configured via llmwiki.bootstrap.admin-usernames");
            return;
        }
        for (String username : adminUsernames) {
            if (username == null || username.isBlank()) {
                continue;
            }
            String name = username.trim();
            UserModel user = userService.getUserByUsername(name);
            if (user == null) {
                createInitialAdmin(name);
                continue;
            }
            if ("admin".equals(user.getRole())) {
                LOGGER.info("user {} is already admin, skip", name);
                continue;
            }
            userService.updateRole(user.getId(), "admin");
            LOGGER.info("promoted user {} to admin", name);
        }
    }

    private void checkAiConfigured() {
        if (llmClient == null || !llmClient.isAvailable()) {
            LOGGER.warn("");
            LOGGER.warn("!!!! AI PROVIDER NOT CONFIGURED !!!!");
            LOGGER.warn("  AI features (Schema bootstrap, Ingest, Query, Lint) will NOT work.");
            LOGGER.warn("  To fix: set AI_DASHSCOPE_API_KEY (or any OpenAI-compatible key) in .env");
            LOGGER.warn("  Then restart: docker-compose restart app");
            LOGGER.warn("!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!");
            LOGGER.warn("");
        }
    }

    private void createInitialAdmin(String username) {
        try {
            CreateUserRequest request = new CreateUserRequest();
            request.setUserName(username);
            request.setRole("admin");
            String[] tempPasswordHolder = new String[1];
            userManagementService.adminCreateUser(request, tempPasswordHolder);
            String tempPassword = tempPasswordHolder[0];
            LOGGER.warn("");
            LOGGER.warn("================= INITIAL ADMIN CREATED =================");
            LOGGER.warn("  username : {}", username);
            LOGGER.warn("  password : {}", tempPassword);
            LOGGER.warn("  WARNING  : please login and change password immediately.");
            LOGGER.warn("  This temporary password will NOT be shown again.");
            LOGGER.warn("=========================================================");
            LOGGER.warn("");
        } catch (Exception e) {
            LOGGER.error("failed to create initial admin {}: {}", username, e.getMessage(), e);
        }
    }
}
