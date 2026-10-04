package com.wagwag.api.message;

import com.wagwag.api.notification.NotificationService;
import com.wagwag.api.pet.PetRepository;
import com.wagwag.api.social.SocialPairLock;
import com.wagwag.api.social.SocialRestrictions;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class MessageService {
    private static final String CONVERSATION_SELECT = "SELECT c.id, p.id AS pet_id, p.name, p.avatar_url, "
        + "c.updated_at, latest.body AS last_message, NOT EXISTS (SELECT 1 FROM pet_blocks b "
        + "WHERE (b.blocker_pet_id = me.pet_id AND b.blocked_pet_id = peer.pet_id) "
        + "OR (b.blocker_pet_id = peer.pet_id AND b.blocked_pet_id = me.pet_id)) AS can_message "
        + "FROM conversations c JOIN conversation_members me ON me.conversation_id = c.id "
        + "JOIN conversation_members peer ON peer.conversation_id = c.id AND peer.pet_id <> me.pet_id "
        + "JOIN pets p ON p.id = peer.pet_id LEFT JOIN LATERAL (SELECT m.body FROM messages m "
        + "WHERE m.conversation_id = c.id ORDER BY m.id DESC LIMIT 1) latest ON TRUE ";
    private static final String MESSAGE_SELECT = "SELECT id, conversation_id, sender_pet_id, "
        + "client_message_id, body, created_at FROM messages ";

    private final JdbcTemplate jdbc;
    private final PetRepository pets;
    private final SocialRestrictions social;
    private final SocialPairLock pairLock;
    private final NotificationService notifications;
    private final long devPetId;

    public MessageService(JdbcTemplate jdbc, PetRepository pets, SocialRestrictions social,
                          SocialPairLock pairLock, NotificationService notifications,
                          @Value("${app.dev-pet-id:0}") long devPetId) {
        this.jdbc = jdbc;
        this.pets = pets;
        this.social = social;
        this.pairLock = pairLock;
        this.notifications = notifications;
        this.devPetId = devPetId;
    }

    @Transactional
    public Conversation open(long petId) {
        long actor = actorId();
        if (petId == actor) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Cannot message yourself");
        if (!pets.existsById(petId)) throw missing();
        pairLock.lock(actor, petId);
        requireUnblocked(actor, petId);
        long first = Math.min(actor, petId);
        long second = Math.max(actor, petId);
        jdbc.update("INSERT INTO conversations (first_pet_id, second_pet_id) VALUES (?, ?) "
            + "ON CONFLICT (first_pet_id, second_pet_id) DO NOTHING", first, second);
        long id = jdbc.queryForObject("SELECT id FROM conversations WHERE first_pet_id = ? AND second_pet_id = ?",
            Long.class, first, second);
        jdbc.update("INSERT INTO conversation_members (conversation_id, pet_id) VALUES (?, ?), (?, ?) "
            + "ON CONFLICT DO NOTHING", id, first, id, second);
        return detail(id);
    }

    @Transactional(readOnly = true)
    public Conversation detail(long id) {
        List<Conversation> rows = jdbc.query(CONVERSATION_SELECT + "WHERE me.pet_id = ? AND c.id = ?",
            MessageService::conversation, actorId(), id);
        if (rows.isEmpty()) throw missing();
        return rows.getFirst();
    }

    @Transactional(readOnly = true)
    public ConversationPage list(int limit, int page) {
        validateLimit(limit);
        if (page < 0 || (long) limit * page > Integer.MAX_VALUE) throw invalidPage();
        List<Conversation> rows = jdbc.query(CONVERSATION_SELECT + "WHERE me.pet_id = ? "
            + "ORDER BY c.updated_at DESC, c.id DESC LIMIT ? OFFSET ?", MessageService::conversation,
            actorId(), limit + 1, limit * page);
        boolean more = rows.size() > limit;
        return new ConversationPage(more ? rows.subList(0, limit) : rows, more ? page + 1 : null);
    }

    @Transactional(readOnly = true)
    public MessagePage history(long id, int limit, Long beforeId, Long afterId) {
        detail(id); // Membership is authoritative even when the caller knows a message ID.
        validateLimit(limit);
        if ((beforeId != null && beforeId < 1) || (afterId != null && afterId < 0)
            || (beforeId != null && afterId != null)) throw invalidPage();
        List<Message> rows;
        if (afterId != null) {
            rows = jdbc.query(MESSAGE_SELECT + "WHERE conversation_id = ? AND id > ? "
                + "ORDER BY id ASC LIMIT ?", MessageService::message, id, afterId, limit + 1);
        } else {
            rows = beforeId == null
                ? jdbc.query(MESSAGE_SELECT + "WHERE conversation_id = ? ORDER BY id DESC LIMIT ?",
                    MessageService::message, id, limit + 1)
                : jdbc.query(MESSAGE_SELECT + "WHERE conversation_id = ? AND id < ? ORDER BY id DESC LIMIT ?",
                    MessageService::message, id, beforeId, limit + 1);
        }
        boolean more = rows.size() > limit;
        List<Message> items = new ArrayList<>(more ? rows.subList(0, limit) : rows);
        if (afterId == null) Collections.reverse(items);
        return new MessagePage(items, more && afterId == null ? items.getFirst().id() : null,
            more && afterId != null ? items.getLast().id() : null);
    }

    @Transactional
    public Message send(long id, MessageInput input) {
        long actor = actorId();
        List<Long> peers = jdbc.queryForList("SELECT peer.pet_id FROM conversation_members me "
            + "JOIN conversation_members peer ON peer.conversation_id = me.conversation_id AND peer.pet_id <> me.pet_id "
            + "WHERE me.conversation_id = ? AND me.pet_id = ?", Long.class, id, actor);
        if (peers.isEmpty()) throw missing();
        long peer = peers.getFirst();
        pairLock.lock(actor, peer);
        String body = input.body().trim();
        List<Message> existing = jdbc.query(MESSAGE_SELECT
            + "WHERE conversation_id = ? AND sender_pet_id = ? AND client_message_id = ?",
            MessageService::message, id, actor, input.clientMessageId());
        if (!existing.isEmpty()) return retry(existing.getFirst(), body);
        requireUnblocked(actor, peer);
        List<Long> inserted = jdbc.queryForList("INSERT INTO messages "
            + "(conversation_id, sender_pet_id, client_message_id, body) VALUES (?, ?, ?, ?) "
            + "ON CONFLICT (conversation_id, sender_pet_id, client_message_id) DO NOTHING RETURNING id",
            Long.class, id, actor, input.clientMessageId(), body);
        if (inserted.isEmpty()) {
            return retry(jdbc.queryForObject(MESSAGE_SELECT
                + "WHERE conversation_id = ? AND sender_pet_id = ? AND client_message_id = ?",
                MessageService::message, id, actor, input.clientMessageId()), body);
        }
        long messageId = inserted.getFirst();
        jdbc.update("UPDATE conversations SET updated_at = clock_timestamp() WHERE id = ?", id);
        notifications.message(peer, actor, messageId);
        return jdbc.queryForObject(MESSAGE_SELECT + "WHERE id = ?", MessageService::message, messageId);
    }

    private void requireUnblocked(long actor, long peer) {
        if (social.blockedEitherWay(actor, peer)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Messaging is unavailable between these pets");
        }
    }

    private long actorId() {
        if (devPetId < 1 || !pets.existsById(devPetId)) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Development pet is missing");
        }
        return devPetId;
    }

    private static Message retry(Message existing, String body) {
        if (!existing.body().equals(body)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Message ID was already used for different text");
        }
        return existing;
    }

    private static void validateLimit(int limit) {
        if (limit < 1 || limit > 50) throw invalidPage();
    }

    private static ResponseStatusException invalidPage() {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid message page or limit");
    }

    private static ResponseStatusException missing() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "Conversation not found");
    }

    private static Conversation conversation(ResultSet rs, int row) throws SQLException {
        return new Conversation(rs.getLong("id"), rs.getLong("pet_id"), rs.getString("name"),
            rs.getString("avatar_url"), rs.getString("last_message"), rs.getTimestamp("updated_at").toInstant(),
            rs.getBoolean("can_message"));
    }

    private static Message message(ResultSet rs, int row) throws SQLException {
        return new Message(rs.getLong("id"), rs.getLong("conversation_id"), rs.getLong("sender_pet_id"),
            rs.getObject("client_message_id", UUID.class), rs.getString("body"), rs.getTimestamp("created_at").toInstant());
    }

    public record Conversation(long id, long petId, String petName, String petAvatarUrl,
                               String lastMessage, Instant updatedAt, boolean canMessage) {}
    public record ConversationPage(List<Conversation> items, Integer nextPage) {}
    public record Message(long id, long conversationId, long senderPetId, UUID clientMessageId,
                          String body, Instant createdAt) {}
    public record MessagePage(List<Message> items, Long nextBeforeId, Long nextAfterId) {}
}
