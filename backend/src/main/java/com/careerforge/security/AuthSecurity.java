package com.careerforge.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;
import org.springframework.stereotype.Component;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.OncePerRequestFilter;

import javax.crypto.Mac;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Configuration
public class AuthSecurity {

    private static final Logger log = LoggerFactory.getLogger(AuthSecurity.class);

    private static void writeJsonError(
            HttpServletResponse response,
            HttpStatus status,
            String code,
            String message
    ) throws IOException {
        if (response.isCommitted()) return;
        response.setStatus(status.value());
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType("application/json");
        response.getWriter().write(
                "{\"code\":\"" + code + "\",\"message\":\"" + message + "\"}"
        );
    }

    @Bean
    SecretKey secret(@Value("${app.jwt-secret}") String base64) {
        if (base64.isBlank()) {
            throw new JwtSecretFailureAnalyzer.InvalidSecret("JWT_SECRET is missing or blank.");
        }
        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(base64.strip());
        } catch (IllegalArgumentException ignored) {
            throw new JwtSecretFailureAnalyzer.InvalidSecret("JWT_SECRET is not standard Base64.");
        }
        if (bytes.length < 32) {
            throw new JwtSecretFailureAnalyzer.InvalidSecret("JWT_SECRET decodes to fewer than 32 bytes.");
        }
        return new SecretKeySpec(bytes, "HmacSHA256");
    }

    @Bean
    JwtEncoder encoder(SecretKey key) {
        return new NimbusJwtEncoder(
                new com.nimbusds.jose.jwk.source.ImmutableSecret<>(key)
        );
    }

    @Bean
    JwtDecoder decoder(SecretKey key) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withSecretKey(key)
                .macAlgorithm(MacAlgorithm.HS256)
                .build();
        decoder.setJwtValidator(JwtValidators.createDefaultWithIssuer("careerforge"));
        return decoder;
    }

    @Bean
    SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            CookieAuth cookieAuth
    ) throws Exception {
        return http
                // CookieAuth supplies CSRF checks for authenticated unsafe requests.
                .csrf(csrf -> csrf.disable())
                .cors(cors -> {
                })
                .sessionManagement(session -> session.sessionCreationPolicy(
                        SessionCreationPolicy.STATELESS
                ))
                .authorizeHttpRequests(auth -> auth
                        .dispatcherTypeMatchers(jakarta.servlet.DispatcherType.ASYNC).permitAll()
                        // Logout is not public.
                        .requestMatchers("/api/v1/auth/logout").authenticated()
                        .requestMatchers(
                                "/api/v1/auth/**",
                                "/api/v1/billing/webhook",
                                "/actuator/health",
                                "/actuator/info",
                                "/error"
                        ).permitAll()
                        .anyRequest().authenticated()
                )
                .exceptionHandling(errors -> errors
                        .authenticationEntryPoint((request, response, exception) -> {
                            log.warn("Unauthenticated request: {} {}",
                                    request.getMethod(), request.getRequestURI());
                            writeJsonError(response, HttpStatus.UNAUTHORIZED,
                                    "UNAUTHENTICATED", "Please sign in to continue");
                        })
                        .accessDeniedHandler((request, response, exception) -> {
                            Authentication current = SecurityContextHolder
                                    .getContext().getAuthentication();
                            log.warn("Access denied: {} {}, authenticationType={}, authenticated={}",
                                    request.getMethod(), request.getRequestURI(),
                                    current == null ? "null" : current.getClass().getSimpleName(),
                                    current != null && current.isAuthenticated());
                            writeJsonError(response, HttpStatus.FORBIDDEN,
                                    "ACCESS_DENIED", "You do not have access to this resource");
                        })
                )
                // Run after the SecurityContextHolderFilter and before authorization.
                .addFilterBefore(cookieAuth, AnonymousAuthenticationFilter.class)
                .build();
    }

    @Bean
    FilterRegistrationBean<CookieAuth> cookieAuthServletRegistration(CookieAuth cookieAuth) {
        FilterRegistrationBean<CookieAuth> registration =
                new FilterRegistrationBean<>(cookieAuth);
        // This filter must run ONLY within Spring Security, not twice.
        registration.setEnabled(false);
        return registration;
    }

    @Bean
    CorsConfigurationSource cors(@Value("${app.public-origin}") String origin) {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(List.of(origin));
        config.setAllowedMethods(List.of(
                "GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"
        ));
        config.setAllowedHeaders(List.of(
                "Content-Type", "X-CSRF-Token", "Idempotency-Key"
        ));
        config.setAllowCredentials(true);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }

    @Component
    public static class CookieAuth extends OncePerRequestFilter {

        private static final Logger log = LoggerFactory.getLogger(CookieAuth.class);
        private final JwtDecoder decoder;
        private final SecretKey key;
        private final String origin;
        private final JdbcTemplate jdbc;

        public CookieAuth(
                JwtDecoder decoder,
                SecretKey key,
                @Value("${app.public-origin}") String origin,
                JdbcTemplate jdbc
        ) {
            this.decoder = decoder;
            this.key = key;
            this.origin = origin;
            this.jdbc = jdbc;
        }

        private static String cookie(HttpServletRequest request, String name) {
            if (request.getCookies() == null) return null;
            for (Cookie cookie : request.getCookies()) {
                if (name.equals(cookie.getName())) return cookie.getValue();
            }
            return null;
        }

        private static boolean constantTimeEquals(String first, String second) {
            return MessageDigest.isEqual(
                    first.getBytes(StandardCharsets.UTF_8),
                    second.getBytes(StandardCharsets.UTF_8)
            );
        }

        public static String csrfFor(String jti, SecretKey secret) {
            try {
                Mac mac = Mac.getInstance("HmacSHA256");
                mac.init(secret);
                return Base64.getUrlEncoder().withoutPadding().encodeToString(
                        mac.doFinal(jti.getBytes(StandardCharsets.UTF_8))
                );
            } catch (Exception ex) {
                throw new IllegalStateException("Unable to generate CSRF token", ex);
            }
        }

        @Override
        protected void doFilterInternal(
                HttpServletRequest request,
                HttpServletResponse response,
                FilterChain chain
        ) throws ServletException, IOException {

            String method = request.getMethod();
            String path = request.getRequestURI();
            Jwt validSession = null;
            String token = cookie(request, "CF_SESSION");

            if (token != null && !token.isBlank()) {
                try {
                    Jwt candidate = decoder.decode(token);
                    UUID jti = UUID.fromString(candidate.getId());
                    UUID userId = UUID.fromString(candidate.getSubject());

                    Integer active = jdbc.queryForObject(
                            """
                                    SELECT COUNT(*)
                                    FROM auth_sessions s
                                    JOIN app_users u ON u.id = s.user_id
                                    WHERE s.jti = ?
                                      AND s.user_id = ?
                                      AND s.revoked_at IS NULL
                                      AND s.expires_at > now()
                                      AND u.deleted_at IS NULL
                                    """,
                            Integer.class,
                            jti,
                            userId
                    );

                    if (active != null && active > 0) {
                        validSession = candidate;

                        // The two-argument constructor marks a validated token authenticated.
                        JwtAuthenticationToken authentication =
                                new JwtAuthenticationToken(candidate, List.of());
                        SecurityContext context =
                                SecurityContextHolder.createEmptyContext();
                        context.setAuthentication(authentication);
                        SecurityContextHolder.setContext(context);

                        log.debug("Authenticated session for {} {}: authenticated={}",
                                method, path, authentication.isAuthenticated());
                    } else {
                        log.warn("Session not active for {} {}", method, path);
                    }
                } catch (Exception ex) {
                    // Never log token values or cookie contents.
                    log.warn("Session validation failed for {} {} ({})",
                            method, path, ex.getClass().getSimpleName());
                }
            } else {
                log.debug("No CF_SESSION cookie for {} {}", method, path);
            }

            boolean safeMethod = Set.of("GET", "HEAD", "OPTIONS").contains(method);
            boolean publicAuth = path.startsWith("/api/v1/auth/")
                    && !path.equals("/api/v1/auth/logout");

            // Existing application uses double-submit CSRF for authenticated writes.
            if (!safeMethod && !publicAuth && validSession != null) {
                String suppliedHeader = request.getHeader("X-CSRF-Token");
                String suppliedCookie = cookie(request, "CF_CSRF");
                String expected = csrfFor(validSession.getId(), key);
                String requestOrigin = request.getHeader("Origin");

                if (suppliedHeader == null
                        || suppliedCookie == null
                        || !constantTimeEquals(suppliedHeader, expected)
                        || !constantTimeEquals(suppliedCookie, expected)
                        || (requestOrigin != null && !requestOrigin.equals(origin))) {
                    log.warn("CSRF validation failed for {} {}", method, path);
                    writeJsonError(response, HttpStatus.FORBIDDEN,
                            "CSRF_INVALID", "Invalid request token");
                    SecurityContextHolder.clearContext();
                    return;
                }
            }

            try {
                chain.doFilter(request, response);
            } finally {
                log.info("CareerForge request: {} {} -> {}",
                        method, path, response.getStatus());
                SecurityContextHolder.clearContext();
            }
        }
    }
}

