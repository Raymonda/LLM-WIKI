package org.cn.liuwt.llmwiki.web.security;

import org.cn.liuwt.llmwiki.domain.model.system.UserModel;
import org.cn.liuwt.llmwiki.domain.service.system.ScopeService;
import org.cn.liuwt.llmwiki.domain.service.system.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class JwtAuthenticationFilterTest {

    @Mock private JwtTokenProvider jwtTokenProvider;
    @Mock private UserService userService;
    @Mock private ScopeService scopeService;
    @InjectMocks private JwtAuthenticationFilter filter;

    private static final Long USER_ID = 100L;
    private static final Long TEAM_SCOPE_ID = 200L;

    @BeforeEach
    void setUp() {
        when(jwtTokenProvider.validateToken(anyString())).thenReturn(true);
        when(jwtTokenProvider.getUsernameFromToken(anyString())).thenReturn("alice");
        when(jwtTokenProvider.getUserIdFromToken(anyString())).thenReturn(USER_ID);
        when(jwtTokenProvider.getRoleFromToken(anyString())).thenReturn("user");
        UserModel user = new UserModel();
        user.setId(USER_ID);
        user.setUsername("alice");
        user.setStatus("active");
        user.setRole("user");
        lenient().when(userService.getUserById(eq(USER_ID))).thenReturn(user);
    }

    private MockHttpServletRequest requestWithToken(String scopeHeader) {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.addHeader("Authorization", "Bearer t");
        if (scopeHeader != null) req.addHeader("X-Scope-Id", scopeHeader);
        return req;
    }

    @Test
    void noScopeHeader_fallsBackToPersonalScope() throws Exception {
        MockHttpServletRequest req = requestWithToken(null);
        filter.doFilter(req, new MockHttpServletResponse(), (rq, rs) -> {});
        assertEquals(USER_ID, req.getAttribute("scopeId"));
    }

    @Test
    void scopeHeaderIsMemberTeam_usesTeamScope() throws Exception {
        when(scopeService.getMemberRole(TEAM_SCOPE_ID, USER_ID)).thenReturn("editor");
        MockHttpServletRequest req = requestWithToken(String.valueOf(TEAM_SCOPE_ID));
        filter.doFilter(req, new MockHttpServletResponse(), (rq, rs) -> {});
        assertEquals(TEAM_SCOPE_ID, req.getAttribute("scopeId"));
    }

    @Test
    void scopeHeaderNotMember_fallsBackToPersonalScope() throws Exception {
        when(scopeService.getMemberRole(TEAM_SCOPE_ID, USER_ID)).thenReturn(null);
        MockHttpServletRequest req = requestWithToken(String.valueOf(TEAM_SCOPE_ID));
        filter.doFilter(req, new MockHttpServletResponse(), (rq, rs) -> {});
        assertEquals(USER_ID, req.getAttribute("scopeId"));
    }

    @Test
    void scopeQueryParamIsMemberTeam_usesTeamScope() throws Exception {
        when(scopeService.getMemberRole(TEAM_SCOPE_ID, USER_ID)).thenReturn("editor");
        MockHttpServletRequest req = requestWithToken(null);
        req.addParameter("scopeId", String.valueOf(TEAM_SCOPE_ID));
        filter.doFilter(req, new MockHttpServletResponse(), (rq, rs) -> {});
        assertEquals(TEAM_SCOPE_ID, req.getAttribute("scopeId"));
    }

    @Test
    void scopeHeaderNonNumeric_fallsBackToPersonalScope() throws Exception {
        MockHttpServletRequest req = requestWithToken("not-a-number");
        filter.doFilter(req, new MockHttpServletResponse(), (rq, rs) -> {});
        assertEquals(USER_ID, req.getAttribute("scopeId"));
    }
}
