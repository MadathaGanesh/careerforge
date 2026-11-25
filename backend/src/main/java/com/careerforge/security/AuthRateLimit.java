package com.careerforge.security;

import com.careerforge.core.ApiError;
import com.careerforge.core.Db;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.sql.Timestamp;

@Service
public class AuthRateLimit {
    private final Db db;
    private final org.springframework.core.env.Environment settings;

    public AuthRateLimit(Db db, org.springframework.core.env.Environment settings) {
        this.db = db;
        this.settings = settings;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void check(String action, String account, int limit, int seconds, HttpServletRequest request) {
        String prefix = "app.auth-rate-limit." + action;
        int window = Math.clamp(settings.getProperty(prefix + ".window-seconds", Integer.class, seconds), 10, 3600);
        int accountLimit = Math.clamp(settings.getProperty(prefix + ".account-requests", Integer.class, limit), 1, 1000);
        int ipLimit = Math.clamp(settings.getProperty(prefix + ".ip-requests", Integer.class, limit * 10), 1, 10000);
        attempt(action + ":ip:" + request.getRemoteAddr(), ipLimit, window);
        if (!account.isEmpty()) attempt(action + ":account:" + account, accountLimit, window);
    }

    private void attempt(String bucket, int limit, int seconds) {
        long now = Instant.now().getEpochSecond();
        Timestamp window = Timestamp.from(Instant.ofEpochSecond(now - now % seconds));
        var accepted = db.list("""
                INSERT INTO auth_rate_limits(bucket_key,window_start,attempts) VALUES (?,?,1)
                ON CONFLICT (bucket_key,window_start) DO UPDATE
                SET attempts=auth_rate_limits.attempts+1 WHERE auth_rate_limits.attempts < ?
                RETURNING attempts
                """, db.fingerprint(bucket), window, limit);
        if (accepted.isEmpty()) {
            throw new ApiError(HttpStatus.TOO_MANY_REQUESTS, "TOO_MANY_ATTEMPTS",
                    "Too many requests. Please wait and try again.");
        }
    }

    @Scheduled(fixedDelayString = "PT1H")
    public void clean() {
        db.jdbc.update("DELETE FROM auth_rate_limits WHERE window_start < now()-interval '1 day'");
        db.jdbc.update("DELETE FROM auth_tokens WHERE expires_at < now()-interval '1 day'");
        db.jdbc.update("DELETE FROM auth_sessions WHERE expires_at < now()-interval '7 days'");
    }
}
