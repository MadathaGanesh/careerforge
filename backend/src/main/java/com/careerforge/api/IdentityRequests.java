package com.careerforge.api;

import com.careerforge.core.ApiError;

final class IdentityRequests {
    private IdentityRequests() {}

    record Register(String email, String name, String password) {}
    record Login(String email, String password) {}
    record Email(String email) {}
    record Google(String credential) {}
    record GoogleWithPassword(String credential, String password) {}
    record EmailChange(String email, String password, String credential) {}
    record EmailCode(String email, String code) {}
    record VerifyEmail(String email, String code, String token) {}
    record Reset(String token, String password) {}
    record ChangePassword(String currentPassword, String newPassword) {}

    static String required(String value, String field) {
        if (value == null || value.isBlank()) throw ApiError.invalid("Field required: " + field);
        return value.trim();
    }
}
