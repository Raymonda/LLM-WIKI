package org.cn.liuwt.llmwiki.web.security;

import org.cn.liuwt.llmwiki.common.util.constant.Constants;
import org.cn.liuwt.llmwiki.domain.model.system.UserModel;
import org.cn.liuwt.llmwiki.domain.service.system.ScopeService;
import org.cn.liuwt.llmwiki.domain.service.system.UserService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.Set;

@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {
    private static final Set<String> QUERY_TOKEN_PATHS = Set.of(
        "/api/query/stream",
        "/api/ingest/",
        "/api/lint/",
        "/api/source/",
        "/api/wiki/assets/"
    );

    public static final String SCOPE_HEADER = "X-Scope-Id";
    public static final String SCOPE_QUERY_PARAM = "scopeId";

    @Autowired
    private JwtTokenProvider jwtTokenProvider;

    @Autowired
    private UserService userService;

    @Autowired
    private ScopeService scopeService;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String token = extractToken(request);
        if (StringUtils.hasText(token) && jwtTokenProvider.validateToken(token)) {
            String username = jwtTokenProvider.getUsernameFromToken(token);
            Long userId = jwtTokenProvider.getUserIdFromToken(token);
            String role = jwtTokenProvider.getRoleFromToken(token);

            UserModel user = userService.getUserById(userId);
            if (user == null || !"active".equals(user.getStatus())) {
                filterChain.doFilter(request, response);
                return;
            }

            String effectiveRole = user.getRole() != null ? user.getRole() : (role != null ? role : "user");

            Long activeScopeId = resolveActiveScope(request, userId);

            request.setAttribute("userId", userId);
            request.setAttribute("scopeId", activeScopeId);
            request.setAttribute("systemRole", effectiveRole);

            List<SimpleGrantedAuthority> authorities = Collections.singletonList(
                new SimpleGrantedAuthority("ROLE_" + effectiveRole.toUpperCase())
            );
            UsernamePasswordAuthenticationToken authentication =
                    new UsernamePasswordAuthenticationToken(username, null, authorities);
            SecurityContextHolder.getContext().setAuthentication(authentication);
        }
        filterChain.doFilter(request, response);
    }

    /**
     * 解析活动 scope：优先 X-Scope-Id 请求头（REST），回退 scopeId query 参数（EventSource），
     * 经 ScopeService.getMemberRole 校验成员关系；无值或非成员回退个人 scope（=userId）。
     */
    private Long resolveActiveScope(HttpServletRequest request, Long userId) {
        String raw = request.getHeader(SCOPE_HEADER);
        if (!StringUtils.hasText(raw)) {
            raw = request.getParameter(SCOPE_QUERY_PARAM);
        }
        if (StringUtils.hasText(raw)) {
            try {
                Long requestedScopeId = Long.parseLong(raw);
                if (scopeService.getMemberRole(requestedScopeId, userId) != null) {
                    return requestedScopeId;
                }
            } catch (NumberFormatException ignored) {
            }
        }
        return userId;
    }

    private String extractToken(HttpServletRequest request) {
        String bearerToken = request.getHeader(Constants.JWT_TOKEN_HEADER);
        if (StringUtils.hasText(bearerToken) && bearerToken.startsWith(Constants.JWT_TOKEN_PREFIX)) {
            return bearerToken.substring(Constants.JWT_TOKEN_PREFIX.length());
        }
        String requestPath = request.getRequestURI();
        boolean supportsQueryToken = QUERY_TOKEN_PATHS.stream().anyMatch(requestPath::startsWith);
        if (supportsQueryToken) {
            String queryToken = request.getParameter("token");
            if (StringUtils.hasText(queryToken)) {
                return queryToken;
            }
        }
        return null;
    }
}