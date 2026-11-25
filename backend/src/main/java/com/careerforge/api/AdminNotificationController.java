package com.careerforge.api;

import com.careerforge.core.ApiError;
import com.careerforge.core.Db;
import com.careerforge.core.NotificationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/admin/notifications")
public class AdminNotificationController {

    private static final Logger log = LoggerFactory.getLogger(AdminNotificationController.class);

    private final Db db;
    private final NotificationService notifications;

    public AdminNotificationController(Db db, NotificationService notifications) {
        this.db = db;
        this.notifications = notifications;
    }

    /**
     * Only users explicitly listed in notification_admins may send these.
     */
    @Transactional
    @PostMapping
    public Map<String, Object> send(@RequestBody WorkspaceRequests.Notification body) {

        UUID admin = db.uid();

        log.debug("Admin notification request received: adminId={}", admin);

        log.debug("Verifying notification administrator permissions: adminId={}",
                admin);

        Integer allowed = db.jdbc.queryForObject(
                "SELECT count(*) FROM notification_admins WHERE user_id=?",
                Integer.class, admin);

        if (allowed == null || allowed == 0) {
            log.warn("Unauthorized admin notification attempt: adminId={}",
                    admin);

            throw new ApiError(HttpStatus.FORBIDDEN, "ADMIN_ONLY",
                    "Administrator access required");
        }

        log.debug("Notification administrator authorization successful: adminId={}",
                admin);

        String title = IdentityRequests.required(body.title(), "title");
        String message = IdentityRequests.required(body.message(), "message");

        log.debug("Admin notification required fields validated: adminId={}, titleLength={}, messageLength={}",
                admin, title.length(), message.length());

        if (title.length() > 200 || message.length() > 4000) {
            log.warn("Admin notification validation failed: message too long, adminId={}, titleLength={}, messageLength={}",
                    admin, title.length(), message.length());

            throw ApiError.invalid("Message is too long");
        }

        UUID userId = body.userId();
        if (userId == null) throw ApiError.invalid("Valid recipient userId required");

        log.debug("Admin notification recipient parsed successfully: adminId={}, recipientId={}",
                admin, userId);

        log.debug("Verifying notification recipient exists: recipientId={}",
                userId);

        List<Map<String, Object>> exists = db.list(
                "SELECT id FROM app_users WHERE id=? AND deleted_at IS NULL",
                userId);

        if (exists.isEmpty()) {
            log.warn("Admin notification recipient not found or deleted: adminId={}, recipientId={}",
                    admin, userId);

            throw ApiError.notFound();
        }

        log.debug("Admin notification recipient verified successfully: recipientId={}",
                userId);

        boolean customTarget = body.targetUrl() != null && !body.targetUrl().isBlank();
        String target = customTarget ? body.targetUrl() : "/dashboard";

        log.debug("Admin notification target resolved: adminId={}, recipientId={}, customTarget={}",
                admin, userId, customTarget);

        boolean email = body.email();

        log.debug("Creating admin notification: adminId={}, recipientId={}, emailEnabled={}",
                admin, userId, email);

        notifications.create(
                userId,
                "ADMIN_MESSAGE",
                title,
                message,
                target,
                "admin:" + UUID.randomUUID(),
                email
        );

        log.debug("Admin notification creation completed: adminId={}, recipientId={}, emailEnabled={}",
                admin, userId, email);

        log.debug("Recording admin notification audit event: adminId={}, recipientId={}",
                admin, userId);

        db.audit("ADMIN_NOTIFICATION_SENT", "user", userId);

        log.info("Admin notification processed successfully: adminId={}, recipientId={}, emailEnabled={}",
                admin, userId, email);

        return Map.of("sent", true);
    }
}

