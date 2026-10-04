package com.wagwag.api.notification;

import com.wagwag.api.pet.PetRepository;
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
    private final long devPetId;

    public NotificationService(JdbcTemplate jdbc, PetRepository pets, @Value("${app.dev-pet-id:0}") long devPetId) {
        this.jdbc = jdbc;
        this.pets = pets;
        this.devPetId = devPetId;
    }

    @Transactional
    public void message(long recipient, long actor, long messageId) {
        jdbc.update("INSERT INTO notifications (recipient_pet_id, actor_pet_id, message_id) VALUES (?, ?, ?) "
            + "ON CONFLICT DO NOTHING", recipient, actor, messageId);
    }

    @Transactional
    public void taskStatus(long taskId, long actor, long eventId) {
        // Notify only the other established participant, including after a later social block.
        jdbc.update("INSERT INTO notifications (recipient_pet_id, actor_pet_id, task_event_id) "
            + "SELECT CASE WHEN t.creator_pet_id = ? THEN a.pet_id ELSE t.creator_pet_id END, ?, ? "
            + "FROM tasks t LEFT JOIN task_assignments a ON a.task_id = t.id WHERE t.id = ? "
            + "AND (CASE WHEN t.creator_pet_id = ? THEN a.pet_id ELSE t.creator_pet_id END) IS NOT NULL "
            + "ON CONFLICT DO NOTHING", actor, actor, eventId, taskId, actor);
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
}
