package io.github.wynyga.ledger.adapter.in.web;

import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.server.resource.web.BearerTokenAuthenticationEntryPoint;
import org.springframework.security.oauth2.server.resource.web.access.BearerTokenAccessDeniedHandler;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;

/**
 * Stateless bearer-token API. CSRF protection is off because no cookie or session ever authenticates a request:
 * a forged cross-site request has no way to attach the token.
 */
@Configuration(proxyBeanMethods = false)
class SecurityConfig {
    static final String ADMIN = "SCOPE_ledger:admin";

    @Bean
    SecurityFilterChain api(HttpSecurity http) throws Exception {
        AuthenticationEntryPoint unauthenticated = (req, res, ex) -> {
            // Sets 401 and the RFC 6750 WWW-Authenticate header (with error="invalid_token" for a bad token).
            new BearerTokenAuthenticationEntryPoint().commence(req, res, ex);
            write(res, HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "a valid bearer token is required");
        };
        AccessDeniedHandler forbidden = (req, res, ex) -> {
            new BearerTokenAccessDeniedHandler().handle(req, res, ex);
            write(res, HttpStatus.FORBIDDEN, "FORBIDDEN", "this token does not allow the request");
        };

        http.csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(a -> a
                        .requestMatchers(HttpMethod.POST, "/api/v1/auth/register", "/api/v1/auth/token").permitAll()
                        .requestMatchers("/actuator/health", "/actuator/health/**", "/error").permitAll()
                        // Checked again in the use cases; this is the first of two locks.
                        .requestMatchers(HttpMethod.POST, "/api/v1/deposits").hasAuthority(ADMIN)
                        .requestMatchers("/api/v1/reconciliation", "/actuator/**").hasAuthority(ADMIN)
                        .anyRequest().authenticated())
                .oauth2ResourceServer(o -> o.jwt(Customizer.withDefaults())
                        .authenticationEntryPoint(unauthenticated)
                        .accessDeniedHandler(forbidden))
                .exceptionHandling(e -> e.authenticationEntryPoint(unauthenticated).accessDeniedHandler(forbidden));
        return http.build();
    }

    /** Same problem-detail shape as {@link ProblemHandler}; the texts are constants, so no JSON escaping is needed. */
    private static void write(HttpServletResponse res, HttpStatus status, String code, String detail)
            throws IOException {
        res.setStatus(status.value());
        res.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        res.getWriter().write("""
                {"type":"about:blank","title":"%s","status":%d,"detail":"%s","code":"%s"}"""
                .formatted(status.getReasonPhrase(), status.value(), detail, code));
    }
}
