package com.wagwag.api.walk;

import java.time.Instant;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@Service
public class TerritoryService {
    private static final int CLAIM_RADIUS_METERS = 20;

    private final JdbcTemplate jdbc;
    private final WalkService walks;
    private final ObjectMapper json;

    public TerritoryService(JdbcTemplate jdbc, WalkService walks, ObjectMapper json) {
        this.jdbc = jdbc;
        this.walks = walks;
        this.json = json;
    }

    @Transactional
    public CreateResult claim(long walkId) {
        long petId = walks.activePetId();
        List<Long> inserted = jdbc.queryForList("INSERT INTO territories (walk_id, area) "
            + "SELECT w.id, ST_Buffer(w.route::geography, ?)::geometry "
            + "FROM walks w WHERE w.id = ? AND w.pet_id = ? "
            + "ON CONFLICT (walk_id) DO NOTHING RETURNING id",
            Long.class, CLAIM_RADIUS_METERS, walkId, petId);
        if (inserted.isEmpty() && !Boolean.TRUE.equals(jdbc.queryForObject(
            "SELECT EXISTS (SELECT 1 FROM walks WHERE id = ? AND pet_id = ?)", Boolean.class, walkId, petId))) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Walk not found");
        }
        return new CreateResult(detail(walkId, petId), !inserted.isEmpty());
    }

    @Transactional(readOnly = true)
    public TerritoryResponse detail(long walkId) {
        return detail(walkId, walks.activePetId());
    }

    private TerritoryResponse detail(long walkId, long petId) {
        List<TerritoryResponse> rows = jdbc.query("SELECT t.id, t.walk_id, w.pet_id, t.created_at, "
            + "ST_Area(t.area::geography) AS area_square_meters, ST_AsGeoJSON(t.area, 6) AS polygon "
            + "FROM territories t JOIN walks w ON w.id = t.walk_id WHERE t.walk_id = ? AND w.pet_id = ?",
            (rs, row) -> new TerritoryResponse(rs.getLong("id"), rs.getLong("walk_id"),
                rs.getLong("pet_id"), rs.getTimestamp("created_at").toInstant(),
                rs.getDouble("area_square_meters"), polygon(rs.getString("polygon"))), walkId, petId);
        if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Territory not found");
        return rows.getFirst();
    }

    private Polygon polygon(String value) {
        try {
            return json.readValue(value, Polygon.class);
        } catch (JacksonException exception) {
            throw new IllegalStateException("Stored territory geometry is invalid", exception);
        }
    }

    public record Polygon(String type, List<List<List<Double>>> coordinates) {}
    public record TerritoryResponse(long id, long walkId, long petId, Instant createdAt,
                                    double areaSquareMeters, Polygon area) {}
    public record CreateResult(TerritoryResponse territory, boolean created) {}
}
