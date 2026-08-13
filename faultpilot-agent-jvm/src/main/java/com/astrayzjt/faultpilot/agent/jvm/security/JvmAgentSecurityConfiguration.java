package com.astrayzjt.faultpilot.agent.jvm.security;

import com.astrayzjt.faultpilot.agent.jvm.config.JvmAgentProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;

@Configuration
public class JvmAgentSecurityConfiguration {

    @Bean
    SecurityFilterChain jvmAgentSecurity(HttpSecurity http, JvmAgentProperties properties) throws Exception {
        http.csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(
                        org.springframework.security.config.http.SessionCreationPolicy.STATELESS))
                .addFilterBefore(new A2aBearerFilter(properties.getA2aToken()), UsernamePasswordAuthenticationFilter.class)
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/.well-known/agent-card.json", "/actuator/health", "/actuator/info").permitAll()
                        .requestMatchers("/a2a/**").hasRole("ORCHESTRATOR")
                        .anyRequest().denyAll());
        return http.build();
    }

    static final class A2aBearerFilter extends OncePerRequestFilter {
        private final byte[] expected;

        A2aBearerFilter(String token) {
            this.expected = ("Bearer " + (token == null ? "" : token)).getBytes(StandardCharsets.UTF_8);
        }

        @Override
        protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
                throws ServletException, IOException {
            String header = request.getHeader("Authorization");
            if (expected.length > "Bearer ".length() && header != null
                    && MessageDigest.isEqual(expected, header.getBytes(StandardCharsets.UTF_8))) {
                var authentication = new UsernamePasswordAuthenticationToken("faultpilot-orchestrator", null,
                        List.of(new SimpleGrantedAuthority("ROLE_ORCHESTRATOR")));
                org.springframework.security.core.context.SecurityContextHolder.getContext()
                        .setAuthentication(authentication);
            }
            chain.doFilter(request, response);
        }

        @Override
        protected boolean shouldNotFilter(HttpServletRequest request) {
            String path = request.getRequestURI().substring(request.getContextPath().length());
            return !path.startsWith("/a2a/");
        }
    }
}
