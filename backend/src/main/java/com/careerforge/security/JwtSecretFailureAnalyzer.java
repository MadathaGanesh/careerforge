package com.careerforge.security;

import org.springframework.boot.diagnostics.AbstractFailureAnalyzer;
import org.springframework.boot.diagnostics.FailureAnalysis;

public final class JwtSecretFailureAnalyzer extends AbstractFailureAnalyzer<JwtSecretFailureAnalyzer.InvalidSecret> {
    static final class InvalidSecret extends IllegalStateException {
        InvalidSecret(String message) {
            super(message);
        }
    }

    @Override
    protected FailureAnalysis analyze(Throwable rootFailure, InvalidSecret cause) {
        return new FailureAnalysis(
                cause.getMessage(),
                """
                Set JWT_SECRET to the single-line standard Base64 encoding of at least 32 random bytes.
                For a direct run, set it in the environment of Maven or your IDE run configuration.
                For Docker Compose, put the value on the JWT_SECRET line in the root .env file.
                See the README for commands to generate a local key.
                """,
                cause);
    }
}
