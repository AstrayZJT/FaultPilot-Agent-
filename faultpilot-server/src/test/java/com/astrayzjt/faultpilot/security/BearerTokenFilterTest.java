package com.astrayzjt.faultpilot.security;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class BearerTokenFilterTest {

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void authenticatesMatchingTokenOnlyWithinConfiguredPath() throws Exception {
        SecurityConfiguration.BearerTokenFilter filter = new SecurityConfiguration.BearerTokenFilter(
                "secret", "/api/internal/", "specialist-agent", "ROLE_AGENT");
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/internal/evidence");
        request.setServletPath("/api/internal/evidence");
        request.addHeader("Authorization", "Bearer secret");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        assertThat(SecurityContextHolder.getContext().getAuthentication().getName())
                .isEqualTo("specialist-agent");
        assertThat(SecurityContextHolder.getContext().getAuthentication().getAuthorities())
                .extracting("authority").containsExactly("ROLE_AGENT");
        verify(chain).doFilter(request, response);
    }

    @Test
    void leavesRequestUnauthenticatedForWrongToken() throws Exception {
        SecurityConfiguration.BearerTokenFilter filter = new SecurityConfiguration.BearerTokenFilter(
                "secret", "/api/internal/", "specialist-agent", "ROLE_AGENT");
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/internal/evidence");
        request.setServletPath("/api/internal/evidence");
        request.addHeader("Authorization", "Bearer wrong");

        filter.doFilter(request, new MockHttpServletResponse(), mock(FilterChain.class));

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }
}
