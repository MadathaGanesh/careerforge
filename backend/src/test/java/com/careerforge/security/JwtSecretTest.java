package com.careerforge.security;

import org.junit.jupiter.api.Test;

import java.security.SecureRandom;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class JwtSecretTest {
    private final AuthSecurity security = new AuthSecurity();

    @Test
    void acceptsASingleKeyWithSurroundingWhitespace() {
        byte[] key = new byte[32];
        new SecureRandom().nextBytes(key);
        String encoded = Base64.getEncoder().encodeToString(key);

        assertArrayEquals(key, security.secret(" \t" + encoded + "\r\n").getEncoded());
    }

    @Test
    void distinguishesMissingMalformedAndShortKeys() {
        assertEquals("JWT_SECRET is missing or blank.", rejection("  ").getMessage());
        assertEquals("JWT_SECRET is not standard Base64.", rejection("C:\\example\\secret").getMessage());
        assertEquals("JWT_SECRET decodes to fewer than 32 bytes.", rejection("YWJj").getMessage());
    }

    private JwtSecretFailureAnalyzer.InvalidSecret rejection(String value) {
        return assertThrows(JwtSecretFailureAnalyzer.InvalidSecret.class, () -> security.secret(value));
    }
}
