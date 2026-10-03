package com.wagwag.api.community;

import com.wagwag.api.pet.PetRepository;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
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
    private static final String HOT_ORDER = "(SELECT COUNT(*) FROM community_members m WHERE m.community_id = c.id) "
        + "+ 2 * (SELECT COUNT(*) FROM post_communities pc JOIN posts p ON p.id = pc.post_id "
        + "WHERE pc.community_id = c.id AND p.created_at >= CURRENT_TIMESTAMP - INTERVAL '7 days') DESC, c.id DESC";
    private final JdbcTemplate jdbc;
    private final NamedParameterJdbcTemplate namedJdbc;
    private final PetRepository pets;
    private final CommunityHotCache hotCache;
    private final long devPetId;

    public CommunityService(JdbcTemplate jdbc, NamedParameterJdbcTemplate namedJdbc,
                            PetRepository pets, CommunityHotCache hotCache,
                            @Value("${app.dev-pet-id:0}") long devPetId) {
        this.jdbc = jdbc;
        this.namedJdbc = namedJdbc;
        this.pets = pets;
        this.hotCache = hotCache;
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
        jdbc.update("INSERT INTO community_members (community_id, pet_id, role) VALUES (?, ?, 'OWNER')", id, actorId);
        hotCache.evictAfterCommit();
        return detail(id);
    }

    @Transactional(readOnly = true)
    public CommunityResponse detail(long id) {
        long actorId = actorId();
        List<CommunityResponse> rows = jdbc.query("SELECT c.id, c.name, c.description, c.rules, "
            + "c.created_by_pet_id, c.created_at, "
            + "(SELECT COUNT(*) FROM community_members m WHERE m.community_id = c.id) AS member_count, "
            + "EXISTS (SELECT 1 FROM community_members m WHERE m.community_id = c.id "
            + "AND m.pet_id = ?) AS joined_by_me, "
            + "(SELECT role FROM community_members m WHERE m.community_id = c.id AND m.pet_id = ?) "
            + "AS my_role FROM communities c WHERE c.id = ?",
            (rs, row) -> response(rs), actorId, actorId, id);
        if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Community not found");
        return rows.getFirst();
    }

    @Transactional(readOnly = true)
    public CommunityPage list(String query, String sort, int limit, int page) {
        int offset = offset(limit, page);
        long actorId = actorId();
        if (!sort.equals("recent") && !sort.equals("hot")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid community sort");
        }
        String search = query == null ? "" : query.trim();
        if (search.length() > 100) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Community search is too long");
        }
        if (search.isEmpty() && sort.equals("hot") && page == 0) return hotFirstPage(limit, actorId);
        String vector = "to_tsvector('english', c.name || ' ' || COALESCE(c.description, '') "
            + "|| ' ' || COALESCE(c.rules, ''))";
        String order = !search.isEmpty() ? "ts_rank(" + vector + ", plainto_tsquery('english', ?)) DESC, c.id DESC"
            : sort.equals("hot") ? HOT_ORDER
            : "c.id DESC";
        String sql = "SELECT c.id, c.name, c.description, c.rules, "
            + "c.created_by_pet_id, c.created_at, "
            + "(SELECT COUNT(*) FROM community_members m WHERE m.community_id = c.id) AS member_count, "
            + "EXISTS (SELECT 1 FROM community_members m WHERE m.community_id = c.id "
            + "AND m.pet_id = ?) AS joined_by_me, "
            + "(SELECT role FROM community_members m WHERE m.community_id = c.id AND m.pet_id = ?) "
            + "AS my_role FROM communities c "
            + (search.isEmpty() ? "" : "WHERE " + vector + " @@ plainto_tsquery('english', ?) ")
            + "ORDER BY " + order + " LIMIT ? OFFSET ?";
        Object[] args = search.isEmpty() ? new Object[] {actorId, actorId, limit + 1, offset}
            : new Object[] {actorId, actorId, search, search, limit + 1, offset};
        List<CommunityResponse> rows = jdbc.query(sql, (rs, row) -> response(rs), args);
        boolean more = rows.size() > limit;
        return new CommunityPage(more ? rows.subList(0, limit) : rows, more ? page + 1 : null);
    }

    private CommunityPage hotFirstPage(int limit, long actorId) {
        List<Long> ids = hotCache.topIds(() -> jdbc.queryForList(
            "SELECT c.id FROM communities c ORDER BY " + HOT_ORDER + " LIMIT 51", Long.class));
        if (ids.isEmpty()) return new CommunityPage(List.of(), null);
        Map<Long, CommunityResponse> byId = new HashMap<>();
        namedJdbc.query("SELECT c.id, c.name, c.description, c.rules, c.created_by_pet_id, c.created_at, "
            + "(SELECT COUNT(*) FROM community_members m WHERE m.community_id = c.id) AS member_count, "
            + "EXISTS (SELECT 1 FROM community_members m WHERE m.community_id = c.id "
            + "AND m.pet_id = :actor) AS joined_by_me, "
            + "(SELECT role FROM community_members m WHERE m.community_id = c.id AND m.pet_id = :actor) "
            + "AS my_role FROM communities c WHERE c.id IN (:ids)",
            new MapSqlParameterSource("actor", actorId).addValue("ids", ids), rs -> {
                CommunityResponse item = response(rs);
                byId.put(item.id(), item);
            });
        List<CommunityResponse> items = new ArrayList<>();
        for (long id : ids.subList(0, Math.min(limit, ids.size()))) {
            CommunityResponse item = byId.get(id);
            if (item != null) items.add(item);
        }
        return new CommunityPage(items, ids.size() > limit ? 1 : null);
    }

    @Transactional
    public CommunityResponse join(long id) {
        lockCommunity(id);
        jdbc.update("INSERT INTO community_members (community_id, pet_id) VALUES (?, ?) "
            + "ON CONFLICT DO NOTHING", id, actorId());
        hotCache.evictAfterCommit();
        return detail(id);
    }

    @Transactional
    public CommunityResponse leave(long id) {
        lockCommunity(id);
        if ("OWNER".equals(role(id, actorId()))) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "The community owner cannot leave");
        }
        jdbc.update("DELETE FROM community_members WHERE community_id = ? AND pet_id = ?", id, actorId());
        hotCache.evictAfterCommit();
        return detail(id);
    }

    @Transactional
    public CommunityResponse updateSettings(long id, CommunitySettingsInput input) {
        lockCommunity(id);
        requireManager(id);
        jdbc.update("UPDATE communities SET description = ?, rules = ? WHERE id = ?",
            trimNullable(input.description()), trimNullable(input.rules()), id);
        return detail(id);
    }

    @Transactional
    public void setRole(long id, long petId, CommunityRoleInput.Role newRole) {
        lockCommunity(id);
        if (!"OWNER".equals(role(id, actorId()))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only the owner can set roles");
        }
        String current = role(id, petId);
        if (current == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Member not found");
        if ("OWNER".equals(current)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Cannot change the owner role");
        }
        jdbc.update("UPDATE community_members SET role = ? WHERE community_id = ? AND pet_id = ?",
            newRole.name(), id, petId);
    }

    @Transactional
    public void removeMember(long id, long petId) {
        lockCommunity(id);
        String manager = requireManager(id);
        String target = role(id, petId);
        if (target == null) return;
        if ("OWNER".equals(target) || ("MODERATOR".equals(target) && !"OWNER".equals(manager))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Cannot remove this member");
        }
        jdbc.update("DELETE FROM community_members WHERE community_id = ? AND pet_id = ?", id, petId);
        hotCache.evictAfterCommit();
    }

    @Transactional
    public void removePost(long id, long postId) {
        lockCommunity(id);
        requireManager(id);
        int deleted = jdbc.update("DELETE FROM post_communities WHERE community_id = ? AND post_id = ?",
            id, postId);
        if (deleted == 0) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Community post not found");
        hotCache.evictAfterCommit();
    }

    @Transactional(readOnly = true)
    public MemberPage members(long id, int limit, int page) {
        boolean moderationView = detail(id).canModerate();
        int offset = offset(limit, page);
        long actorId = actorId();
        String sql = "SELECT p.id, p.name, p.species, p.avatar_url, m.role "
            + "FROM community_members m JOIN pets p ON p.id = m.pet_id "
            + "WHERE m.community_id = ? "
            + (moderationView ? "" : "AND NOT EXISTS (SELECT 1 FROM pet_blocks b "
            + "WHERE (b.blocker_pet_id = ? AND b.blocked_pet_id = p.id) "
            + "OR (b.blocker_pet_id = p.id AND b.blocked_pet_id = ?)) ")
            + "ORDER BY m.joined_at ASC, p.id ASC LIMIT ? OFFSET ?";
        Object[] args = moderationView ? new Object[] {id, limit + 1, offset}
            : new Object[] {id, actorId, actorId, limit + 1, offset};
        List<MemberSummary> rows = jdbc.query(sql,
            (rs, row) -> new MemberSummary(rs.getLong("id"), rs.getString("name"),
                rs.getString("species"), rs.getString("avatar_url"), rs.getString("role")),
            args);
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
        hotCache.evictAfterCommit();
    }

    private void lockCommunity(long id) {
        if (jdbc.queryForList("SELECT id FROM communities WHERE id = ? FOR UPDATE", Long.class, id).isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Community not found");
        }
    }

    private String role(long id, long petId) {
        List<String> roles = jdbc.queryForList("SELECT role FROM community_members "
            + "WHERE community_id = ? AND pet_id = ?", String.class, id, petId);
        return roles.isEmpty() ? null : roles.getFirst();
    }

    private String requireManager(long id) {
        String role = role(id, actorId());
        if (!isManagerRole(role)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Community manager required");
        }
        return role;
    }

    private static boolean isManagerRole(String role) {
        return "OWNER".equals(role) || "MODERATOR".equals(role);
    }

    private static String trimNullable(String value) {
        return value == null || value.isBlank() ? null : value.trim();
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
            rs.getString("description"), rs.getString("rules"), rs.getLong("created_by_pet_id"),
            rs.getTimestamp("created_at").toInstant(), rs.getLong("member_count"),
            rs.getBoolean("joined_by_me"), rs.getString("my_role"));
    }

    public record CommunityResponse(long id, String name, String description, String rules,
                                    long createdByPetId, Instant createdAt, long memberCount,
                                    boolean joinedByMe, String myRole) {
        public boolean canModerate() { return isManagerRole(myRole); }
    }
    public record CommunityPage(List<CommunityResponse> items, Integer nextPage) {}
    public record MemberSummary(long id, String name, String species, String avatarUrl, String role) {}
    public record MemberPage(List<MemberSummary> items, Integer nextPage) {}
    public record CommunityLabel(long id, String name) {}
}
