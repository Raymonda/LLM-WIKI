package org.cn.liuwt.llmwiki.bootstrap;

import java.util.ArrayList;
import java.util.List;

public final class StartupConfigValidator {

    public static final String DEFAULT_JWT_SECRET = "llmwiki-default-secret-change-in-production";

    private StartupConfigValidator() {
    }

    public static List<String> validate(String jwtSecret) {
        List<String> violations = new ArrayList<>();
        if (isMissingOrDefault(jwtSecret, DEFAULT_JWT_SECRET)) {
            violations.add("JWT_SECRET 未配置或仍为默认值，必须通过 JWT_SECRET 环境变量覆盖（生产环境启动将被阻断）");
        }
        return violations;
    }

    private static boolean isMissingOrDefault(String value, String defaultValue) {
        return value == null || value.isBlank() || defaultValue.equals(value);
    }
}
