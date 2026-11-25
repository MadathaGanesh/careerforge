package com.careerforge.api;

import com.careerforge.core.ApiError;
import com.careerforge.core.Db;
import com.careerforge.security.AuthRateLimit;
import com.careerforge.security.AuthSecurity.CookieAuth;
import com.careerforge.security.Passwords;
import com.careerforge.security.GoogleIdTokens;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.*;

import javax.crypto.Mac;
import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

@RestController
@RequestMapping("/api/v1")
public class AuthController {
    private static final SecureRandom RANDOM = new SecureRandom();
    private final Db db;
    private final JwtEncoder encoder;
    private final SecretKey key;
    private final JavaMailSender mail;
    private final AuthRateLimit limits;
    private final TransactionTemplate tx;
    private final GoogleIdTokens googleTokens;

    @Value("${app.dev-auth:false}") private boolean devAuth;
    @Value("${app.cookie-secure:false}") private boolean secure;
    @Value("${app.mail-from}") private String from;

    public AuthController(Db db, JwtEncoder encoder, SecretKey key, JavaMailSender mail,
                          AuthRateLimit limits, TransactionTemplate tx,
                          GoogleIdTokens googleTokens) {
        this.db = db;
        this.encoder = encoder;
        this.key = key;
        this.mail = mail;
        this.limits = limits;
        this.tx = tx;
        this.googleTokens = googleTokens;
    }

    private void issue(
            UUID userId,
            HttpServletResponse response
    ) {

        Instant now = Instant.now();

        String jti = UUID.randomUUID().toString();

        Instant expiry =
                now.plus(Duration.ofHours(8));

        JwtClaimsSet claims = JwtClaimsSet.builder()
                .id(jti)
                .issuer("careerforge")
                .subject(userId.toString())
                .issuedAt(now)
                .expiresAt(expiry)
                .build();

        JwsHeader header = JwsHeader
                .with(MacAlgorithm.HS256)
                .build();

        String jwt = encoder.encode(
                JwtEncoderParameters.from(header, claims)
        ).getTokenValue();

        db.jdbc.update(
                """
                        INSERT INTO auth_sessions
                        (jti, user_id, expires_at)
                        VALUES (?, ?, ?)
                        """,
                UUID.fromString(jti),
                userId,
                java.sql.Timestamp.from(expiry)
        );

        ResponseCookie sessionCookie = ResponseCookie
                .from("CF_SESSION", jwt)
                .httpOnly(true)
                .secure(secure)
                .sameSite("Lax")
                .path("/")
                .maxAge(Duration.ofHours(8))
                .build();

        ResponseCookie csrfCookie = ResponseCookie
                .from(
                        "CF_CSRF",
                        CookieAuth.csrfFor(jti, key)
                )
                .httpOnly(false)
                .secure(secure)
                .sameSite("Lax")
                .path("/")
                .maxAge(Duration.ofHours(8))
                .build();

        response.addHeader(
                HttpHeaders.SET_COOKIE,
                sessionCookie.toString()
        );

        response.addHeader(
                HttpHeaders.SET_COOKIE,
                csrfCookie.toString()
        );
    }


    @PostMapping("/auth/register")
    public Map<String, Object> register(@RequestBody IdentityRequests.Register body, HttpServletRequest request) {
        String email = normalizeEmail(body.email());
        limits.check("register", email, 3, 3600, request);
        String name = IdentityRequests.required(body.name(), "name");
        if (name.length() > 200) throw ApiError.invalid("Full name exceeds 200 characters");
        String hash = Passwords.encode(Passwords.input(body.password()));
        tx.executeWithoutResult(status -> {
            UUID id = create(email, name, hash, false);
            sendCode(id, email, "SIGNUP_OTP");
        });
        return Map.of("message", "Enter the six-digit code sent to your email.");
    }

    @PostMapping("/auth/login")
    public Map<String, Object> login(@RequestBody IdentityRequests.Login body, HttpServletRequest request,
                                    HttpServletResponse response) {
        String email = normalizeEmail(body.email());
        limits.check("login", email, 10, 900, request);
        String password = Passwords.input(body.password());
        tx.executeWithoutResult(status -> {
            var users = db.list("SELECT id,password_hash,email_verified FROM app_users WHERE email=? AND deleted_at IS NULL FOR UPDATE", email);
            if (users.isEmpty() || !Passwords.matches(password, (String) users.getFirst().get("passwordHash"))) {
                throw new ApiError(HttpStatus.UNAUTHORIZED, "LOGIN_FAILED", "Invalid email or password");
            }
            var user = users.getFirst();
            if (!Boolean.TRUE.equals(user.get("emailVerified"))) {
                throw new ApiError(HttpStatus.FORBIDDEN, "EMAIL_UNVERIFIED", "Verify your email before signing in");
            }
            issue((UUID) user.get("id"), response);
        });
        return Map.of("authenticated", true);
    }

    @PostMapping("/auth/dev")
    public Map<String, Object> dev(@RequestBody IdentityRequests.Email body, HttpServletResponse response) {
        if (!devAuth || secure) throw ApiError.notFound();
        String email = normalizeEmail(body.email());
        if (!email.endsWith("@example.test")) throw ApiError.invalid("Development login requires an @example.test email");
        tx.executeWithoutResult(status -> {
            var users = db.list("SELECT id FROM app_users WHERE email=? FOR UPDATE", email);
            UUID id = users.isEmpty() ? create(email, "Demo User", null, true) : (UUID) users.getFirst().get("id");
            issue(id, response);
        });
        return Map.of("authenticated", true, "mode", "DEVELOPMENT_ONLY");
    }

    @GetMapping("/auth/options")
    public Map<String, String> signInOptions() {
        return Map.of("googleClientId", googleTokens.clientId());
    }

    @PostMapping("/auth/google")
    public Map<String, Object> google(@RequestBody IdentityRequests.Google body, HttpServletRequest request,
                                     HttpServletResponse response) {
        limits.check("google", "", 20, 900, request);
        Jwt token = googleToken(body.credential());
        tx.executeWithoutResult(status -> {
            UUID linkedUser = googleOwner(token);
            if (linkedUser != null) {
                db.lockUser(linkedUser);
                issue(linkedUser, response);
                return;
            }
            String email = normalizeEmail(token.getClaimAsString("email"));
            if (!db.list("SELECT id FROM app_users WHERE email=?", email).isEmpty()) {
                throw new ApiError(HttpStatus.CONFLICT, "GOOGLE_LINK_REQUIRED",
                        "Sign in with your existing method, then link Google in Settings.");
            }
            String claimName = token.getClaimAsString("name");
            String name = claimName == null || claimName.isBlank() ? email : claimName;
            UUID id = create(email, name.substring(0, Math.min(name.length(), 200)), null, true);
            linkIdentity(id, token);
            issue(id, response);
        });
        return Map.of("authenticated", true);
    }

    @PostMapping("/users/me/link-google")
    public Map<String, Object> linkGoogle(@RequestBody IdentityRequests.GoogleWithPassword body, HttpServletRequest request) {
        UUID owner = db.uid();
        limits.check("link-google", owner.toString(), 5, 900, request);
        String password = Passwords.input(body.password());
        Jwt token = googleToken(body.credential());
        tx.executeWithoutResult(status -> {
            db.lockUser(owner);
            var user = db.one("SELECT email,password_hash,email_verified FROM app_users WHERE id=?", owner);
            if (!Boolean.TRUE.equals(user.get("emailVerified"))
                    || !Passwords.matches(password, (String) user.get("passwordHash"))) {
                throw new ApiError(HttpStatus.FORBIDDEN, "REAUTHENTICATION_REQUIRED", "Confirm your current password.");
            }
            if (!user.get("email").equals(normalizeEmail(token.getClaimAsString("email")))) {
                throw ApiError.invalid("Choose the Google identity with the same verified email.");
            }
            UUID linked = googleOwner(token);
            if (owner.equals(linked)) return;
            if (linked != null || !db.list("SELECT id FROM auth_identities WHERE user_id=? AND provider='GOOGLE'", owner).isEmpty()) {
                throw ApiError.conflict("This Google identity or account already has a link");
            }
            linkIdentity(owner, token);
        });
        return Map.of("message", "Google sign-in linked.");
    }

    @PostMapping("/users/me/setup-password")
    public Map<String, Object> setupPassword(@RequestBody IdentityRequests.GoogleWithPassword body, HttpServletRequest request) {
        UUID owner = db.uid();
        limits.check("setup-password", owner.toString(), 5, 900, request);
        Jwt token = googleToken(body.credential());
        String hash = Passwords.encode(Passwords.input(body.password()));
        tx.executeWithoutResult(status -> {
            db.lockUser(owner);
            requireGoogleOwner(owner, token);
            if (db.jdbc.update("UPDATE app_users SET password_hash=? WHERE id=? AND password_hash IS NULL", hash, owner) != 1) {
                throw ApiError.conflict("A password is already configured");
            }
            revokeOtherSessions(owner);
            db.audit("PASSWORD_SET_UP", "user", owner);
        });
        return Map.of("message", "Password sign-in is now available.");
    }

    @PostMapping("/users/me/email-change")
    public Map<String, Object> requestEmailChange(@RequestBody IdentityRequests.EmailChange body, HttpServletRequest request) {
        UUID owner = db.uid();
        String target = normalizeEmail(body.email());
        limits.check("email-change", owner.toString(), 3, 3600, request);
        tx.executeWithoutResult(status -> {
            db.lockUser(owner);
            var user = db.one("SELECT email,password_hash FROM app_users WHERE id=?", owner);
            if (user.get("passwordHash") != null) {
                if (!Passwords.matches(Passwords.input(body.password()), (String) user.get("passwordHash"))) {
                    throw new ApiError(HttpStatus.FORBIDDEN, "REAUTHENTICATION_REQUIRED", "Confirm your current password.");
                }
            } else {
                requireGoogleOwner(owner, googleToken(body.credential()));
            }
            if (!db.list("SELECT id FROM app_users WHERE email=?", target).isEmpty()) {
                throw ApiError.conflict("This email is already registered");
            }
            sendCode(owner, target, "CHANGE_EMAIL_OTP");
        });
        return Map.of("message", "Check your new email for a six-digit code.");
    }

    @PostMapping("/users/me/email-change/confirm")
    public Map<String, Object> confirmEmailChange(@RequestBody IdentityRequests.EmailCode body, HttpServletRequest request) {
        UUID owner = db.uid();
        String target = normalizeEmail(body.email());
        limits.check("confirm-email-change", owner.toString(), 5, 600, request);
        tx.executeWithoutResult(status -> {
            db.lockUser(owner);
            String old = (String) db.one("SELECT email FROM app_users WHERE id=?", owner).get("email");
            consumeCode(owner, "CHANGE_EMAIL_OTP", target, code(body.code()));
            db.jdbc.update("UPDATE app_users SET email=?,email_verified=true WHERE id=?", target, owner);
            invalidate(owner, "RESET");
            revokeOtherSessions(owner);
            db.audit("EMAIL_CHANGED", "user", owner);
            send(old, "CareerForge email changed", "Your CareerForge sign-in email was changed. Contact support if you did not request this.");
        });
        return Map.of("message", "Email verified and updated.");
    }

    @PostMapping("/auth/resend-verification")
    public Map<String, Object> resend(@RequestBody IdentityRequests.Email body, HttpServletRequest request) {
        String email = normalizeEmail(body.email());
        limits.check("resend-verification", email, 1, 60, request);
        limits.check("verification-mail", email, 5, 3600, request);
        tx.executeWithoutResult(status -> db.list("""
                SELECT id FROM app_users WHERE email=? AND NOT email_verified
                AND deleted_at IS NULL FOR UPDATE
                """, email).stream().findFirst().ifPresent(user -> sendCode((UUID) user.get("id"), email, "SIGNUP_OTP")));
        return Map.of("message", "If verification is pending, a new code was sent.");
    }

    @PostMapping("/auth/verify-email")
    public Map<String, Object> verify(@RequestBody IdentityRequests.VerifyEmail body, HttpServletRequest request) {
        if (body.token() != null) {
            limits.check("legacy-verify", "", 5, 600, request);
            tx.executeWithoutResult(status -> {
                String hash = db.fingerprint(IdentityRequests.required(body.token(), "token"));
                UUID owner = tokenOwner(hash, "VERIFY");
                db.lockUser(owner);
                consume(owner, hash, "VERIFY");
                invalidate(owner, "SIGNUP_OTP");
                db.jdbc.update("UPDATE app_users SET email_verified=true WHERE id=?", owner);
            });
        } else {
            String email = normalizeEmail(body.email());
            limits.check("verify", email, 5, 600, request);
            tx.executeWithoutResult(status -> {
                var users = db.list("SELECT id FROM app_users WHERE email=? AND deleted_at IS NULL FOR UPDATE", email);
                if (users.isEmpty()) throw badToken();
                UUID owner = (UUID) users.getFirst().get("id");
                consumeCode(owner, "SIGNUP_OTP", email, code(body.code()));
                invalidate(owner, "VERIFY");
                db.jdbc.update("UPDATE app_users SET email_verified=true WHERE id=?", owner);
            });
        }
        return Map.of("verified", true);
    }

    @PostMapping("/auth/forgot-password")
    public Map<String, Object> forgot(@RequestBody IdentityRequests.Email body, HttpServletRequest request) {
        String email = normalizeEmail(body.email());
        limits.check("forgot", email, 1, 60, request);
        limits.check("recovery-mail", email, 5, 3600, request);
        tx.executeWithoutResult(status -> db.list("""
                SELECT id FROM app_users WHERE email=? AND password_hash IS NOT NULL
                AND email_verified AND deleted_at IS NULL FOR UPDATE
                """, email).stream().findFirst().ifPresent(user -> {
            UUID owner = (UUID) user.get("id");
            invalidate(owner, "RESET");
            String token = UUID.randomUUID() + "." + UUID.randomUUID();
            storeToken(UUID.randomUUID(), owner, "RESET", db.fingerprint(token), null, 30);
            send(email, "CareerForge AI password reset", "Your one-time password reset token (expires in 30 minutes):\n\n"
                    + token + "\n\nNever share this token.");
        }));
        return Map.of("message", "If the account exists, password reset instructions were sent.");
    }

    @PostMapping("/auth/reset-password")
    public Map<String, Object> reset(@RequestBody IdentityRequests.Reset body, HttpServletRequest request) {
        limits.check("reset", "", 5, 600, request);
        String hash = db.fingerprint(IdentityRequests.required(body.token(), "token"));
        tx.executeWithoutResult(status -> {
            UUID owner = tokenOwner(hash, "RESET");
            db.lockUser(owner);
            consume(owner, hash, "RESET");
            db.jdbc.update("UPDATE app_users SET password_hash=? WHERE id=?",
                    Passwords.encode(Passwords.input(body.password())), owner);
            invalidate(owner, "RESET");
            db.jdbc.update("UPDATE auth_sessions SET revoked_at=now() WHERE user_id=? AND revoked_at IS NULL", owner);
        });
        return Map.of("reset", true);
    }

    @GetMapping("/auth/session")
    public Map<String, Object> session() {
        if (!(SecurityContextHolder.getContext().getAuthentication() instanceof JwtAuthenticationToken)) {
            return Map.of("authenticated", false);
        }
        return Map.of("authenticated", true, "user", db.one("""
                SELECT id,email,full_name,email_verified FROM app_users WHERE id=? AND deleted_at IS NULL
                """, db.uid()));
    }

    @PostMapping("/auth/logout")
    public Map<String, Object> logout(HttpServletResponse response) {
        var auth = (JwtAuthenticationToken) SecurityContextHolder.getContext().getAuthentication();
        db.jdbc.update("UPDATE auth_sessions SET revoked_at=now() WHERE jti=? AND user_id=?",
                UUID.fromString(auth.getToken().getId()), db.uid());
        for (String name : List.of("CF_SESSION", "CF_CSRF")) {
            response.addHeader(HttpHeaders.SET_COOKIE, ResponseCookie.from(name, "").path("/")
                    .httpOnly(name.equals("CF_SESSION")).secure(secure).sameSite("Lax").maxAge(0).build().toString());
        }
        return Map.of("authenticated", false);
    }

    private UUID create(String email, String name, String hash, boolean verified) {
        UUID id = UUID.randomUUID();
        db.jdbc.update("INSERT INTO app_users(id,email,full_name,password_hash,email_verified) VALUES (?,?,?,?,?)",
                id, email, name, hash, verified);
        db.jdbc.update("INSERT INTO user_profiles(user_id) VALUES (?)", id);
        db.jdbc.update("INSERT INTO subscriptions(user_id) VALUES (?)", id);
        return id;
    }

    private Jwt googleToken(String credential) {
        return googleTokens.verify(IdentityRequests.required(credential, "credential"));
    }

    private UUID googleOwner(Jwt token) {
        return db.list("SELECT user_id FROM auth_identities WHERE provider='GOOGLE' AND provider_subject=?", token.getSubject())
                .stream().findFirst().map(row -> (UUID) row.get("userId")).orElse(null);
    }

    private void requireGoogleOwner(UUID owner, Jwt token) {
        if (!owner.equals(googleOwner(token)) || token.getIssuedAt() == null
                || token.getIssuedAt().isBefore(Instant.now().minus(Duration.ofMinutes(5)))) {
            throw new ApiError(HttpStatus.FORBIDDEN, "REAUTHENTICATION_REQUIRED", "Sign in with the linked Google identity again.");
        }
    }

    private void linkIdentity(UUID owner, Jwt token) {
        db.jdbc.update("INSERT INTO auth_identities(id,user_id,provider,provider_subject) VALUES (?,?,'GOOGLE',?)",
                UUID.randomUUID(), owner, token.getSubject());
        if (SecurityContextHolder.getContext().getAuthentication() instanceof JwtAuthenticationToken) {
            db.audit("GOOGLE_LINKED", "user", owner);
        }
    }

    private void revokeOtherSessions(UUID owner) {
        var auth = (JwtAuthenticationToken) SecurityContextHolder.getContext().getAuthentication();
        db.jdbc.update("UPDATE auth_sessions SET revoked_at=now() WHERE user_id=? AND jti<>? AND revoked_at IS NULL",
                owner, UUID.fromString(auth.getToken().getId()));
    }

    private String normalizeEmail(String value) {
        String email = IdentityRequests.required(value, "email").strip().toLowerCase(Locale.ROOT);
        if (email.length() > 320 || !email.matches("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$")) {
            throw ApiError.invalid("Enter a valid email address");
        }
        return email;
    }

    private String code(String value) {
        String code = IdentityRequests.required(value, "code");
        if (!code.matches("[0-9]{6}")) throw ApiError.invalid("Enter the six-digit code");
        return code;
    }

    private String otpHash(UUID owner, UUID challenge, String kind, String email, String code) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(key);
            return HexFormat.of().formatHex(mac.doFinal(("otp:" + owner + ":" + challenge + ":" + kind + ":" + email + ":" + code)
                    .getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    private void sendCode(UUID owner, String email, String kind) {
        invalidate(owner, kind);
        String code = String.format(Locale.ROOT, "%06d", RANDOM.nextInt(1_000_000));
        UUID challenge = UUID.randomUUID();
        storeToken(challenge, owner, kind, otpHash(owner, challenge, kind, email, code), email, 10);
        send(email, "CareerForge verification code", "Your six-digit CareerForge code is " + code
                + ". It expires in 10 minutes. Never share it.");
    }

    private void send(String email, String subject, String text) {
        SimpleMailMessage message = new SimpleMailMessage();
        message.setTo(email);
        message.setFrom(from);
        message.setSubject(subject);
        message.setText(text);
        try {
            mail.send(message);
        } catch (org.springframework.mail.MailException e) {
            throw new ApiError(HttpStatus.SERVICE_UNAVAILABLE, "MAIL_UNAVAILABLE", "Email delivery is unavailable. Try again later.");
        }
    }

    private void storeToken(UUID id, UUID owner, String kind, String hash, String target, int minutes) {
        db.jdbc.update("INSERT INTO auth_tokens(id,user_id,kind,token_hash,target_email,expires_at) VALUES (?,?,?,?,?,?)",
                id, owner, kind, hash, target, Timestamp.from(Instant.now().plus(Duration.ofMinutes(minutes))));
    }

    private void consumeCode(UUID owner, String kind, String address, String code) {
        UUID challenge = db.list("""
                SELECT id FROM auth_tokens WHERE user_id=? AND kind=? AND target_email=?
                AND used_at IS NULL AND expires_at>now()
                """, owner, kind, address).stream().findFirst().map(row -> (UUID) row.get("id")).orElseThrow(this::badToken);
        consume(owner, otpHash(owner, challenge, kind, address, code), kind);
    }

    private UUID tokenOwner(String hash, String kind) {
        return db.list("SELECT user_id FROM auth_tokens WHERE token_hash=? AND kind=? AND used_at IS NULL AND expires_at>now()", hash, kind)
                .stream().findFirst().map(row -> (UUID) row.get("userId")).orElseThrow(this::badToken);
    }

    private void consume(UUID owner, String hash, String kind) {
        int count = db.jdbc.update("""
                UPDATE auth_tokens SET used_at=now() WHERE user_id=? AND token_hash=? AND kind=?
                AND used_at IS NULL AND expires_at>now()
                """, owner, hash, kind);
        if (count != 1) throw badToken();
    }

    private void invalidate(UUID owner, String kind) {
        db.jdbc.update("UPDATE auth_tokens SET used_at=now() WHERE user_id=? AND kind=? AND used_at IS NULL", owner, kind);
    }

    private ApiError badToken() {
        return new ApiError(HttpStatus.UNAUTHORIZED, "TOKEN_EXPIRED", "Invalid or expired verification code or token");
    }
}
