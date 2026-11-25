package com.careerforge.security;

import com.careerforge.core.ApiError;
import com.nimbusds.jose.*;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.Date;

import static org.junit.jupiter.api.Assertions.*;

class GoogleIdTokensTest {
    private static KeyPair signingKey;
    private static GoogleIdTokens verifier;

    @BeforeAll
    static void fixture() throws Exception {
        var generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        signingKey = generator.generateKeyPair();
        verifier = new GoogleIdTokens("careerforge-test-client",
                NimbusJwtDecoder.withPublicKey((RSAPublicKey) signingKey.getPublic()).build());
    }

    @Test
    void verifiesSignatureIssuerAudienceAndEmail() throws Exception {
        assertEquals("fixture-google-subject", verifier.verify(
                token("https://accounts.google.com", "careerforge-test-client", true, 600)).getSubject());
        assertThrows(ApiError.class, () -> verifier.verify(token("https://identity.example.test", "careerforge-test-client", true, 600)));
        assertThrows(ApiError.class, () -> verifier.verify(token("https://accounts.google.com", "other-client", true, 600)));
        assertThrows(ApiError.class, () -> verifier.verify(token("https://accounts.google.com", "careerforge-test-client", false, 600)));
        assertThrows(ApiError.class, () -> verifier.verify(token("https://accounts.google.com", "careerforge-test-client", true, -600)));
    }

    private String token(String issuer, String audience, boolean emailVerified, long expiresIn) throws Exception {
        Instant now = Instant.now();
        var claims = new JWTClaimsSet.Builder().issuer(issuer).subject("fixture-google-subject").audience(audience)
                .issueTime(Date.from(now.minusSeconds(1200))).expirationTime(Date.from(now.plusSeconds(expiresIn)))
                .claim("email", "google@example.test").claim("email_verified", emailVerified).build();
        var token = new SignedJWT(new JWSHeader(JWSAlgorithm.RS256), claims);
        token.sign(new RSASSASigner(signingKey.getPrivate()));
        return token.serialize();
    }
}
