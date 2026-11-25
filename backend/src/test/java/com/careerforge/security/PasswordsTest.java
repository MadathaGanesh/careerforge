package com.careerforge.security;

import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import static org.junit.jupiter.api.Assertions.*;

class PasswordsTest {
    @Test
    void preservesUnicodeAndSpacesWithoutBcryptByteLimit() {
        String password = "  " + "é".repeat(73) + "  ";
        String hash = Passwords.encode(password);
        assertTrue(Passwords.matches(password, hash));
        assertFalse(Passwords.matches(password.strip(), hash));
    }

    @Test
    void readsExistingTrimmedBcryptHashes() {
        String hash = new BCryptPasswordEncoder(4).encode("Legacy Password");
        assertTrue(Passwords.matches(" Legacy Password ", hash));
        assertFalse(Passwords.matches("Other Password", hash));
    }
}
