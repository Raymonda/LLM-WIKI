package org.cn.liuwt.llmwiki.web.controller;

import jakarta.servlet.http.HttpServletRequest;
import org.cn.liuwt.llmwiki.common.util.result.Result;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/mcp-info")
public class McpInfoController {

    @Value("${llmwiki.mcp.public-url:}")
    private String publicUrl;

    @GetMapping("/public-url")
    public Result<String> publicUrl(HttpServletRequest request) {
        String configured = publicUrl == null ? "" : publicUrl.trim();
        if (!configured.isEmpty()) {
            return Result.success(stripTrailingSlash(configured));
        }
        String scheme = firstValue(request.getHeader("X-Forwarded-Proto"), request.getScheme());
        String host = firstValue(request.getHeader("X-Forwarded-Host"), request.getHeader("Host"));
        if (host == null || host.isBlank()) {
            host = request.getServerName();
            int port = request.getServerPort();
            if (port > 0 && port != 80 && port != 443) {
                host = host + ":" + port;
            }
        }
        return Result.success(scheme + "://" + host);
    }

    private String stripTrailingSlash(String value) {
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }

    private String firstValue(String header, String fallback) {
        if (header == null || header.isBlank()) {
            return fallback == null ? "" : fallback.trim();
        }
        String trimmed = header.trim();
        int comma = trimmed.indexOf(',');
        return (comma >= 0 ? trimmed.substring(0, comma) : trimmed).trim();
    }
}