package com.careerforge.core;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Replaces the old send-only-when-due ReminderScheduler.
 */
@Component
public class ReminderScheduler {
    private static final Logger log = LoggerFactory.getLogger(ReminderScheduler.class);
    private final JdbcTemplate jdbc;
    private final NotificationService notifications;
    private final JavaMailSender mail;
    private final ZoneId zone;
    private final String from;
    private final String origin;

    public ReminderScheduler(JdbcTemplate jdbc, NotificationService notifications,
                             JavaMailSender mail,
                             @Value("${app.notification-zone:Asia/Kolkata}") String zoneId,
                             @Value("${app.mail-from}") String from,
                             @Value("${app.public-origin}") String origin) {
        this.jdbc = jdbc;
        this.notifications = notifications;
        this.mail = mail;
        this.zone = ZoneId.of(zoneId);
        this.from = from;
        this.origin = origin.replaceAll("/+$", "");
    }

    private static Instant instant(Object value) {
        if (value instanceof Timestamp ts) return ts.toInstant();
        if (value instanceof OffsetDateTime t) return t.toInstant();
        if (value instanceof Instant i) return i;
        throw new IllegalArgumentException("Unsupported timestamp: " + value.getClass().getSimpleName());
    }

    @Scheduled(fixedDelayString = "${app.notification-poll-ms:60000}")
    public void sendDue() {
        try {
            createReminderNotifications();
        } catch (Exception ex) {
            log.error("Reminder notification scan failed", ex);
        }
        try {
            createOverdueReminderNotifications();
        } catch (Exception ex) {
            log.error("Overdue reminder recovery failed", ex);
        }
        try {
            createSubscriptionNotifications();
        } catch (Exception ex) {
            log.error("Subscription notification scan failed", ex);
        }
        try {
            sendPendingEmails();
        } catch (Exception ex) {
            log.error("Notification email worker failed", ex);
        }
    }

    @Scheduled(cron = "0 0 3 * * *", zone = "UTC")
    public void cleanupExpiredPasswordChangeFailures() {
        jdbc.update("DELETE FROM password_change_failures WHERE attempted_at < now()-interval '30 days'");
    }

    private void createReminderNotifications() {
        LocalDate today = LocalDate.now(zone);
        List<Map<String, Object>> reminders = jdbc.queryForList("""
                SELECT r.id,r.user_id,r.title,r.due_at,u.email_verified
                FROM reminders r JOIN app_users u ON u.id=r.user_id
                WHERE r.completed=false AND u.deleted_at IS NULL
                  AND r.due_at >= now()-interval '1 day'
                  AND r.due_at < now()+interval '2 days'
                ORDER BY r.due_at LIMIT 500
                """);
        for (var r : reminders) {
            UUID reminderId = (UUID) r.get("id");
            UUID userId = (UUID) r.get("user_id");
            LocalDate dueDate = instant(r.get("due_at")).atZone(zone).toLocalDate();
            long remaining = ChronoUnit.DAYS.between(today, dueDate);
            if (remaining != 1 && remaining != 0) continue;
            String phase = remaining == 1 ? "1d" : "today";
            String title = remaining == 1 ? "Reminder due tomorrow" : "Reminder due today";
            String description = String.valueOf(r.get("title"));
            String body = description + " (" + dueDate + ")";
            notifications.create(userId, "FOLLOW_UP", title, body, "/applications",
                    "reminder:" + reminderId + ":" + phase,
                    Boolean.TRUE.equals(r.get("email_verified")));
        }
    }

    /**
     * Preserve the previous scheduler's overdue-delivery behavior.
     * A reminder missed while the server was offline is queued once, but a
     * reminder already notified on its due date is not sent again.
     */
    private void createOverdueReminderNotifications() {
        Instant startToday = LocalDate.now(zone).atStartOfDay(zone).toInstant();
        List<Map<String, Object>> overdue = jdbc.queryForList("""
                SELECT r.id,r.user_id,r.title,r.due_at,u.email_verified
                FROM reminders r JOIN app_users u ON u.id=r.user_id
                WHERE r.completed=false AND u.deleted_at IS NULL
                  AND r.notified_at IS NULL
                  AND r.due_at < ?
                  AND NOT EXISTS (
                    SELECT 1 FROM user_notifications n
                    WHERE n.user_id=r.user_id
                      AND n.event_key IN (
                        'reminder:' || r.id::text || ':today',
                        'reminder:' || r.id::text || ':overdue'
                      )
                  )
                ORDER BY r.due_at ASC LIMIT 200
                """, Timestamp.from(startToday));
        for (var r : overdue) {
            UUID reminderId = (UUID) r.get("id");
            UUID userId = (UUID) r.get("user_id");
            LocalDate dueDate = instant(r.get("due_at")).atZone(zone).toLocalDate();
            notifications.create(userId, "FOLLOW_UP", "Overdue follow-up reminder",
                    String.valueOf(r.get("title")) + " (was due " + dueDate + ")",
                    "/applications", "reminder:" + reminderId + ":overdue",
                    Boolean.TRUE.equals(r.get("email_verified")));
        }
    }

    private void createSubscriptionNotifications() {
        LocalDate today = LocalDate.now(zone);
        List<Map<String, Object>> subscriptions = jdbc.queryForList("""
                SELECT s.user_id,s.period_end,u.email_verified
                FROM subscriptions s JOIN app_users u ON u.id=s.user_id
                WHERE s.plan='PRO' AND s.status='ACTIVE'
                  AND s.period_end IS NOT NULL AND u.deleted_at IS NULL
                  AND s.period_end > now()
                  AND s.period_end < now()+interval '9 days'
                LIMIT 1000
                """);
        for (var s : subscriptions) {
            LocalDate endDate = instant(s.get("period_end")).atZone(zone).toLocalDate();
            long days = ChronoUnit.DAYS.between(today, endDate);
            if (days != 7 && days != 3 && days != 1) continue;
            UUID userId = (UUID) s.get("user_id");
            notifications.create(userId, "SUBSCRIPTION_EXPIRY",
                    "Subscription period ends in " + days + " day" + (days == 1 ? "" : "s"),
                    "Your CareerForge Pro subscription period ends on " + endDate +
                            ". Check your billing settings to confirm renewal status.",
                    "/settings", "subscription:" + endDate + ":" + days,
                    Boolean.TRUE.equals(s.get("email_verified")));
        }
    }

    /**
     * Claims work atomically; safe against concurrent app instances with an expired lease.
     */
    private void sendPendingEmails() {
        for (int i = 0; i < 30; i++) {
            List<Map<String, Object>> claim = jdbc.queryForList("""
                    WITH picked AS (
                      SELECT id FROM notification_email_outbox
                      WHERE next_attempt_at<=now()
                        AND (status='PENDING' OR (status='PROCESSING' AND lease_until<now()))
                      ORDER BY created_at FOR UPDATE SKIP LOCKED LIMIT 1
                    )
                    UPDATE notification_email_outbox o
                    SET status='PROCESSING',attempts=attempts+1,
                        lease_until=now()+interval '2 minutes'
                    FROM picked WHERE o.id=picked.id
                    RETURNING o.id,o.notification_id,o.attempts
                    """);
            if (claim.isEmpty()) return;
            Map<String, Object> work = claim.get(0);
            UUID deliveryId = (UUID) work.get("id");
            int attempts = ((Number) work.get("attempts")).intValue();
            try {
                List<Map<String, Object>> targets = jdbc.queryForList("""
                        SELECT u.email,n.title,n.body,n.target_url,n.event_key,n.kind
                        FROM user_notifications n JOIN app_users u ON u.id=n.user_id
                        WHERE n.id=? AND u.deleted_at IS NULL AND u.email_verified=true
                        """, work.get("notification_id"));
                if (!targets.isEmpty()) {
                    var data = targets.get(0);
                    SimpleMailMessage message = new SimpleMailMessage();
                    message.setFrom(from);
                    message.setTo((String) data.get("email"));
                    message.setSubject("CareerForge AI — " + data.get("title"));
                    message.setText(data.get("body") + "\n\nOpen CareerForge: " + origin + data.get("target_url"));
                    mail.send(message);
                    // Keep legacy notified_at meaningful when a due/overdue
                    // email is successfully delivered by the new outbox worker.
                    if ("FOLLOW_UP".equals(data.get("kind"))) {
                        String eventKey = String.valueOf(data.get("event_key"));
                        if (eventKey.endsWith(":today") || eventKey.endsWith(":overdue")) {
                            String[] segments = eventKey.split(":");
                            if (segments.length == 3) {
                                try {
                                    UUID reminderId = UUID.fromString(segments[1]);
                                    jdbc.update("""
                                            UPDATE reminders SET notified_at=coalesce(notified_at,now())
                                            WHERE id=?
                                            """, reminderId);
                                } catch (IllegalArgumentException ignored) {
                                    log.warn("Unexpected reminder event key format");
                                }
                            }
                        }
                    }
                }
                jdbc.update("""
                        UPDATE notification_email_outbox
                        SET status='SENT',sent_at=now(),lease_until=NULL,last_error=NULL
                        WHERE id=?
                        """, deliveryId);
            } catch (Exception ex) {
                log.warn("Notification email delivery attempt failed: {}", ex.getClass().getSimpleName());
                int minutes = Math.min(60, 5 * attempts);
                jdbc.update("""
                                UPDATE notification_email_outbox
                                SET status=?,lease_until=NULL,
                                    next_attempt_at=now()+(? * interval '1 minute'),last_error=?
                                WHERE id=?
                                """, attempts >= 8 ? "FAILED" : "PENDING", minutes,
                        ex.getClass().getSimpleName(), deliveryId);
            }
        }
    }
}
