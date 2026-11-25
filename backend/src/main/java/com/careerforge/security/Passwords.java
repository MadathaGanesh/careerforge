package com.careerforge.security;

import com.careerforge.core.ApiError;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.Pbkdf2PasswordEncoder;

import java.nio.charset.StandardCharsets;

public final class Passwords {
    private static final String PREFIX = "{pbkdf2}";
    private static final Pbkdf2PasswordEncoder CURRENT =
            Pbkdf2PasswordEncoder.defaultsForSpringSecurity_v5_8();
    private static final BCryptPasswordEncoder LEGACY = new BCryptPasswordEncoder();

    private Passwords() {}

    public static String input(String password) {
        if (password == null || password.isEmpty()
                || password.codePointCount(0, password.length()) > 128) {
            throw ApiError.invalid("Enter a password of up to 128 characters");
        }
        return password;
    }

    public static String encode(String password) {
        if (password.isBlank() || password.codePointCount(0, password.length()) < 12) {
            throw ApiError.invalid("Password must contain 12–128 characters");
        }
        return PREFIX + CURRENT.encode(password);
    }

    public static boolean matches(String password, String hash) {
        if (hash == null) return false;
        if (hash.startsWith(PREFIX)) return CURRENT.matches(password, hash.substring(PREFIX.length()));
        // Old requests trimmed input before hashing. Keep those accounts usable.
        String legacy = password.trim();
        return legacy.getBytes(StandardCharsets.UTF_8).length <= 72 && LEGACY.matches(legacy, hash);
    }
}
