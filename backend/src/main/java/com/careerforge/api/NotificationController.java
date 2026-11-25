package com.careerforge.api;

import com.careerforge.core.Db;
import com.careerforge.core.CursorPage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/notifications")
public class NotificationController {

    private static final Logger log = LoggerFactory.getLogger(NotificationController.class);

    private final Db db;

    public NotificationController(Db db) {
        this.db = db;
    }

    @GetMapping
    public List<Map<String, Object>> list(@RequestParam(defaultValue = "50") int limit, @RequestParam(defaultValue = "0") int page) {

        int safeLimit = Math.max(1, Math.min(limit, 100));

        log.debug("Fetching notifications: requestedLimit={}, safeLimit={}",
                limit, safeLimit);

        List<Map<String, Object>> notifications = db.list("""
                SELECT id,kind,title,body,target_url,is_read,created_at,read_at
                FROM user_notifications WHERE user_id=?
                ORDER BY created_at DESC,id DESC LIMIT ? OFFSET ?
                """, db.uid(), safeLimit, Math.max(0L, page) * safeLimit);

        log.debug("Notifications fetched successfully: count={}, safeLimit={}",
                notifications.size(), safeLimit);

        return notifications;
    }

    @GetMapping("/page")
    public CursorPage<Map<String, Object>> page(@RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "25") int size,
            @RequestParam(defaultValue = "false") boolean unreadOnly) {
        int limit = CursorPage.limit(size);
        var position = CursorPage.decode(cursor);
        String query = "SELECT id,kind,title,body,target_url,is_read,created_at,read_at FROM user_notifications WHERE user_id=?";
        if (unreadOnly) query += " AND is_read=false";
        String order = " ORDER BY created_at DESC,id DESC LIMIT ?";
        var rows = position == null ? db.list(query + order, db.uid(), limit + 1)
                : db.list(query + " AND (created_at,id)<(?,?)" + order, db.uid(), position.timestamp(), position.id(), limit + 1);
        return CursorPage.rows(rows, limit, "createdAt");
    }

    @GetMapping("/unread-count")
    public Map<String, Object> unreadCount() {

        log.debug("Fetching unread notification count");

        Integer count = db.jdbc.queryForObject("""
                SELECT count(*) FROM user_notifications WHERE user_id=? AND is_read=false
                """, Integer.class, db.uid());

        log.debug("Unread notification count fetched successfully: count={}",
                count);

        return Map.of("count", count == null ? 0 : count);
    }

    @PostMapping("/{id}/read")
    public ResponseEntity<Void> markRead(@PathVariable UUID id) {

        log.debug("Marking notification as read: notificationId={}", id);

        // A notification from another user is never modified.
        int count = db.jdbc.update("""
                UPDATE user_notifications SET is_read=true,read_at=coalesce(read_at,now())
                WHERE id=? AND user_id=?
                """, id, db.uid());

        if (count == 0) {
            log.debug("Notification not found or not owned by current user: notificationId={}",
                    id);
        } else {
            log.info("Notification marked as read successfully: notificationId={}",
                    id);
        }

        return count == 0
                ? ResponseEntity.notFound().build()
                : ResponseEntity.noContent().build();
    }

    @PostMapping("/read-all")
    public Map<String, Object> readAll() {

        log.debug("Marking all unread notifications as read");

        int changed = db.jdbc.update("""
                UPDATE user_notifications SET is_read=true,read_at=now()
                WHERE user_id=? AND is_read=false
                """, db.uid());

        log.info("Bulk notification read operation completed: updatedCount={}",
                changed);

        return Map.of("updated", changed);
    }
}

