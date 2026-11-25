package com.careerforge.core;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.util.UUID;

@Service
public class NotificationService {

    private static final Logger log = LoggerFactory.getLogger(NotificationService.class);

    private final JdbcTemplate jdbc;

    public NotificationService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Call within the same transaction as the domain event whenever possible.
     */
    @Transactional
    public void create(UUID userId, String kind, String title, String body,
                       String targetUrl, String eventKey, boolean email) {

        log.debug("Creating notification: userId={}, kind={}, eventKey={}, email={}",
                userId, kind, eventKey, email);

        if (userId == null || eventKey == null || eventKey.isBlank()) {
            log.warn("Notification validation failed: userId={}, eventKey={}",
                    userId, eventKey);
            throw new IllegalArgumentException("Notification owner and event key are required");
        }

        if (targetUrl == null || !targetUrl.startsWith("/") || targetUrl.startsWith("//") || targetUrl.length() > 2048) {
            throw new IllegalArgumentException("Notification URL must be an internal path");
        }
        URI.create(targetUrl);

        UUID id = UUID.randomUUID();

        log.debug("Generated notification ID: notificationId={}, userId={}, eventKey={}",
                id, userId, eventKey);

        int created = jdbc.update("""
                INSERT INTO user_notifications
                  (id,user_id,kind,title,body,target_url,event_key)
                VALUES (?,?,?,?,?,?,?)
                ON CONFLICT (user_id,event_key) DO NOTHING
                """, id, userId, kind, title, body, targetUrl, eventKey);

        if (created == 1) {
            log.info("Notification created successfully: notificationId={}, userId={}, kind={}, eventKey={}",
                    id, userId, kind, eventKey);
        } else {
            log.debug("Notification already exists, skipping duplicate: userId={}, eventKey={}",
                    userId, eventKey);
        }

        if (created == 1 && email) {
            log.debug("Creating email outbox entry: notificationId={}, userId={}",
                    id, userId);

            jdbc.update("""
                    INSERT INTO notification_email_outbox (id,notification_id)
                    VALUES (?,?)
                    """, UUID.randomUUID(), id);

            log.info("Email outbox entry created successfully: notificationId={}, userId={}",
                    id, userId);
        }

        log.debug("Notification processing completed: userId={}, eventKey={}, created={}, email={}",
                userId, eventKey, created == 1, email);
    }
}

