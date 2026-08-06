package com.astrayzjt.faultpilot.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

@Configuration
public class SecurityConfiguration {

    @Bean
    PasswordEncoder passwordEncoder() {
        return PasswordEncoderFactories.createDelegatingPasswordEncoder();
    }

    @Bean
    UserDetailsService users(PasswordEncoder encoder,
                             @Value("${faultpilot.security.viewer-password:}") String viewerPassword,
                             @Value("${faultpilot.security.operator-password:}") String operatorPassword,
                             @Value("${faultpilot.security.admin-password:}") String adminPassword) {
        List<UserDetails> users = new ArrayList<>();
        addUser(users, "viewer", viewerPassword, "VIEWER", encoder);
        addUser(users, "operator", operatorPassword, "VIEWER", "OPERATOR", encoder);
        addUser(users, "admin", adminPassword, "VIEWER", "OPERATOR", "ADMIN", encoder);
        if (users.isEmpty()) {
            users.add(User.withUsername("disabled").password(encoder.encode(java.util.UUID.randomUUID().toString()))
                    .roles("VIEWER").disabled(true).build());
        }
        return new InMemoryUserDetailsManager(users);
    }

    @Bean
    @Order(1)
    SecurityFilterChain webhookChain(HttpSecurity http,
                                     @Value("${faultpilot.security.alertmanager-token:}") String token) throws Exception {
        http.securityMatcher("/api/integrations/alertmanager/webhook")
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(org.springframework.security.config.http.SessionCreationPolicy.STATELESS))
                .addFilterBefore(new BearerTokenFilter(token), org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter.class)
                .authorizeHttpRequests(auth -> auth.anyRequest().hasRole("OPERATOR"));
        return http.build();
    }

    @Bean
    @Order(2)
    SecurityFilterChain applicationChain(HttpSecurity http) throws Exception {
        CookieCsrfTokenRepository csrfRepository = CookieCsrfTokenRepository.withHttpOnlyFalse();
        CsrfTokenRequestAttributeHandler csrfHandler = new CsrfTokenRequestAttributeHandler();
        csrfHandler.setCsrfRequestAttributeName(null);
        http.csrf(csrf -> csrf.csrfTokenRepository(csrfRepository).csrfTokenRequestHandler(csrfHandler))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/actuator/**", "/api/system", "/api/security/csrf", "/swagger-ui/**", "/swagger-ui.html", "/v3/api-docs/**").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/incidents/**", "/api/pending-actions/**").hasAnyRole("VIEWER", "OPERATOR", "ADMIN")
                        .requestMatchers(HttpMethod.POST, "/api/incidents", "/api/incidents/**", "/api/pending-actions/**", "/api/evaluations").hasAnyRole("OPERATOR", "ADMIN")
                        .requestMatchers(HttpMethod.PATCH, "/api/incidents/**").hasAnyRole("OPERATOR", "ADMIN")
                        .anyRequest().authenticated())
                .formLogin(form -> form.permitAll())
                .httpBasic(basic -> basic.realmName("FaultPilot"))
                .logout(logout -> logout.logoutSuccessHandler((request, response, authentication) -> response.setStatus(204)));
        return http.build();
    }

    private void addUser(List<UserDetails> users, String username, String password, String role, PasswordEncoder encoder) {
        addUser(users, username, password, new String[]{role}, encoder);
    }

    private void addUser(List<UserDetails> users, String username, String password, String role1, String role2, PasswordEncoder encoder) {
        addUser(users, username, password, new String[]{role1, role2}, encoder);
    }

    private void addUser(List<UserDetails> users, String username, String password, String role1, String role2, String role3, PasswordEncoder encoder) {
        addUser(users, username, password, new String[]{role1, role2, role3}, encoder);
    }

    private void addUser(List<UserDetails> users, String username, String password, String[] roles, PasswordEncoder encoder) {
        if (password != null && !password.isBlank()) {
            users.add(User.withUsername(username).password(encoder.encode(password)).roles(roles).build());
        }
    }

    static final class BearerTokenFilter extends OncePerRequestFilter {
        private final String expected;

        BearerTokenFilter(String expected) {
            this.expected = expected == null ? "" : expected;
        }

        @Override
        protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
                throws ServletException, IOException {
            String header = request.getHeader("Authorization");
            if (!expected.isBlank() && header != null && header.equals("Bearer " + expected)) {
                var authentication = new UsernamePasswordAuthenticationToken("alertmanager", null,
                        List.of(new SimpleGrantedAuthority("ROLE_OPERATOR")));
                org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(authentication);
            }
            chain.doFilter(request, response);
        }

        @Override
        protected boolean shouldNotFilter(HttpServletRequest request) {
            return !"/api/integrations/alertmanager/webhook".equals(request.getServletPath());
        }
    }
}
