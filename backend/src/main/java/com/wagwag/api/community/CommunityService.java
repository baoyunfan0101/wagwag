package com.wagwag.api.community;

import com.wagwag.api.pet.PetRepository;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class CommunityService {
    private final JdbcTemplate jdbc;
    private final NamedParameterJdbcTemplate namedJdbc;
    private final PetRepository pets;
    private final long devPetId;

    public CommunityService(JdbcTemplate jdbc, NamedParameterJdbcTemplate namedJdbc,
                            PetRepository pets, @Value("${app.dev-pet-id:0}") long devPetId) {
        this.jdbc = jdbc;
        this.namedJdbc = namedJdbc;
        this.pets = pets;
        this.devPetId = devPetId;
    }

    @Transactional
    public CommunityResponse create(CommunityInput input) {
        long actorId = actorId();
        String name = input.name().trim();
        String description = input.description() == null || input.description().isBlank()
            ? null : input.description().trim();
        long id;
        try {
            id = jdbc.queryForObject("INSERT INTO communities (name, description, created_by_pet_id) "
                + "VALUES (?, ?, ?) RETURNING id", Long.class, name, description, actorId);
        } catch (DataIntegrityViolationException error) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Community name is already taken", error);
        }
        jdbc.update("INSERT INTO community_members (community_id, pet_id) VALUES (?, ?)", id, actorId);
        return detail(id);
    }

    @Transactional(readOnly = true)
    public CommunityResponse detail(long id) {
        long actorId = actorId();
        List<CommunityResponse> rows = jdbc.query("SELECT c.id, c.name, c.description, "
            + "c.created_by_pet_id, c.created_at, "
            + "(SELECT COUNT(*) FROM community_members m WHERE m.community_id = c.id) AS member_count, "
            + "EXISTS (SELECT 1 FROM community_members m WHERE m.community_id = c.id "
            + "AND m.pet_id = ?) AS joined_by_me FROM communities c WHERE c.id = ?",
            (rs, row) -> response(rs), actorId, id);
        if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Community not found");
        return rows.getFirst();
    }

    @Transactional(readOnly = true)
    public CommunityPage list(String query, int limit, int page) {
        int offset = offset(limit, page);
        long actorId = actorId();
        String pattern = "%" + (query == null ? "" : query.trim()) + "%";
        List<CommunityResponse> rows = jdbc.query("SELECT c.id, c.name, c.description, "
            + "c.created_by_pet_id, c.created_at, "
            + "(SELECT COUNT(*) FROM community_members m WHERE m.community_id = c.id) AS member_count, "
            + "EXISTS (SELECT 1 FROM community_members m WHERE m.community_id = c.id "
            + "AND m.pet_id = ?) AS joined_by_me FROM communities c WHERE c.name ILIKE ? "
            + "ORDER BY c.id DESC LIMIT ? OFFSET ?", (rs, row) -> response(rs),
            actorId, pattern, limit + 1, offset);
        boolean more = rows.size() > limit;
        return new CommunityPage(more ? rows.subList(0, limit) : rows, more ? page + 1 : null);
    }

    @Transactional
    public CommunityResponse join(long id) {
        detail(id);
        jdbc.update("INSERT INTO community_members (community_id, pet_id) VALUES (?, ?) "
            + "ON CONFLICT DO NOTHING", id, actorId());
        return detail(id);
    }

    @Transactional
    public CommunityResponse leave(long id) {
        detail(id);
        jdbc.update("DELETE FROM community_members WHERE community_id = ? AND pet_id = ?", id, actorId());
        return detail(id);
    }

    @Transactional(readOnly = true)
    public MemberPage members(long id, int limit, int page) {
        detail(id);
        int offset = offset(limit, page);
        long actorId = actorId();
        List<MemberSummary> rows = jdbc.query("SELECT p.id, p.name, p.species, p.avatar_url "
            + "FROM community_members m JOIN pets p ON p.id = m.pet_id "
            + "WHERE m.community_id = ? AND NOT EXISTS (SELECT 1 FROM pet_blocks b "
            + "WHERE (b.blocker_pet_id = ? AND b.blocked_pet_id = p.id) "
            + "OR (b.blocker_pet_id = p.id AND b.blocked_pet_id = ?)) "
            + "ORDER BY m.joined_at ASC, p.id ASC LIMIT ? OFFSET ?",
            (rs, row) -> new MemberSummary(rs.getLong("id"), rs.getString("name"),
                rs.getString("species"), rs.getString("avatar_url")),
            id, actorId, actorId, limit + 1, offset);
        boolean more = rows.size() > limit;
        return new MemberPage(more ? rows.subList(0, limit) : rows, more ? page + 1 : null);
    }

    @Transactional(readOnly = true)
    public void requireMember(long communityId, long petId) {
        if (communityId < 1) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Community not found");
        boolean member = Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS (SELECT 1 "
            + "FROM community_members WHERE community_id = ? AND pet_id = ?)", Boolean.class,
            communityId, petId));
        if (!member) {
            detail(communityId);
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Join the community before posting");
        }
    }

    @Transactional(readOnly = true)
    public Map<Long, CommunityLabel> labels(List<Long> postIds) {
        if (postIds.isEmpty()) return Map.of();
        Map<Long, CommunityLabel> result = new HashMap<>();
        namedJdbc.query("SELECT pc.post_id, c.id, c.name FROM post_communities pc "
            + "JOIN communities c ON c.id = pc.community_id WHERE pc.post_id IN (:ids)",
            new MapSqlParameterSource("ids", postIds), rs -> {
                result.put(rs.getLong("post_id"), new CommunityLabel(rs.getLong("id"), rs.getString("name")));
            });
        return result;
    }

    @Transactional
    public void attach(long postId, long communityId) {
        jdbc.update("INSERT INTO post_communities (post_id, community_id) VALUES (?, ?)", postId, communityId);
    }

    private long actorId() {
        if (devPetId < 1 || !pets.existsById(devPetId)) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Development pet is missing");
        }
        return devPetId;
    }

    private static int offset(int limit, int page) {
        if (limit < 1 || limit > 50 || page < 0 || (long) page * limit > Integer.MAX_VALUE) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid community page or limit");
        }
        return page * limit;
    }

    private static CommunityResponse response(ResultSet rs) throws SQLException {
        return new CommunityResponse(rs.getLong("id"), rs.getString("name"),
            rs.getString("description"), rs.getLong("created_by_pet_id"),
            rs.getTimestamp("created_at").toInstant(), rs.getLong("member_count"),
            rs.getBoolean("joined_by_me"));
    }

    public record CommunityResponse(long id, String name, String description, long createdByPetId,
                                    Instant createdAt, long memberCount, boolean joinedByMe) {}
    public record CommunityPage(List<CommunityResponse> items, Integer nextPage) {}
    public record MemberSummary(long id, String name, String species, String avatarUrl) {}
    public record MemberPage(List<MemberSummary> items, Integer nextPage) {}
    public record CommunityLabel(long id, String name) {}
}
