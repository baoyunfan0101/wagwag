package com.wagwag.api.notification;

import com.wagwag.api.pet.PetRepository;
import com.wagwag.api.realtime.RealtimeBus;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class NotificationService {
    private static final String SELECT = "SELECT n.id, n.actor_pet_id, p.name AS actor_name, n.created_at, n.read_at, "
        + "CASE WHEN n.message_id IS NOT NULL THEN 'MESSAGE' ELSE 'TASK_STATUS' END AS type, "
        + "COALESCE(m.conversation_id, e.task_id) AS target_id, t.title AS task_title, e.status AS task_status "
        + "FROM notifications n JOIN pets p ON p.id = n.actor_pet_id "
        + "LEFT JOIN messages m ON m.id = n.message_id LEFT JOIN task_events e ON e.id = n.task_event_id "
        + "LEFT JOIN tasks t ON t.id = e.task_id ";
    private final JdbcTemplate jdbc;
    private final PetRepository pets;
    private final RealtimeBus realtime;
    private final long devPetId;

    public NotificationService(JdbcTemplate jdbc, PetRepository pets, RealtimeBus realtime,
                               @Value("${app.dev-pet-id:0}") long devPetId) {
        this.jdbc = jdbc;
        this.pets = pets;
        this.realtime = realtime;
        this.devPetId = devPetId;
    }

    @Transactional
    public void message(long recipient, long actor, long messageId) {
        List<Long> ids = jdbc.queryForList("INSERT INTO notifications (recipient_pet_id, actor_pet_id, message_id) "
            + "VALUES (?, ?, ?) ON CONFLICT DO NOTHING RETURNING id", Long.class, recipient, actor, messageId);
        if (ids.isEmpty()) return;
        queuePush(ids.getFirst(), recipient);
        long conversationId = jdbc.queryForObject("SELECT conversation_id FROM messages WHERE id = ?", Long.class, messageId);
        RealtimeBus.Event event = new RealtimeBus.Event("MESSAGE", conversationId, messageId, actor);
        realtime.afterCommit(recipient, event);
        realtime.afterCommit(actor, event);
    }

    @Transactional
    public void taskStatus(long taskId, long actor, long eventId) {
        // Notify only the other established participant, including after a later social block.
        List<Target> targets = jdbc.query("INSERT INTO notifications (recipient_pet_id, actor_pet_id, task_event_id) "
            + "SELECT CASE WHEN t.creator_pet_id = ? THEN a.pet_id ELSE t.creator_pet_id END, ?, ? "
            + "FROM tasks t LEFT JOIN task_assignments a ON a.task_id = t.id WHERE t.id = ? "
            + "AND (CASE WHEN t.creator_pet_id = ? THEN a.pet_id ELSE t.creator_pet_id END) IS NOT NULL "
            + "ON CONFLICT DO NOTHING RETURNING id, recipient_pet_id", (rs, row) -> new Target(rs.getLong("id"),
                rs.getLong("recipient_pet_id")), actor, actor, eventId, taskId, actor);
        for (Target target : targets) {
            queuePush(target.id(), target.petId());
            realtime.afterCommit(target.petId(), new RealtimeBus.Event("NOTIFICATIONS", null, null, null));
        }
    }

    private void queuePush(long notificationId, long recipient) {
        jdbc.update("INSERT INTO push_deliveries (notification_id, device_id) "
            + "SELECT ?, id FROM push_devices WHERE pet_id = ? AND enabled ON CONFLICT DO NOTHING", notificationId, recipient);
    }

    @Transactional
    public void receipt(long actor, long peer, long conversationId, long through, boolean read, boolean changed) {
        int readNotifications = read ? jdbc.update("UPDATE notifications n SET read_at = CURRENT_TIMESTAMP "
            + "FROM messages m WHERE n.message_id = m.id AND n.recipient_pet_id = ? AND n.read_at IS NULL "
            + "AND m.conversation_id = ? AND m.id <= ?", actor, conversationId, through) : 0;
        if (changed) {
            RealtimeBus.Event event = new RealtimeBus.Event("RECEIPT", conversationId, null, null);
            realtime.afterCommit(peer, event);
            realtime.afterCommit(actor, event);
        } else if (readNotifications > 0) {
            realtime.afterCommit(actor, new RealtimeBus.Event("NOTIFICATIONS", null, null, null));
        }
    }

    @Transactional(readOnly = true)
    public UnreadCounts unread() {
        long actor = actorId();
        return jdbc.queryForObject("SELECT (SELECT COUNT(*) FROM messages m JOIN conversation_members me "
            + "ON me.conversation_id = m.conversation_id WHERE me.pet_id = ? AND m.sender_pet_id <> ? "
            + "AND m.id > me.last_read_message_id) AS messages, (SELECT COUNT(*) FROM notifications "
            + "WHERE recipient_pet_id = ? AND read_at IS NULL) AS notifications",
            (rs, row) -> new UnreadCounts(rs.getLong("messages"), rs.getLong("notifications")), actor, actor, actor);
    }

    @Transactional(readOnly = true)
    public NotificationPage list(int limit, Long beforeId) {
        if (limit < 1 || limit > 50 || (beforeId != null && beforeId < 1)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid notification page or limit");
        }
        long actor = actorId();
        List<Notification> rows = beforeId == null
            ? jdbc.query(SELECT + "WHERE n.recipient_pet_id = ? ORDER BY n.id DESC LIMIT ?",
                NotificationService::notification, actor, limit + 1)
            : jdbc.query(SELECT + "WHERE n.recipient_pet_id = ? AND n.id < ? ORDER BY n.id DESC LIMIT ?",
                NotificationService::notification, actor, beforeId, limit + 1);
        boolean more = rows.size() > limit;
        List<Notification> items = more ? rows.subList(0, limit) : rows;
        return new NotificationPage(items, more ? items.getLast().id() : null);
    }

    @Transactional
    public Notification read(long id) {
        long actor = actorId();
        int changed = jdbc.update("UPDATE notifications SET read_at = COALESCE(read_at, CURRENT_TIMESTAMP) "
            + "WHERE id = ? AND recipient_pet_id = ?", id, actor);
        if (changed == 0) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Notification not found");
        realtime.afterCommit(actor, new RealtimeBus.Event("NOTIFICATIONS", null, null, null));
        return jdbc.queryForObject(SELECT + "WHERE n.id = ? AND n.recipient_pet_id = ?",
            NotificationService::notification, id, actor);
    }

    private long actorId() {
        if (devPetId < 1 || !pets.existsById(devPetId)) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Development pet is missing");
        }
        return devPetId;
    }

    private static Notification notification(ResultSet rs, int row) throws SQLException {
        var readAt = rs.getTimestamp("read_at");
        return new Notification(rs.getLong("id"), rs.getString("type"), rs.getLong("actor_pet_id"),
            rs.getString("actor_name"), rs.getLong("target_id"), rs.getString("task_title"),
            rs.getString("task_status"), rs.getTimestamp("created_at").toInstant(),
            readAt == null ? null : readAt.toInstant());
    }

    public record Notification(long id, String type, long actorPetId, String actorName, long targetId,
                               String taskTitle, String taskStatus, Instant createdAt, Instant readAt) {}
    public record NotificationPage(List<Notification> items, Long nextBeforeId) {}
    public record UnreadCounts(long messages, long notifications) {}
    private record Target(long id, long petId) {}
}
