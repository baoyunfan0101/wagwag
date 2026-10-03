package com.wagwag.api.walk;

import com.wagwag.api.pet.PetRepository;
import com.wagwag.api.walk.WalkInput.PointInput;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class WalkService {
    private static final double EARTH_RADIUS_METERS = 6_371_008.8;
    private static final double MAX_WALK_SPEED_METERS_PER_SECOND = 12.0;

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
    public CreateResult create(WalkInput input) {
        long petId = actorId();
        validateRoute(input);
        List<Long> inserted = jdbc.queryForList("INSERT INTO walks (pet_id, client_walk_id, started_at, ended_at, route) "
            + "VALUES (?, ?, ?, ?, ST_GeomFromText(?, 4326)) "
            + "ON CONFLICT (pet_id, client_walk_id) DO NOTHING RETURNING id",
            Long.class, petId, input.clientWalkId(), timestamp(input.startedAt()), timestamp(input.endedAt()), routeWkt(input));
        if (inserted.isEmpty()) {
            long existingId = jdbc.queryForObject("SELECT id FROM walks WHERE pet_id = ? AND client_walk_id = ?",
                Long.class, petId, input.clientWalkId());
            WalkResponse existing = detail(existingId);
            if (!sameWalk(existing, input)) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Client walk ID belongs to another route");
            }
            return new CreateResult(existing, false);
        }
        long id = inserted.getFirst();
        List<Object[]> rows = new ArrayList<>(input.points().size());
        for (int index = 0; index < input.points().size(); index++) {
            PointInput point = input.points().get(index);
            rows.add(new Object[] {id, index, point.latitude(), point.longitude(),
                timestamp(point.recordedAt())});
        }
        jdbc.batchUpdate("INSERT INTO walk_points (walk_id, sequence_number, latitude, longitude, recorded_at) "
            + "VALUES (?, ?, ?, ?, ?)", rows);
        return new CreateResult(detail(id), true);
    }

    private static void validateRoute(WalkInput input) {
        if (input.endedAt().isBefore(input.startedAt())
            || input.endedAt().isAfter(Instant.now().plusSeconds(300))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid walk time");
        }
        PointInput previous = null;
        Instant previousStoredTime = null;
        for (PointInput point : input.points()) {
            if (!Double.isFinite(point.latitude()) || !Double.isFinite(point.longitude())
                || point.recordedAt().isBefore(input.startedAt())
                || point.recordedAt().isAfter(input.endedAt())) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid walk point");
            }
            Instant storedTime = point.recordedAt().truncatedTo(ChronoUnit.MICROS);
            if (previous != null) {
                if (!storedTime.isAfter(previousStoredTime)) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid walk point timestamp");
                }
                Duration elapsed = Duration.between(previousStoredTime, storedTime);
                double elapsedSeconds = elapsed.getSeconds() + elapsed.getNano() / 1_000_000_000.0;
                double speed = distanceMeters(previous, point) / elapsedSeconds;
                if (speed > MAX_WALK_SPEED_METERS_PER_SECOND) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Implausible walk segment");
                }
            }
            previous = point;
            previousStoredTime = storedTime;
        }
    }

    private static double distanceMeters(PointInput first, PointInput second) {
        double latitudeDelta = Math.toRadians(second.latitude() - first.latitude());
        double longitudeDelta = Math.toRadians(second.longitude() - first.longitude());
        double firstLatitude = Math.toRadians(first.latitude());
        double secondLatitude = Math.toRadians(second.latitude());
        double haversine = Math.pow(Math.sin(latitudeDelta / 2), 2)
            + Math.cos(firstLatitude) * Math.cos(secondLatitude) * Math.pow(Math.sin(longitudeDelta / 2), 2);
        return 2 * EARTH_RADIUS_METERS * Math.asin(Math.min(1, Math.sqrt(haversine)));
    }

    @Transactional(readOnly = true)
    public WalkResponse detail(long id) {
        long petId = actorId();
        List<WalkSummary> walks = jdbc.query("SELECT w.id, w.pet_id, w.started_at, w.ended_at, "
            + "ST_Length(w.route::geography) AS distance_meters, "
            + "(SELECT COUNT(*) FROM walk_points wp WHERE wp.walk_id = w.id) AS point_count "
            + "FROM walks w WHERE w.id = ? AND w.pet_id = ?",
            (rs, row) -> summary(rs), id, petId);
        if (walks.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Walk not found");
        WalkSummary walk = walks.getFirst();
        List<WalkPoint> points = jdbc.query("SELECT latitude, longitude, recorded_at FROM walk_points "
            + "WHERE walk_id = ? ORDER BY sequence_number ASC",
            (rs, row) -> new WalkPoint(rs.getDouble("latitude"), rs.getDouble("longitude"),
                rs.getTimestamp("recorded_at").toInstant()), id);
        List<List<Double>> coordinates = jdbc.query("SELECT ST_X((point).geom) AS longitude, "
            + "ST_Y((point).geom) AS latitude FROM (SELECT ST_DumpPoints("
            + "ST_SimplifyPreserveTopology(route, 0.00003)) AS point FROM walks WHERE id = ?) r "
            + "ORDER BY (point).path", (rs, row) -> List.of(rs.getDouble("longitude"), rs.getDouble("latitude")), id);
        return new WalkResponse(walk.id(), walk.petId(), walk.startedAt(), walk.endedAt(),
            walk.distanceMeters(), points, new WalkRoute("LineString", coordinates));
    }

    @Transactional(readOnly = true)
    public WalkPage list(int limit, int page) {
        validatePage(limit, page);
        long petId = actorId();
        List<WalkSummary> rows = jdbc.query("SELECT w.id, w.pet_id, w.started_at, w.ended_at, "
            + "ST_Length(w.route::geography) AS distance_meters, "
            + "(SELECT COUNT(*) FROM walk_points wp WHERE wp.walk_id = w.id) AS point_count "
            + "FROM walks w WHERE w.pet_id = ? ORDER BY w.started_at DESC, w.id DESC "
            + "LIMIT ? OFFSET ?", (rs, row) -> summary(rs), petId, limit + 1, page * limit);
        boolean more = rows.size() > limit;
        return new WalkPage(more ? rows.subList(0, limit) : rows, more ? page + 1 : null);
    }

    @Transactional(readOnly = true)
    public NearbyWalkPage nearby(double latitude, double longitude, double radiusMeters, int limit, int page) {
        validatePage(limit, page);
        if (!Double.isFinite(latitude) || !Double.isFinite(longitude) || !Double.isFinite(radiusMeters)
            || latitude < -90 || latitude > 90 || longitude < -180 || longitude > 180
            || radiusMeters < 1 || radiusMeters > 10000) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid nearby location or radius");
        }
        long petId = actorId();
        List<NearbyWalk> rows = jdbc.query("WITH center AS (SELECT ST_SetSRID(ST_MakePoint(?, ?), 4326)::geography AS point) "
            + "SELECT w.id, w.pet_id, w.started_at, w.ended_at, ST_Length(w.route::geography) AS distance_meters, "
            + "(SELECT COUNT(*) FROM walk_points wp WHERE wp.walk_id = w.id) AS point_count, "
            + "ST_Distance(w.route::geography, center.point) AS proximity_meters FROM walks w CROSS JOIN center "
            + "WHERE w.pet_id = ? AND ST_DWithin(w.route::geography, center.point, ?) "
            + "ORDER BY proximity_meters ASC, w.started_at DESC, w.id DESC LIMIT ? OFFSET ?",
            (rs, row) -> new NearbyWalk(summary(rs), rs.getDouble("proximity_meters")),
            longitude, latitude, petId, radiusMeters, limit + 1, page * limit);
        boolean more = rows.size() > limit;
        return new NearbyWalkPage(more ? rows.subList(0, limit) : rows, more ? page + 1 : null);
    }

    private static void validatePage(int limit, int page) {
        if (limit < 1 || limit > 50 || page < 0 || (long) page * limit > Integer.MAX_VALUE) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid walk page or limit");
        }
    }

    private static String routeWkt(WalkInput input) {
        String coordinates = input.points().stream().map(p -> p.longitude() + " " + p.latitude())
            .collect(Collectors.joining(","));
        if (input.points().size() == 1) coordinates += "," + coordinates;
        return "LINESTRING(" + coordinates + ")";
    }

    private long actorId() {
        if (devPetId < 1 || !pets.existsById(devPetId)) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Development pet is missing");
        }
        return devPetId;
    }

    long activePetId() { return actorId(); }

    private static Timestamp timestamp(Instant instant) {
        return Timestamp.from(instant.truncatedTo(ChronoUnit.MICROS));
    }

    private static boolean sameWalk(WalkResponse existing, WalkInput input) {
        if (!existing.startedAt().equals(timestamp(input.startedAt()).toInstant())
            || !existing.endedAt().equals(timestamp(input.endedAt()).toInstant())
            || existing.points().size() != input.points().size()) return false;
        for (int index = 0; index < input.points().size(); index++) {
            WalkPoint stored = existing.points().get(index);
            PointInput submitted = input.points().get(index);
            if (Double.compare(stored.latitude(), submitted.latitude()) != 0
                || Double.compare(stored.longitude(), submitted.longitude()) != 0
                || !stored.recordedAt().equals(timestamp(submitted.recordedAt()).toInstant())) return false;
        }
        return true;
    }

    private static WalkSummary summary(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new WalkSummary(rs.getLong("id"), rs.getLong("pet_id"),
            rs.getTimestamp("started_at").toInstant(), rs.getTimestamp("ended_at").toInstant(),
            rs.getLong("point_count"), rs.getDouble("distance_meters"));
    }

    public record WalkPoint(double latitude, double longitude, Instant recordedAt) {}
    public record CreateResult(WalkResponse walk, boolean created) {}
    public record WalkResponse(long id, long petId, Instant startedAt, Instant endedAt,
                               double distanceMeters, List<WalkPoint> points, WalkRoute route) {}
    public record WalkRoute(String type, List<List<Double>> coordinates) {}
    public record WalkSummary(long id, long petId, Instant startedAt, Instant endedAt,
                              long pointCount, double distanceMeters) {}
    public record NearbyWalk(WalkSummary walk, double proximityMeters) {}
    public record NearbyWalkPage(List<NearbyWalk> items, Integer nextPage) {}
    public record WalkPage(List<WalkSummary> items, Integer nextPage) {}
}
