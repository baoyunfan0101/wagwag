package com.wagwag.api.listing;

import com.wagwag.api.pet.PetRepository;
import com.wagwag.api.social.SocialPairLock;
import com.wagwag.api.social.SocialRestrictions;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class ListingService {
    private static final String SELECT = "SELECT l.id, l.seller_pet_id, seller.name AS seller_name, "
        + "seller.avatar_url AS seller_avatar_url, l.title, l.description, l.price_cents, l.status, "
        + "l.created_at, l.updated_at, EXISTS (SELECT 1 FROM listing_favorites f "
        + "WHERE f.listing_id = l.id AND f.pet_id = ?) AS favorited_by_me "
        + "FROM listings l JOIN pets seller ON seller.id = l.seller_pet_id ";
    private static final String NOT_BLOCKED = "NOT EXISTS (SELECT 1 FROM pet_blocks b WHERE "
        + "(b.blocker_pet_id = ? AND b.blocked_pet_id = l.seller_pet_id) "
        + "OR (b.blocker_pet_id = l.seller_pet_id AND b.blocked_pet_id = ?)) ";
    private final JdbcTemplate jdbc;
    private final PetRepository pets;
    private final SocialRestrictions social;
    private final SocialPairLock pairLock;
    private final long devPetId;

    public ListingService(JdbcTemplate jdbc, PetRepository pets, SocialRestrictions social,
                          SocialPairLock pairLock, @Value("${app.dev-pet-id:0}") long devPetId) {
        this.jdbc = jdbc;
        this.pets = pets;
        this.social = social;
        this.pairLock = pairLock;
        this.devPetId = devPetId;
    }

    @Transactional
    public ListingResponse create(ListingInput input) {
        long actor = actorId();
        List<String> urls = validUrls(input.imageUrls());
        long id = jdbc.queryForObject("INSERT INTO listings (seller_pet_id, title, description, price_cents) "
            + "VALUES (?, ?, ?, ?) RETURNING id", Long.class,
            actor, input.title().trim(), input.description().trim(), input.priceCents());
        for (int index = 0; index < urls.size(); index++) {
            jdbc.update("INSERT INTO listing_media (listing_id, url, sort_order) VALUES (?, ?, ?)",
                id, urls.get(index), index);
        }
        return detail(id);
    }

    @Transactional(readOnly = true)
    public ListingPage list(String scope, int limit, String cursor) {
        if (limit < 1 || limit > 50) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid listing limit");
        Cursor after = decode(cursor);
        long actor = actorId();
        List<Object> args = new ArrayList<>();
        args.add(actor);
        String filter;
        switch (scope) {
            case "available" -> filter = "WHERE l.status = 'AVAILABLE' AND " + NOT_BLOCKED;
            case "mine" -> filter = "WHERE l.seller_pet_id = ? ";
            case "favorites" -> filter = "WHERE EXISTS (SELECT 1 FROM listing_favorites own "
                + "WHERE own.listing_id = l.id AND own.pet_id = ?) AND " + NOT_BLOCKED;
            default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid listing scope");
        }
        if ("mine".equals(scope)) {
            args.add(actor);
        } else {
            if ("favorites".equals(scope)) args.add(actor);
            args.add(actor);
            args.add(actor);
        }
        if (after != null) {
            filter += "AND (l.created_at < ? OR (l.created_at = ? AND l.id < ?)) ";
            args.add(Timestamp.from(after.createdAt()));
            args.add(Timestamp.from(after.createdAt()));
            args.add(after.id());
        }
        args.add(limit + 1);
        List<ListingRecord> rows = jdbc.query(SELECT + filter + "ORDER BY l.created_at DESC, l.id DESC LIMIT ?",
            ListingService::record, args.toArray());
        boolean more = rows.size() > limit;
        List<ListingRecord> selected = more ? rows.subList(0, limit) : rows;
        if (selected.isEmpty()) return new ListingPage(List.of(), null);
        Map<Long, List<String>> media = media(selected.stream().map(ListingRecord::id).toList());
        List<ListingResponse> items = selected.stream().map(row -> response(row, media.getOrDefault(row.id(), List.of())))
            .toList();
        ListingRecord last = selected.getLast();
        return new ListingPage(items, more ? encode(last) : null);
    }

    @Transactional(readOnly = true)
    public ListingResponse detail(long id) {
        long actor = actorId();
        ListingRecord row = find(id, actor);
        if (row.sellerPetId() != actor && social.blockedEitherWay(actor, row.sellerPetId())) throw missing();
        return response(row, media(List.of(id)).getOrDefault(id, List.of()));
    }

    @Transactional
    public ListingResponse favorite(long id) {
        long actor = actorId();
        ListingRecord row = find(id, actor);
        if (row.sellerPetId() == actor) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Cannot favorite your own listing");
        }
        pairLock.lock(actor, row.sellerPetId());
        if (social.blockedEitherWay(actor, row.sellerPetId())) throw missing();
        String currentStatus = jdbc.queryForObject("SELECT status FROM listings WHERE id = ? FOR UPDATE", String.class, id);
        if (!"AVAILABLE".equals(currentStatus)) {
            Boolean alreadySaved = jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM listing_favorites "
                + "WHERE listing_id = ? AND pet_id = ?)", Boolean.class, id, actor);
            if (!Boolean.TRUE.equals(alreadySaved)) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Listing is sold");
            }
            return detail(id);
        }
        jdbc.update("INSERT INTO listing_favorites (listing_id, pet_id) VALUES (?, ?) ON CONFLICT DO NOTHING", id, actor);
        return detail(id);
    }

    @Transactional
    public ListingResponse unfavorite(long id) {
        long actor = actorId();
        ListingRecord row = find(id, actor);
        if (row.sellerPetId() != actor && social.blockedEitherWay(actor, row.sellerPetId())) throw missing();
        jdbc.update("DELETE FROM listing_favorites WHERE listing_id = ? AND pet_id = ?", id, actor);
        return detail(id);
    }

    @Transactional
    public ListingResponse sold(long id) {
        long actor = actorId();
        ListingRecord row = find(id, actor);
        if (row.sellerPetId() != actor) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only the seller can mark this listing sold");
        }
        jdbc.update("UPDATE listings SET status = 'SOLD', updated_at = CURRENT_TIMESTAMP "
            + "WHERE id = ? AND seller_pet_id = ? AND status = 'AVAILABLE'", id, actor);
        return detail(id);
    }

    private ListingRecord find(long id, long actor) {
        List<ListingRecord> rows = jdbc.query(SELECT + "WHERE l.id = ?", ListingService::record, actor, id);
        if (rows.isEmpty()) throw missing();
        return rows.getFirst();
    }

    private Map<Long, List<String>> media(List<Long> ids) {
        Map<Long, List<String>> result = new HashMap<>();
        String placeholders = String.join(",", java.util.Collections.nCopies(ids.size(), "?"));
        jdbc.query("SELECT listing_id, url FROM listing_media WHERE listing_id IN (" + placeholders
            + ") ORDER BY listing_id, sort_order", rs -> {
                result.computeIfAbsent(rs.getLong("listing_id"), ignored -> new ArrayList<>()).add(rs.getString("url"));
        }, ids.toArray());
        return result;
    }

    private static ListingResponse response(ListingRecord row, List<String> media) {
        return new ListingResponse(row.id(), row.sellerPetId(), row.sellerName(), row.sellerAvatarUrl(),
            row.title(), row.description(), row.priceCents(), row.status(), media,
            row.favoritedByMe(), row.createdAt(), row.updatedAt());
    }

    private static ListingRecord record(ResultSet rs, int index) throws SQLException {
        return new ListingRecord(rs.getLong("id"), rs.getLong("seller_pet_id"), rs.getString("seller_name"),
            rs.getString("seller_avatar_url"), rs.getString("title"), rs.getString("description"),
            rs.getLong("price_cents"), rs.getString("status"), rs.getBoolean("favorited_by_me"),
            rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("updated_at").toInstant());
    }

    private static List<String> validUrls(List<String> urls) {
        if (urls == null) return List.of();
        if (urls.size() > 4 || new HashSet<>(urls).size() != urls.size()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Use up to four distinct image links");
        }
        for (String url : urls) {
            if (url == null || url.length() > 2048) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid listing image link");
            }
            try {
                URI parsed = URI.create(url);
                if (!"https".equals(parsed.getScheme()) || parsed.getHost() == null
                        || parsed.getRawUserInfo() != null) throw new IllegalArgumentException();
            } catch (IllegalArgumentException error) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Use absolute HTTPS image links", error);
            }
        }
        return urls;
    }

    private long actorId() {
        if (devPetId < 1 || !pets.existsById(devPetId)) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Development pet is missing");
        }
        return devPetId;
    }

    private static String encode(ListingRecord row) {
        String value = row.createdAt() + "|" + row.id();
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private static Cursor decode(String cursor) {
        if (cursor == null) return null;
        try {
            String decoded = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
            String[] parts = decoded.split("\\|", -1);
            if (parts.length != 2) throw new IllegalArgumentException();
            Instant createdAt = Instant.parse(parts[0]);
            long id = Long.parseLong(parts[1]);
            if (id < 1) throw new IllegalArgumentException();
            return new Cursor(createdAt, id);
        } catch (RuntimeException error) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid listing cursor", error);
        }
    }

    private static ResponseStatusException missing() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "Listing not found");
    }

    private record Cursor(Instant createdAt, long id) {}
    private record ListingRecord(long id, long sellerPetId, String sellerName, String sellerAvatarUrl,
                                 String title, String description, long priceCents, String status,
                                 boolean favoritedByMe, Instant createdAt, Instant updatedAt) {}
    public record ListingResponse(long id, long sellerPetId, String sellerName, String sellerAvatarUrl,
                                  String title, String description, long priceCents, String status,
                                  List<String> imageUrls, boolean favoritedByMe, Instant createdAt, Instant updatedAt) {}
    public record ListingPage(List<ListingResponse> items, String nextCursor) {}
}
