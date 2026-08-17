package org.cn.liuwt.llmwiki.web.security;

import org.cn.liuwt.llmwiki.domain.service.system.ApiKeyService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Collections;

@Component
public class ApiKeyAuthFilter extends OncePerRequestFilter {

    private final ApiKeyService apiKeyService;

    public ApiKeyAuthFilter(ApiKeyService apiKeyService) {
        this.apiKeyService = apiKeyService;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String rawKey = extractApiKey(request);
        if (StringUtils.hasText(rawKey) && SecurityContextHolder.getContext().getAuthentication() == null) {
            ApiKeyService.ApiKeyValidation validation = apiKeyService.validate(rawKey);
            if (validation != null) {
                request.setAttribute("userId", validation.userId());
                request.setAttribute("scopeId", validation.scopeId());
                request.setAttribute("systemRole", validation.role());
                UsernamePasswordAuthenticationToken authentication =
                    new UsernamePasswordAuthenticationToken(
                        "api-key", null,
                        Collections.singletonList(new SimpleGrantedAuthority(
                            "ROLE_" + validation.role().toUpperCase())));
                SecurityContextHolder.getContext().setAuthentication(authentication);
            }
        }
        filterChain.doFilter(request, response);
    }

    private String extractApiKey(HttpServletRequest request) {
        String bearer = request.getHeader("Authorization");
        if (StringUtils.hasText(bearer) && bearer.startsWith("Bearer llmwiki_")) {
            return bearer.substring("Bearer ".length());
        }
        return request.getHeader("X-API-Key");
    }
}
