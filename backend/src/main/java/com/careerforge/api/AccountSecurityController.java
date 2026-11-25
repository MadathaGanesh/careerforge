package com.careerforge.api;

import com.careerforge.core.ApiError;
import com.careerforge.core.Db;
import com.careerforge.security.Passwords;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/users/me")
public class AccountSecurityController {
    private final Db db;
    private final TransactionTemplate tx;

    public AccountSecurityController(Db db, TransactionTemplate tx) {
        this.db = db;
        this.tx = tx;
    }

    @PostMapping("/change-password")
    public Map<String, Object> changePassword(@RequestBody IdentityRequests.ChangePassword body) {
        UUID owner = db.uid();
        String current = Passwords.input(body.currentPassword());
        String next = Passwords.input(body.newPassword());
        boolean changed = Boolean.TRUE.equals(tx.execute(status -> {
            db.lockUser(owner);
            Integer failures = db.jdbc.queryForObject("""
                    SELECT count(*) FROM password_change_failures
                    WHERE user_id=? AND attempted_at>now()-interval '15 minutes'
                    """, Integer.class, owner);
            if (failures != null && failures >= 5) {
                throw new ApiError(HttpStatus.TOO_MANY_REQUESTS, "TOO_MANY_ATTEMPTS", "Try again in 15 minutes.");
            }
            String hash = (String) db.one("SELECT password_hash FROM app_users WHERE id=?", owner).get("passwordHash");
            if (hash == null) throw ApiError.conflict("Use the verified Google password setup flow.");
            if (!Passwords.matches(current, hash)) {
                db.jdbc.update("INSERT INTO password_change_failures(id,user_id) VALUES (?,?)", UUID.randomUUID(), owner);
                return false;
            }
            if (Passwords.matches(next, hash)) throw ApiError.invalid("Choose a different password");
            var auth = (JwtAuthenticationToken) SecurityContextHolder.getContext().getAuthentication();
            db.jdbc.update("UPDATE app_users SET password_hash=? WHERE id=?", Passwords.encode(next), owner);
            db.jdbc.update("DELETE FROM password_change_failures WHERE user_id=?", owner);
            db.jdbc.update("UPDATE auth_tokens SET used_at=now() WHERE user_id=? AND kind='RESET' AND used_at IS NULL", owner);
            db.jdbc.update("UPDATE auth_sessions SET revoked_at=now() WHERE user_id=? AND jti<>? AND revoked_at IS NULL",
                    owner, UUID.fromString(auth.getToken().getId()));
            db.audit("PASSWORD_CHANGED", "user", owner);
            return true;
        }));
        if (!changed) throw new ApiError(HttpStatus.BAD_REQUEST, "INCORRECT_CURRENT_PASSWORD", "The current password is incorrect.");
        return Map.of("message", "Password changed. Other sessions have been signed out.");
    }
}
