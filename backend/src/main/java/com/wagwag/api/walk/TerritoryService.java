package com.wagwag.api.walk;

import com.wagwag.api.social.SocialRestrictions;
import java.time.Instant;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
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
    private final TerritoryLeaderboardCache leaderboardCache;
    private final SocialRestrictions restrictions;

    public TerritoryService(JdbcTemplate jdbc, WalkService walks, ObjectMapper json,
                            TerritoryLeaderboardCache leaderboardCache, SocialRestrictions restrictions) {
        this.jdbc = jdbc;
        this.walks = walks;
        this.json = json;
        this.leaderboardCache = leaderboardCache;
        this.restrictions = restrictions;
    }

    @Transactional
    public CreateResult claim(long walkId) {
        long petId = walks.activePetId();
        List<Long> inserted = jdbc.queryForList("INSERT INTO territories (walk_id, area, base_strength) "
            + "SELECT w.id, ST_Buffer(w.route::geography, ?)::geometry, "
            + "LEAST(5, 1 + FLOOR(ST_Length(w.route::geography) / 200)::INTEGER) "
            + "FROM walks w WHERE w.id = ? AND w.pet_id = ? "
            + "ON CONFLICT (walk_id) DO NOTHING RETURNING id",
            Long.class, CLAIM_RADIUS_METERS, walkId, petId);
        if (inserted.isEmpty() && !Boolean.TRUE.equals(jdbc.queryForObject(
            "SELECT EXISTS (SELECT 1 FROM walks WHERE id = ? AND pet_id = ?)", Boolean.class, walkId, petId))) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Walk not found");
        }
        if (!inserted.isEmpty()) leaderboardCache.evictAfterCommit();
        return new CreateResult(detail(walkId, petId), !inserted.isEmpty());
    }

    @Transactional(readOnly = true)
    public TerritoryResponse detail(long walkId) {
        return detail(walkId, walks.activePetId());
    }

    private TerritoryResponse detail(long walkId, long petId) {
        List<TerritoryResponse> rows = jdbc.query("SELECT t.id, t.walk_id, t.pet_id, t.created_at, "
            + "t.base_strength, t.effective_strength, t.contested_area_square_meters, "
            + "ST_Area(t.area::geography) AS area_square_meters, "
            + "ST_Area(t.owned_area::geography) AS owned_area_square_meters, "
            + "ST_AsGeoJSON(t.area, 6) AS polygon, ST_AsGeoJSON(t.owned_area, 6) AS owned_polygon "
            + "FROM territory_ownership t WHERE t.walk_id = ? AND t.pet_id = ?",
            (rs, row) -> new TerritoryResponse(rs.getLong("id"), rs.getLong("walk_id"),
                rs.getLong("pet_id"), rs.getTimestamp("created_at").toInstant(),
                rs.getDouble("area_square_meters"), rs.getDouble("owned_area_square_meters"),
                rs.getDouble("contested_area_square_meters"), rs.getInt("base_strength"),
                rs.getInt("effective_strength"), polygon(rs.getString("polygon")),
                multiPolygon(rs.getString("owned_polygon"))), walkId, petId);
        if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Territory not found");
        return rows.getFirst();
    }

    @Transactional(readOnly = true)
    public TerritoryPage history(int limit, int page) {
        validatePage(limit, page);
        long petId = walks.activePetId();
        List<TerritorySummary> rows = jdbc.query("SELECT id, walk_id, created_at, base_strength, "
            + "effective_strength, ST_Area(area::geography) AS area_square_meters, "
            + "ST_Area(owned_area::geography) AS owned_area_square_meters "
            + "FROM territory_ownership WHERE pet_id = ? ORDER BY created_at DESC, id DESC LIMIT ? OFFSET ?",
            (rs, row) -> new TerritorySummary(rs.getLong("id"), rs.getLong("walk_id"),
                rs.getTimestamp("created_at").toInstant(), rs.getInt("base_strength"),
                rs.getInt("effective_strength"), rs.getDouble("area_square_meters"),
                rs.getDouble("owned_area_square_meters")), petId, limit + 1, page * limit);
        boolean more = rows.size() > limit;
        return new TerritoryPage(more ? rows.subList(0, limit) : rows, more ? page + 1 : null);
    }

    @Transactional(readOnly = true)
    public List<LeaderboardEntry> leaderboard(int limit) {
        if (limit < 1 || limit > 50) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid leaderboard limit");
        }
        long petId = walks.activePetId();
        if (!restrictions.blockedPetIds(petId).isEmpty()) return leaderboardFromDatabase(limit, petId);
        List<LeaderboardEntry> top = leaderboardCache.top(() -> leaderboardFromDatabase(50, null));
        return top.subList(0, Math.min(limit, top.size()));
    }

    private List<LeaderboardEntry> leaderboardFromDatabase(int limit, Long viewerPetId) {
        String visibility = viewerPetId == null ? "" : "WHERE NOT EXISTS (SELECT 1 FROM pet_blocks b "
            + "WHERE (b.blocker_pet_id = ? AND b.blocked_pet_id = o.pet_id) "
            + "OR (b.blocker_pet_id = o.pet_id AND b.blocked_pet_id = ?)) ";
        String sql = "SELECT o.pet_id, p.name AS pet_name, "
            + "SUM(ST_Area(o.owned_area::geography)) AS area_square_meters, COUNT(*) AS claim_count "
            + "FROM territory_ownership o JOIN pets p ON p.id = o.pet_id "
            + visibility
            + "GROUP BY o.pet_id, p.name HAVING SUM(ST_Area(o.owned_area::geography)) > 0 "
            + "ORDER BY area_square_meters DESC, o.pet_id ASC LIMIT ?";
        RowMapper<LeaderboardEntry> mapper =
            (rs, row) -> new LeaderboardEntry(rs.getLong("pet_id"), rs.getString("pet_name"),
                rs.getDouble("area_square_meters"), rs.getLong("claim_count"));
        return viewerPetId == null ? jdbc.query(sql, mapper, limit)
            : jdbc.query(sql, mapper, viewerPetId, viewerPetId, limit);
    }

    private static void validatePage(int limit, int page) {
        if (limit < 1 || limit > 50 || page < 0 || (long) limit * page > Integer.MAX_VALUE) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid territory page or limit");
        }
    }

    private Polygon polygon(String value) {
        try {
            return json.readValue(value, Polygon.class);
        } catch (JacksonException exception) {
            throw new IllegalStateException("Stored territory geometry is invalid", exception);
        }
    }

    private MultiPolygon multiPolygon(String value) {
        try {
            return json.readValue(value, MultiPolygon.class);
        } catch (JacksonException exception) {
            throw new IllegalStateException("Stored territory ownership geometry is invalid", exception);
        }
    }

    public record Polygon(String type, List<List<List<Double>>> coordinates) {}
    public record MultiPolygon(String type, List<List<List<List<Double>>>> coordinates) {}
    public record TerritoryResponse(long id, long walkId, long petId, Instant createdAt,
                                    double areaSquareMeters, double ownedAreaSquareMeters,
                                    double contestedAreaSquareMeters, int baseStrength, int effectiveStrength,
                                    Polygon area, MultiPolygon ownedArea) {}
    public record TerritorySummary(long id, long walkId, Instant createdAt, int baseStrength,
                                   int effectiveStrength, double areaSquareMeters, double ownedAreaSquareMeters) {}
    public record TerritoryPage(List<TerritorySummary> items, Integer nextPage) {}
    public record LeaderboardEntry(long petId, String petName, double areaSquareMeters, long claimCount) {}
    public record CreateResult(TerritoryResponse territory, boolean created) {}
}
