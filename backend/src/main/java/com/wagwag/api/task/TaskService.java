package com.wagwag.api.task;

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
public class TaskService {
    private static final String SELECT = "SELECT t.id, t.creator_pet_id, creator.name AS creator_name, "
        + "t.title, t.description, t.category, t.latitude, t.longitude, t.status, "
        + "t.created_at, t.updated_at, a.pet_id AS assignee_pet_id, assignee.name AS assignee_name "
        + "FROM tasks t JOIN pets creator ON creator.id = t.creator_pet_id "
        + "LEFT JOIN task_assignments a ON a.task_id = t.id "
        + "LEFT JOIN pets assignee ON assignee.id = a.pet_id ";

    private final JdbcTemplate jdbc;
    private final PetRepository pets;
    private final long devPetId;

    public TaskService(JdbcTemplate jdbc, PetRepository pets,
                       @Value("${app.dev-pet-id:0}") long devPetId) {
        this.jdbc = jdbc;
        this.pets = pets;
        this.devPetId = devPetId;
    }

    @Transactional
    public TaskResponse create(TaskInput input) {
        long actor = actorId();
        if (!Double.isFinite(input.latitude()) || !Double.isFinite(input.longitude())
            || input.latitude() < -90 || input.latitude() > 90
            || input.longitude() < -180 || input.longitude() > 180) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid task location");
        }
        long id = jdbc.queryForObject("INSERT INTO tasks (creator_pet_id, title, description, category, "
            + "latitude, longitude) VALUES (?, ?, ?, ?, ?, ?) RETURNING id", Long.class,
            actor, input.title().trim(), input.description().trim(), input.category().name(),
            input.latitude(), input.longitude());
        return detail(id);
    }

    @Transactional(readOnly = true)
    public TaskResponse detail(long id) {
        actorId();
        List<TaskResponse> rows = jdbc.query(SELECT + "WHERE t.id = ?", TaskService::response, id);
        if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Task not found");
        return rows.getFirst();
    }

    @Transactional(readOnly = true)
    public TaskPage list(String scope, int limit, int page) {
        long actor = actorId();
        if (limit < 1 || limit > 50 || page < 0 || (long) limit * page > Integer.MAX_VALUE) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid task page or limit");
        }
        String filter;
        Object[] args;
        if ("open".equals(scope)) {
            filter = "WHERE t.status = 'OPEN' ";
            args = new Object[] {limit + 1, limit * page};
        } else if ("mine".equals(scope)) {
            filter = "WHERE t.creator_pet_id = ? OR a.pet_id = ? ";
            args = new Object[] {actor, actor, limit + 1, limit * page};
        } else {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid task scope");
        }
        List<TaskResponse> rows = jdbc.query(SELECT + filter
            + "ORDER BY t.created_at DESC, t.id DESC LIMIT ? OFFSET ?", TaskService::response, args);
        boolean more = rows.size() > limit;
        return new TaskPage(more ? rows.subList(0, limit) : rows, more ? page + 1 : null);
    }

    @Transactional
    public TaskResponse accept(long id) {
        long actor = actorId();
        LockedTask task = lock(id);
        if (task.creatorPetId() == actor) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Cannot accept your own task");
        }
        if ("ACCEPTED".equals(task.status()) && task.assigneePetId() != null
            && actor == task.assigneePetId()) return detail(id);
        requireStatus(task, "OPEN");
        jdbc.update("INSERT INTO task_assignments (task_id, pet_id) VALUES (?, ?)", id, actor);
        jdbc.update("UPDATE tasks SET status = 'ACCEPTED', updated_at = CURRENT_TIMESTAMP WHERE id = ?", id);
        return detail(id);
    }

    @Transactional
    public TaskResponse start(long id) {
        long actor = actorId();
        LockedTask task = lock(id);
        if (task.assigneePetId() == null || task.assigneePetId() != actor) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only the assigned pet can start this task");
        }
        requireStatus(task, "ACCEPTED");
        jdbc.update("UPDATE tasks SET status = 'IN_PROGRESS', updated_at = CURRENT_TIMESTAMP WHERE id = ?", id);
        return detail(id);
    }

    @Transactional
    public TaskResponse complete(long id) {
        long actor = actorId();
        LockedTask task = lock(id);
        if (task.assigneePetId() == null || task.assigneePetId() != actor) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only the assigned pet can complete this task");
        }
        requireStatus(task, "IN_PROGRESS");
        jdbc.update("UPDATE tasks SET status = 'COMPLETED', updated_at = CURRENT_TIMESTAMP WHERE id = ?", id);
        return detail(id);
    }

    @Transactional
    public TaskResponse cancel(long id) {
        long actor = actorId();
        LockedTask task = lock(id);
        if (task.creatorPetId() != actor) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only the creator can cancel this task");
        }
        if (!"OPEN".equals(task.status()) && !"ACCEPTED".equals(task.status())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Task cannot be cancelled now");
        }
        jdbc.update("UPDATE tasks SET status = 'CANCELLED', updated_at = CURRENT_TIMESTAMP WHERE id = ?", id);
        return detail(id);
    }

    private LockedTask lock(long id) {
        List<LockedTask> rows = jdbc.query("SELECT t.creator_pet_id, t.status, a.pet_id AS assignee_pet_id "
            + "FROM tasks t LEFT JOIN task_assignments a ON a.task_id = t.id WHERE t.id = ? FOR UPDATE OF t",
            (rs, row) -> new LockedTask(rs.getLong("creator_pet_id"), rs.getString("status"),
                rs.getObject("assignee_pet_id", Long.class)), id);
        if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Task not found");
        return rows.getFirst();
    }

    private static void requireStatus(LockedTask task, String expected) {
        if (!expected.equals(task.status())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Task is not " + expected);
        }
    }

    private long actorId() {
        if (devPetId < 1 || !pets.existsById(devPetId)) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Development pet is missing");
        }
        return devPetId;
    }

    private static TaskResponse response(ResultSet rs, int row) throws SQLException {
        return new TaskResponse(rs.getLong("id"), rs.getLong("creator_pet_id"),
            rs.getString("creator_name"), rs.getString("title"), rs.getString("description"),
            TaskInput.Category.valueOf(rs.getString("category")), rs.getDouble("latitude"),
            rs.getDouble("longitude"), rs.getString("status"),
            rs.getObject("assignee_pet_id", Long.class), rs.getString("assignee_name"),
            rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("updated_at").toInstant());
    }

    private record LockedTask(long creatorPetId, String status, Long assigneePetId) {}

    public record TaskResponse(long id, long creatorPetId, String creatorName, String title,
                               String description, TaskInput.Category category, double latitude,
                               double longitude, String status, Long assigneePetId, String assigneeName,
                               Instant createdAt, Instant updatedAt) {}
    public record TaskPage(List<TaskResponse> items, Integer nextPage) {}
}
