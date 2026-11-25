package com.careerforge.security;

import com.careerforge.core.ApiError;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;

@Service
public class GoogleIdTokens {
    private final String clientId;
    private final JwtDecoder decoder;

    @Autowired
    public GoogleIdTokens(@Value("${app.google-client-id:}") String clientId) {
        this(clientId, NimbusJwtDecoder.withJwkSetUri("https://www.googleapis.com/oauth2/v3/certs").build());
    }

    GoogleIdTokens(String clientId, NimbusJwtDecoder decoder) {
        this.clientId = clientId.strip();
        this.decoder = decoder;
        decoder.setJwtValidator(JwtValidators.createDefaultWithValidators(java.util.List.<org.springframework.security.oauth2.core.OAuth2TokenValidator<Jwt>>of(token -> {
            String issuer = token.getClaimAsString(JwtClaimNames.ISS);
            if (!"https://accounts.google.com".equals(issuer) && !"accounts.google.com".equals(issuer)) {
                return org.springframework.security.oauth2.core.OAuth2TokenValidatorResult.failure(
                        new org.springframework.security.oauth2.core.OAuth2Error("invalid_token", "Invalid Google issuer", null));
            }
            return org.springframework.security.oauth2.core.OAuth2TokenValidatorResult.success();
        })));
    }

    public String clientId() {
        return clientId;
    }

    public Jwt verify(String credential) {
        if (clientId.isBlank()) {
            throw new ApiError(HttpStatus.SERVICE_UNAVAILABLE, "OAUTH_UNCONFIGURED", "Google sign-in is not configured");
        }
        try {
            Jwt token = decoder.decode(credential);
            String holder = token.getClaimAsString("azp");
            if (token.getAudience() != null && token.getAudience().contains(clientId)
                    && (holder == null ? token.getAudience().size() == 1 : clientId.equals(holder))
                    && Boolean.TRUE.equals(token.getClaim("email_verified"))
                    && token.getSubject() != null && !token.getSubject().isBlank()
                    && token.getClaimAsString("email") != null && !token.getClaimAsString("email").isBlank()
                    && token.getExpiresAt() != null && token.getIssuedAt() != null
                    && token.getIssuedAt().isBefore(Instant.now().plus(Duration.ofMinutes(1)))) {
                return token;
            }
        } catch (JwtException | IllegalArgumentException ignored) {
            // A rejected credential and its claims never enter log output.
        }
        throw new ApiError(HttpStatus.UNAUTHORIZED, "GOOGLE_TOKEN_INVALID", "Google authentication failed");
    }
}
