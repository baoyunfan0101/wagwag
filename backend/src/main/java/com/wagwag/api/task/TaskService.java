package com.wagwag.api.task;

import com.wagwag.api.pet.PetRepository;
import com.wagwag.api.social.SocialPairLock;
import com.wagwag.api.social.SocialRestrictions;
import java.math.BigDecimal;
import java.math.RoundingMode;
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
    private static final String COLUMNS = "t.id, t.creator_pet_id, creator.name AS creator_name, "
        + "t.title, t.description, t.category, t.latitude, t.longitude, t.status, "
        + "t.created_at, t.updated_at, a.pet_id AS assignee_pet_id, assignee.name AS assignee_name, "
        + "r.score AS rating_score, r.comment AS rating_comment, r.updated_at AS rating_updated_at ";
    private static final String FROM = "FROM tasks t JOIN pets creator ON creator.id = t.creator_pet_id "
        + "LEFT JOIN task_assignments a ON a.task_id = t.id "
        + "LEFT JOIN pets assignee ON assignee.id = a.pet_id "
        + "LEFT JOIN task_ratings r ON r.task_id = t.id ";
    private static final String SELECT = "SELECT " + COLUMNS + FROM;
    private static final String NOT_BLOCKED = "NOT EXISTS (SELECT 1 FROM pet_blocks b "
        + "WHERE (b.blocker_pet_id = ? AND b.blocked_pet_id = t.creator_pet_id) "
        + "OR (b.blocker_pet_id = t.creator_pet_id AND b.blocked_pet_id = ?)) ";

    private final JdbcTemplate jdbc;
    private final PetRepository pets;
    private final SocialRestrictions social;
    private final SocialPairLock pairLock;
    private final long devPetId;

    public TaskService(JdbcTemplate jdbc, PetRepository pets, SocialRestrictions social, SocialPairLock pairLock,
                       @Value("${app.dev-pet-id:0}") long devPetId) {
        this.jdbc = jdbc;
        this.pets = pets;
        this.social = social;
        this.pairLock = pairLock;
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
        event(id, actor, "OPEN");
        return detail(id);
    }

    @Transactional(readOnly = true)
    public TaskResponse detail(long id) {
        long actor = actorId();
        List<TaskRecord> rows = jdbc.query(SELECT + "WHERE t.id = ?", TaskService::record, id);
        if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Task not found");
        TaskRecord task = rows.getFirst();
        if (!task.participant(actor) && social.blockedEitherWay(actor, task.creatorPetId())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Task not found");
        }
        return response(task, actor);
    }

    @Transactional(readOnly = true)
    public TaskPage list(String scope, int limit, int page) {
        long actor = actorId();
        validatePage(limit, page);
        String filter;
        Object[] args;
        if ("open".equals(scope)) {
            filter = "WHERE t.status = 'OPEN' AND " + NOT_BLOCKED;
            args = new Object[] {actor, actor, limit + 1, limit * page};
        } else if ("mine".equals(scope)) {
            filter = "WHERE t.creator_pet_id = ? OR a.pet_id = ? ";
            args = new Object[] {actor, actor, limit + 1, limit * page};
        } else {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid task scope");
        }
        List<TaskRecord> rows = jdbc.query(SELECT + filter
            + "ORDER BY t.created_at DESC, t.id DESC LIMIT ? OFFSET ?", TaskService::record, args);
        boolean more = rows.size() > limit;
        List<TaskRecord> pageRows = more ? rows.subList(0, limit) : rows;
        return new TaskPage(pageRows.stream().map(task -> response(task, actor)).toList(), more ? page + 1 : null);
    }

    @Transactional(readOnly = true)
    public NearbyTaskPage nearby(double latitude, double longitude, double radiusMeters, int limit, int page) {
        validatePage(limit, page);
        if (!Double.isFinite(latitude) || !Double.isFinite(longitude) || !Double.isFinite(radiusMeters)
            || latitude < -90 || latitude > 90 || longitude < -180 || longitude > 180
            || radiusMeters < 1 || radiusMeters > 20000) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid nearby location or radius");
        }
        long actor = actorId();
        // Non-participants search and sort using only the same coarse location exposed in the DTO.
        List<NearbyTask> rows = jdbc.query("SELECT " + COLUMNS
            + ", ST_Distance(CASE WHEN t.creator_pet_id = ? THEN t.location ELSE t.public_location END, "
            + "center.point) AS distance_meters " + FROM
            + "CROSS JOIN (SELECT ST_SetSRID(ST_MakePoint(?, ?), 4326)::geography AS point) center "
            + "WHERE t.status = 'OPEN' AND " + NOT_BLOCKED
            + "AND ((t.creator_pet_id = ? AND ST_DWithin(t.location, center.point, ?)) "
            + "OR (t.creator_pet_id <> ? AND ST_DWithin(t.public_location, center.point, ?))) "
            + "ORDER BY distance_meters ASC, t.created_at DESC, t.id DESC LIMIT ? OFFSET ?",
            (rs, row) -> new NearbyTask(response(record(rs, row), actor), rs.getDouble("distance_meters")),
            actor, longitude, latitude, actor, actor, actor, radiusMeters, actor, radiusMeters,
            limit + 1, limit * page);
        boolean more = rows.size() > limit;
        return new NearbyTaskPage(more ? rows.subList(0, limit) : rows, more ? page + 1 : null);
    }

    @Transactional(readOnly = true)
    public List<TaskEvent> history(long id) {
        TaskResponse task = detail(id);
        long actor = actorId();
        if (task.creatorPetId() != actor && (task.assigneePetId() == null || task.assigneePetId() != actor)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Task history not found");
        }
        return jdbc.query("SELECT e.id, e.actor_pet_id, p.name, e.status, e.created_at "
            + "FROM task_events e JOIN pets p ON p.id = e.actor_pet_id WHERE e.task_id = ? "
            + "ORDER BY e.created_at ASC, e.id ASC", (rs, row) -> new TaskEvent(rs.getLong("id"),
                rs.getLong("actor_pet_id"), rs.getString("name"), rs.getString("status"),
                rs.getTimestamp("created_at").toInstant()), id);
    }

    @Transactional(readOnly = true)
    public TaskProfile profile() {
        long actor = actorId();
        return jdbc.queryForObject("SELECT AVG(r.score)::double precision AS average_rating, COUNT(r.task_id) AS rating_count, "
            + "COALESCE((SELECT accepting_tasks FROM task_availability WHERE pet_id = ?), TRUE) AS accepting_tasks "
            + "FROM task_ratings r JOIN task_assignments a ON a.task_id = r.task_id WHERE a.pet_id = ?",
            (rs, row) -> new TaskProfile(actor, rs.getBoolean("accepting_tasks"),
                rs.getObject("average_rating", Double.class), rs.getLong("rating_count")), actor, actor);
    }

    @Transactional
    public TaskProfile availability(boolean acceptingTasks) {
        long actor = actorId();
        pairLock.lockPet(actor);
        jdbc.update("INSERT INTO task_availability (pet_id, accepting_tasks) VALUES (?, ?) "
            + "ON CONFLICT (pet_id) DO UPDATE SET accepting_tasks = EXCLUDED.accepting_tasks", actor, acceptingTasks);
        return profile();
    }

    @Transactional
    public TaskResponse rate(long id, TaskRatingInput input) {
        long actor = actorId();
        LockedTask task = lock(id);
        if (task.creatorPetId() != actor) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only the creator can rate this task");
        }
        requireStatus(task, "COMPLETED");
        String comment = input.comment() == null || input.comment().isBlank() ? null : input.comment().trim();
        jdbc.update("INSERT INTO task_ratings (task_id, score, comment) VALUES (?, ?, ?) "
            + "ON CONFLICT (task_id) DO UPDATE SET score = EXCLUDED.score, comment = EXCLUDED.comment, "
            + "updated_at = CURRENT_TIMESTAMP", id, input.score(), comment);
        return detail(id);
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
        // Serialize new acceptance with social block changes before checking PostgreSQL.
        pairLock.lock(actor, task.creatorPetId());
        if (social.blockedEitherWay(actor, task.creatorPetId())
            && (task.assigneePetId() == null || actor != task.assigneePetId())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Task not found");
        }
        requireStatus(task, "OPEN");
        if (Boolean.FALSE.equals(jdbc.queryForObject("SELECT COALESCE((SELECT accepting_tasks "
            + "FROM task_availability WHERE pet_id = ?), TRUE)", Boolean.class, actor))) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Enable task availability before accepting");
        }
        jdbc.update("INSERT INTO task_assignments (task_id, pet_id) VALUES (?, ?)", id, actor);
        transition(id, actor, "ACCEPTED");
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
        transition(id, actor, "IN_PROGRESS");
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
        transition(id, actor, "COMPLETED");
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
        transition(id, actor, "CANCELLED");
        return detail(id);
    }

    private LockedTask lock(long id) {
        List<Long> ids = jdbc.queryForList("SELECT t.id FROM tasks t WHERE t.id = ? FOR UPDATE OF t", Long.class, id);
        if (ids.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Task not found");
        // Read assignments in a fresh statement after any concurrent acceptance has committed.
        List<LockedTask> rows = jdbc.query("SELECT t.creator_pet_id, t.status, a.pet_id AS assignee_pet_id "
            + "FROM tasks t LEFT JOIN task_assignments a ON a.task_id = t.id WHERE t.id = ?",
            (rs, row) -> new LockedTask(rs.getLong("creator_pet_id"), rs.getString("status"),
                rs.getObject("assignee_pet_id", Long.class)), id);
        if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Task not found");
        return rows.getFirst();
    }

    private void transition(long id, long actor, String status) {
        jdbc.update("UPDATE tasks SET status = ?, updated_at = CURRENT_TIMESTAMP WHERE id = ?", status, id);
        event(id, actor, status);
    }

    private void event(long id, long actor, String status) {
        jdbc.update("INSERT INTO task_events (task_id, actor_pet_id, status) VALUES (?, ?, ?)", id, actor, status);
    }

    private static void validatePage(int limit, int page) {
        if (limit < 1 || limit > 50 || page < 0 || (long) limit * page > Integer.MAX_VALUE) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid task page or limit");
        }
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

    private static TaskRecord record(ResultSet rs, int row) throws SQLException {
        Integer score = rs.getObject("rating_score", Integer.class);
        TaskRating rating = score == null ? null : new TaskRating(score, rs.getString("rating_comment"),
            rs.getTimestamp("rating_updated_at").toInstant());
        return new TaskRecord(rs.getLong("id"), rs.getLong("creator_pet_id"),
            rs.getString("creator_name"), rs.getString("title"), rs.getString("description"),
            TaskInput.Category.valueOf(rs.getString("category")), rs.getDouble("latitude"),
            rs.getDouble("longitude"), rs.getString("status"),
            rs.getObject("assignee_pet_id", Long.class), rs.getString("assignee_name"),
            rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("updated_at").toInstant(), rating);
    }

    private static TaskResponse response(TaskRecord task, long actor) {
        boolean exact = task.participant(actor);
        return new TaskResponse(task.id(), task.creatorPetId(), task.creatorName(), task.title(),
            task.description(), task.category(), exact ? task.latitude() : approximate(task.latitude()),
            exact ? task.longitude() : approximate(task.longitude()), exact, task.status(),
            task.assigneePetId(), task.assigneeName(), task.createdAt(), task.updatedAt(), task.rating());
    }

    private static double approximate(double coordinate) {
        return BigDecimal.valueOf(coordinate).setScale(2, RoundingMode.HALF_UP).doubleValue();
    }

    private record TaskRecord(long id, long creatorPetId, String creatorName, String title,
                              String description, TaskInput.Category category, double latitude,
                              double longitude, String status, Long assigneePetId, String assigneeName,
                              Instant createdAt, Instant updatedAt, TaskRating rating) {
        boolean participant(long actor) {
            return actor == creatorPetId || (assigneePetId != null && actor == assigneePetId);
        }
    }

    private record LockedTask(long creatorPetId, String status, Long assigneePetId) {}

    public record TaskResponse(long id, long creatorPetId, String creatorName, String title,
                               String description, TaskInput.Category category, double latitude,
                               double longitude, boolean locationExact, String status, Long assigneePetId, String assigneeName,
                               Instant createdAt, Instant updatedAt, TaskRating rating) {}
    public record TaskPage(List<TaskResponse> items, Integer nextPage) {}
    public record NearbyTask(TaskResponse task, double distanceMeters) {}
    public record NearbyTaskPage(List<NearbyTask> items, Integer nextPage) {}
    public record TaskEvent(long id, long actorPetId, String actorName, String status, Instant createdAt) {}
    public record TaskRating(int score, String comment, Instant updatedAt) {}
    public record TaskProfile(long petId, boolean acceptingTasks, Double averageRating, long ratingCount) {}
}
