package com.wagwag.api.walk;

import com.wagwag.api.pet.PetRepository;
import com.wagwag.api.walk.WalkInput.PointInput;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class WalkService {
    private final JdbcTemplate jdbc;
    private final PetRepository pets;
    private final long devPetId;

    public WalkService(JdbcTemplate jdbc, PetRepository pets,
                       @Value("${app.dev-pet-id:0}") long devPetId) {
        this.jdbc = jdbc;
        this.pets = pets;
        this.devPetId = devPetId;
    }

    @Transactional
    public WalkResponse create(WalkInput input) {
        long petId = actorId();
        if (input.endedAt().isBefore(input.startedAt())
            || input.endedAt().isAfter(Instant.now().plusSeconds(300))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid walk time");
        }
        Instant previous = input.startedAt();
        for (PointInput point : input.points()) {
            if (!Double.isFinite(point.latitude()) || !Double.isFinite(point.longitude())
                || point.recordedAt().isBefore(previous) || point.recordedAt().isAfter(input.endedAt())) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid walk point");
            }
            previous = point.recordedAt();
        }
        long id = jdbc.queryForObject("INSERT INTO walks (pet_id, started_at, ended_at) "
            + "VALUES (?, ?, ?) RETURNING id", Long.class,
            petId, Timestamp.from(input.startedAt()), Timestamp.from(input.endedAt()));
        List<Object[]> rows = new ArrayList<>(input.points().size());
        for (int index = 0; index < input.points().size(); index++) {
            PointInput point = input.points().get(index);
            rows.add(new Object[] {id, index, point.latitude(), point.longitude(),
                Timestamp.from(point.recordedAt())});
        }
        jdbc.batchUpdate("INSERT INTO walk_points (walk_id, sequence_number, latitude, longitude, recorded_at) "
            + "VALUES (?, ?, ?, ?, ?)", rows);
        return detail(id);
    }

    @Transactional(readOnly = true)
    public WalkResponse detail(long id) {
        long petId = actorId();
        List<WalkSummary> walks = jdbc.query("SELECT w.id, w.pet_id, w.started_at, w.ended_at, "
            + "(SELECT COUNT(*) FROM walk_points wp WHERE wp.walk_id = w.id) AS point_count "
            + "FROM walks w WHERE w.id = ? AND w.pet_id = ?",
            (rs, row) -> summary(rs), id, petId);
        if (walks.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Walk not found");
        WalkSummary walk = walks.getFirst();
        List<WalkPoint> points = jdbc.query("SELECT latitude, longitude, recorded_at FROM walk_points "
            + "WHERE walk_id = ? ORDER BY sequence_number ASC",
            (rs, row) -> new WalkPoint(rs.getDouble("latitude"), rs.getDouble("longitude"),
                rs.getTimestamp("recorded_at").toInstant()), id);
        return new WalkResponse(walk.id(), walk.petId(), walk.startedAt(), walk.endedAt(), points);
    }

    @Transactional(readOnly = true)
    public WalkPage list(int limit, int page) {
        if (limit < 1 || limit > 50 || page < 0 || (long) page * limit > Integer.MAX_VALUE) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid walk page or limit");
        }
        long petId = actorId();
        List<WalkSummary> rows = jdbc.query("SELECT w.id, w.pet_id, w.started_at, w.ended_at, "
            + "(SELECT COUNT(*) FROM walk_points wp WHERE wp.walk_id = w.id) AS point_count "
            + "FROM walks w WHERE w.pet_id = ? ORDER BY w.started_at DESC, w.id DESC "
            + "LIMIT ? OFFSET ?", (rs, row) -> summary(rs), petId, limit + 1, page * limit);
        boolean more = rows.size() > limit;
        return new WalkPage(more ? rows.subList(0, limit) : rows, more ? page + 1 : null);
    }

    private long actorId() {
        if (devPetId < 1 || !pets.existsById(devPetId)) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Development pet is missing");
        }
        return devPetId;
    }

    private static WalkSummary summary(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new WalkSummary(rs.getLong("id"), rs.getLong("pet_id"),
            rs.getTimestamp("started_at").toInstant(), rs.getTimestamp("ended_at").toInstant(),
            rs.getLong("point_count"));
    }

    public record WalkPoint(double latitude, double longitude, Instant recordedAt) {}
    public record WalkResponse(long id, long petId, Instant startedAt, Instant endedAt,
                               List<WalkPoint> points) {}
    public record WalkSummary(long id, long petId, Instant startedAt, Instant endedAt,
                              long pointCount) {}
    public record WalkPage(List<WalkSummary> items, Integer nextPage) {}
}
