package com.wagwag.api.listing;

import com.wagwag.api.pet.PetRepository;
import com.wagwag.api.social.SocialPairLock;
import com.wagwag.api.social.SocialRestrictions;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class ListingOrderService {
    private static final String SELECT = "SELECT o.*, l.seller_pet_id, l.title, seller.name AS seller_name, "
        + "buyer.name AS buyer_name, r.score, r.comment FROM listing_orders o "
        + "JOIN listings l ON l.id = o.listing_id JOIN pets seller ON seller.id = l.seller_pet_id "
        + "JOIN pets buyer ON buyer.id = o.buyer_pet_id LEFT JOIN listing_ratings r ON r.order_id = o.id ";
    private final JdbcTemplate jdbc;
    private final PetRepository pets;
    private final SocialRestrictions social;
    private final SocialPairLock pairLock;
    private final long devPetId;

    public ListingOrderService(JdbcTemplate jdbc, PetRepository pets, SocialRestrictions social,
                               SocialPairLock pairLock, @Value("${app.dev-pet-id:0}") long devPetId) {
        this.jdbc = jdbc;
        this.pets = pets;
        this.social = social;
        this.pairLock = pairLock;
        this.devPetId = devPetId;
    }

    @Transactional
    public Order reserve(long listingId, UUID clientOrderId) {
        long actor = actor();
        List<Long> sellers = jdbc.queryForList("SELECT seller_pet_id FROM listings WHERE id = ?", Long.class, listingId);
        if (sellers.isEmpty()) throw missing();
        long seller = sellers.getFirst();
        if (seller == actor) throw error(HttpStatus.FORBIDDEN, "Cannot reserve your own listing");
        // Pair before item is the common lock order for new social interactions.
        pairLock.lock(actor, seller);
        String inventory = lockListing(listingId);
        List<Order> existing = jdbc.query(SELECT + "WHERE o.buyer_pet_id = ? AND o.client_order_id = ?",
            ListingOrderService::record, actor, clientOrderId);
        if (!existing.isEmpty()) return retry(existing.getFirst(), listingId);
        if (social.blockedEitherWay(actor, seller)) throw missing();
        if (!"AVAILABLE".equals(inventory)) throw error(HttpStatus.CONFLICT, "Item is no longer available");
        List<Long> inserted = jdbc.queryForList("INSERT INTO listing_orders (client_order_id, listing_id, buyer_pet_id, price_cents) "
            + "SELECT ?, id, ?, price_cents FROM listings WHERE id = ? "
            + "ON CONFLICT (buyer_pet_id, client_order_id) DO NOTHING RETURNING id", Long.class, clientOrderId, actor, listingId);
        if (inserted.isEmpty()) {
            Order previous = jdbc.query(SELECT + "WHERE o.buyer_pet_id = ? AND o.client_order_id = ?",
                ListingOrderService::record, actor, clientOrderId).getFirst();
            return retry(previous, listingId);
        }
        jdbc.update("UPDATE listings SET status = 'RESERVED', updated_at = CURRENT_TIMESTAMP WHERE id = ?", listingId);
        return find(inserted.getFirst());
    }

    @Transactional(readOnly = true)
    public Order detail(long id) {
        Order order = find(id);
        participant(order, actor());
        return order;
    }

    @Transactional(readOnly = true)
    public OrderPage list(int limit, int page) {
        if (limit < 1 || limit > 50 || page < 0 || page > 100000) throw error(HttpStatus.BAD_REQUEST, "Invalid order page");
        long actor = actor();
        List<Order> rows = jdbc.query(SELECT + "WHERE l.seller_pet_id = ? OR o.buyer_pet_id = ? "
            + "ORDER BY o.created_at DESC, o.id DESC LIMIT ? OFFSET ?", ListingOrderService::record,
            actor, actor, limit + 1, (long) page * limit);
        boolean more = rows.size() > limit;
        return new OrderPage(more ? rows.subList(0, limit) : rows, more ? page + 1 : null);
    }

    @Transactional
    public Order cancel(long id) {
        Order order = locked(id);
        participant(order, actor());
        if ("CANCELLED".equals(order.status())) return order;
        if (!"RESERVED".equals(order.status())) throw error(HttpStatus.CONFLICT, "Completed order cannot be cancelled");
        jdbc.update("UPDATE listing_orders SET status = 'CANCELLED', updated_at = CURRENT_TIMESTAMP WHERE id = ?", id);
        jdbc.update("UPDATE listings SET status = 'AVAILABLE', updated_at = CURRENT_TIMESTAMP WHERE id = ?", order.listingId());
        return find(id);
    }

    @Transactional
    public Order complete(long id) {
        Order order = locked(id);
        long actor = actor();
        participant(order, actor);
        if (order.sellerPetId() != actor) throw error(HttpStatus.FORBIDDEN, "Only the seller can confirm handoff");
        if ("COMPLETED".equals(order.status())) return order;
        if (!"RESERVED".equals(order.status())) throw error(HttpStatus.CONFLICT, "Order is not reserved");
        jdbc.update("UPDATE listing_orders SET status = 'COMPLETED', updated_at = CURRENT_TIMESTAMP WHERE id = ?", id);
        jdbc.update("UPDATE listings SET status = 'SOLD', updated_at = CURRENT_TIMESTAMP WHERE id = ?", order.listingId());
        return find(id);
    }

    @Transactional
    public Order rate(long id, int score, String comment) {
        Order order = locked(id);
        long actor = actor();
        participant(order, actor);
        if (order.buyerPetId() != actor) throw error(HttpStatus.FORBIDDEN, "Only the buyer can rate the seller");
        if (!"COMPLETED".equals(order.status())) throw error(HttpStatus.CONFLICT, "Complete the handoff before rating");
        if (score < 1 || score > 5 || (comment != null && comment.length() > 500)) throw error(HttpStatus.BAD_REQUEST, "Invalid seller rating");
        jdbc.update("INSERT INTO listing_ratings (order_id, score, comment) VALUES (?, ?, ?) "
            + "ON CONFLICT (order_id) DO UPDATE SET score = EXCLUDED.score, comment = EXCLUDED.comment, updated_at = CURRENT_TIMESTAMP",
            id, score, comment == null ? null : comment.trim());
        return find(id);
    }

    private Order locked(long id) {
        Order initial = find(id);
        // All order/inventory transitions take the listing row first, then reload current state.
        lockListing(initial.listingId());
        return find(id);
    }

    private String lockListing(long id) {
        List<String> states = jdbc.queryForList("SELECT status FROM listings WHERE id = ? FOR UPDATE", String.class, id);
        if (states.isEmpty()) throw missing();
        return states.getFirst();
    }

    private Order retry(Order order, long listingId) {
        if (order.listingId() != listingId) throw error(HttpStatus.CONFLICT, "Order identifier belongs to another listing");
        return order;
    }

    private void participant(Order order, long actor) {
        if (order.sellerPetId() != actor && order.buyerPetId() != actor) throw missing();
    }

    private Order find(long id) {
        List<Order> rows = jdbc.query(SELECT + "WHERE o.id = ?", ListingOrderService::record, id);
        if (rows.isEmpty()) throw missing();
        return rows.getFirst();
    }

    private long actor() {
        if (devPetId < 1 || !pets.existsById(devPetId)) throw error(HttpStatus.SERVICE_UNAVAILABLE, "Development pet is missing");
        return devPetId;
    }

    private static Order record(ResultSet rs, int index) throws SQLException {
        return new Order(rs.getLong("id"), rs.getObject("client_order_id", UUID.class), rs.getLong("listing_id"),
            rs.getString("title"), rs.getLong("seller_pet_id"), rs.getString("seller_name"), rs.getLong("buyer_pet_id"),
            rs.getString("buyer_name"), rs.getLong("price_cents"), rs.getString("status"),
            rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("updated_at").toInstant(),
            rs.getObject("score", Integer.class), rs.getString("comment"));
    }

    private static ResponseStatusException missing() { return error(HttpStatus.NOT_FOUND, "Order or listing not found"); }
    private static ResponseStatusException error(HttpStatus status, String message) { return new ResponseStatusException(status, message); }
    public record Order(long id, UUID clientOrderId, long listingId, String title, long sellerPetId, String sellerName,
                        long buyerPetId, String buyerName, long priceCents, String status, Instant createdAt, Instant updatedAt,
                        Integer ratingScore, String ratingComment) {}
    public record OrderPage(List<Order> items, Integer nextPage) {}
}
