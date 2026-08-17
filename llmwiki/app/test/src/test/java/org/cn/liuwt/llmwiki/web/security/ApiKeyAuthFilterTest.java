package org.cn.liuwt.llmwiki.web.security;

import jakarta.servlet.FilterChain;
import org.cn.liuwt.llmwiki.domain.service.system.ApiKeyService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class ApiKeyAuthFilterTest {

    private ApiKeyService apiKeyService;
    private ApiKeyAuthFilter filter;

    @BeforeEach
    void setUp() {
        apiKeyService = mock(ApiKeyService.class);
        filter = new ApiKeyAuthFilter(apiKeyService);
        SecurityContextHolder.clearContext();
    }

    @Test
    void shouldSetAttributesAndAuthenticationWhenApiKeyValid() throws Exception {
        when(apiKeyService.validate("llmwiki_good"))
            .thenReturn(new ApiKeyService.ApiKeyValidation(7L, 5L, "admin"));
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer llmwiki_good");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilterInternal(request, response, chain);

        assertThat(request.getAttribute("userId")).isEqualTo(7L);
        assertThat(request.getAttribute("scopeId")).isEqualTo(5L);
        assertThat(request.getAttribute("systemRole")).isEqualTo("admin");
        assertThat(SecurityContextHolder.getContext().getAuthentication().getAuthorities())
            .anyMatch(a -> a.getAuthority().equals("ROLE_ADMIN"));
        verify(chain).doFilter(request, response);
    }

    @Test
    void shouldSkipWhenNoApiKeyHeader() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilterInternal(request, response, chain);

        assertThat(request.getAttribute("userId")).isNull();
        verify(apiKeyService, never()).validate(any());
        verify(chain).doFilter(request, response);
    }

    @Test
    void shouldSkipWhenValidationFails() throws Exception {
        when(apiKeyService.validate("llmwiki_bad")).thenReturn(null);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer llmwiki_bad");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilterInternal(request, response, chain);

        assertThat(request.getAttribute("userId")).isNull();
        verify(chain).doFilter(request, response);
    }

    @Test
    void shouldReadXApiKeyHeaderAsFallback() throws Exception {
        when(apiKeyService.validate("llmwiki_alt"))
            .thenReturn(new ApiKeyService.ApiKeyValidation(7L, 5L, "user"));
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-API-Key", "llmwiki_alt");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilterInternal(request, response, chain);

        assertThat(request.getAttribute("userId")).isEqualTo(7L);
    }
}
