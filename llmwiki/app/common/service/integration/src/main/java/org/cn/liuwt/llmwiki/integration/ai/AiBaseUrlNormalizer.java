package org.cn.liuwt.llmwiki.integration.ai;

import java.util.Locale;

public final class AiBaseUrlNormalizer {

    private AiBaseUrlNormalizer() {
    }

    public static String normalize(String baseUrl) {
        if (baseUrl == null) {
            return null;
        }
        String s = baseUrl.trim();
        while (s.endsWith("/")) {
            s = s.substring(0, s.length() - 1);
        }
        if (s.toLowerCase(Locale.ROOT).endsWith("/v1")) {
            s = s.substring(0, s.length() - 3);
        }
        while (s.endsWith("/")) {
            s = s.substring(0, s.length() - 1);
        }
        return s;
    }
}
