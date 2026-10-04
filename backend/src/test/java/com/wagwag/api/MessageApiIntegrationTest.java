package com.wagwag.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.wagwag.api.message.MessageInput;
import com.wagwag.api.message.MessageService;
import com.wagwag.api.message.ReceiptInput;
import com.wagwag.api.notification.NotificationService;
import com.wagwag.api.pet.PetRepository;
import com.wagwag.api.social.SocialPairLock;
import com.wagwag.api.social.SocialRestrictions;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("dev")
@Testcontainers
class MessageApiIntegrationTest {
    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
        DockerImageName.parse("postgis/postgis:17-3.5").asCompatibleSubstituteFor("postgres"));

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("app.follow-cache.enabled", () -> false);
    }

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired PetRepository pets;
    @Autowired SocialRestrictions social;
    @Autowired SocialPairLock pairLock;
    @Autowired NotificationService notifications;
    @Autowired MessageService messages;
    @Autowired TransactionTemplate transactions;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM conversations");
        jdbc.update("DELETE FROM pet_blocks");
        jdbc.update("INSERT INTO pets (id, owner_id, name, species, gender) "
            + "VALUES (1001, 1, 'Pip', 'Dog', 'UNKNOWN') ON CONFLICT DO NOTHING");
    }

    @Test
    void bothPetsShareOneConversationAndExchangePersistedMessages() throws Exception {
        long id = open();
        assertThat(other(1000).detail(id).petId()).isEqualTo(1);
        assertThat(transactions.execute(tx -> other(1000).open(1)).id()).isEqualTo(id);
        String sent = send(id, UUID.randomUUID(), "Hello Biscuit");
        long sentId = number(sent, "$.id");
        transactions.executeWithoutResult(tx -> other(1000).send(id, new MessageInput(UUID.randomUUID(), "Hi Mochi")));
        mvc.perform(get("/api/conversations/{id}/messages", id))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items.length()").value(2))
            .andExpect(jsonPath("$.items[0].id").value(sentId))
            .andExpect(jsonPath("$.items[0].senderPetId").value(1))
            .andExpect(jsonPath("$.items[1].senderPetId").value(1000))
            .andExpect(jsonPath("$.items[1].body").value("Hi Mochi"));
        mvc.perform(get("/api/conversations"))
            .andExpect(jsonPath("$.items[0].id").value(id))
            .andExpect(jsonPath("$.items[0].lastMessage").value("Hi Mochi"));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM conversations", Long.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM conversation_members", Long.class)).isEqualTo(2);
    }

    @Test
    void nonMembersCannotReadOrSendOrReceiveAnotherPetsNotifications() throws Exception {
        long id = transactions.execute(tx -> other(1000).open(1001)).id();
        transactions.executeWithoutResult(tx -> other(1000).send(id, new MessageInput(UUID.randomUUID(), "Private chat")));
        mvc.perform(get("/api/conversations/{id}", id)).andExpect(status().isNotFound());
        mvc.perform(get("/api/conversations/{id}/messages", id)).andExpect(status().isNotFound());
        mvc.perform(post("/api/conversations/{id}/messages", id).contentType(MediaType.APPLICATION_JSON)
            .content(payload(UUID.randomUUID(), "Intrude"))).andExpect(status().isNotFound());
        mvc.perform(get("/api/conversations")).andExpect(jsonPath("$.items.length()").value(0));
        mvc.perform(get("/api/notifications")).andExpect(jsonPath("$.items.length()").value(0));
        long notificationId = jdbc.queryForObject("SELECT id FROM notifications LIMIT 1", Long.class);
        mvc.perform(put("/api/notifications/{id}/read", notificationId)).andExpect(status().isNotFound());
        assertThat(jdbc.queryForObject("SELECT read_at IS NULL FROM notifications WHERE id = ?",
            Boolean.class, notificationId)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void blockEitherWayPreventsNewMessagingButPreservesMembersHistory(boolean reverse) throws Exception {
        long id = open();
        UUID key = UUID.randomUUID();
        send(id, key, "Before block");
        jdbc.update("INSERT INTO pet_blocks (blocker_pet_id, blocked_pet_id) VALUES (?, ?)",
            reverse ? 1000 : 1, reverse ? 1 : 1000);
        mvc.perform(post("/api/conversations").contentType(MediaType.APPLICATION_JSON).content("{\"petId\":1000}"))
            .andExpect(status().isForbidden());
        mvc.perform(post("/api/conversations/{id}/messages", id).contentType(MediaType.APPLICATION_JSON)
            .content(payload(UUID.randomUUID(), "After block"))).andExpect(status().isForbidden());
        mvc.perform(get("/api/conversations/{id}", id)).andExpect(jsonPath("$.canMessage").value(false));
        mvc.perform(get("/api/conversations/{id}/messages", id))
            .andExpect(jsonPath("$.items[0].body").value("Before block"));
        send(id, key, "Before block"); // A lost response may still resolve an already committed message.
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM messages", Long.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM notifications", Long.class)).isEqualTo(1);
    }

    @Test
    void retriesResolveSameMessageAndConflictingReuseIsRejected() throws Exception {
        long id = open();
        UUID key = UUID.randomUUID();
        long firstId = number(send(id, key, "Hello"), "$.id");
        assertThat(number(send(id, key, "Hello"), "$.id")).isEqualTo(firstId);
        mvc.perform(post("/api/conversations/{id}/messages", id).contentType(MediaType.APPLICATION_JSON)
            .content(payload(key, "Different"))).andExpect(status().isConflict());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM messages", Long.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM notifications", Long.class)).isEqualTo(1);
    }

    @Test
    void simultaneousConversationAndMessageRetriesCannotDuplicateRows() throws Exception {
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        UUID key = UUID.randomUUID();
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            java.util.concurrent.Callable<Long> submit = () -> {
                ready.countDown();
                assertThat(go.await(5, TimeUnit.SECONDS)).isTrue();
                return number(send(open(), key, "Concurrent hello"), "$.id");
            };
            var first = executor.submit(submit);
            var second = executor.submit(submit);
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            go.countDown();
            assertThat(first.get(15, TimeUnit.SECONDS)).isEqualTo(second.get(15, TimeUnit.SECONDS));
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM conversations", Long.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM messages", Long.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM notifications", Long.class)).isEqualTo(1);
    }

    @Test
    void messageHistoryPagesInBothDirectionsWithoutSkippingOrDuplicating() throws Exception {
        long id = open();
        long[] ids = new long[5];
        for (int i = 0; i < ids.length; i++) ids[i] = number(send(id, UUID.randomUUID(), "Message " + i), "$.id");
        mvc.perform(get("/api/conversations/{id}/messages", id).param("limit", "2"))
            .andExpect(jsonPath("$.items[0].id").value(ids[3]))
            .andExpect(jsonPath("$.items[1].id").value(ids[4]))
            .andExpect(jsonPath("$.nextBeforeId").value(ids[3]));
        mvc.perform(get("/api/conversations/{id}/messages", id).param("limit", "2").param("beforeId", "" + ids[3]))
            .andExpect(jsonPath("$.items[0].id").value(ids[1]))
            .andExpect(jsonPath("$.items[1].id").value(ids[2]))
            .andExpect(jsonPath("$.nextBeforeId").value(ids[1]));
        mvc.perform(get("/api/conversations/{id}/messages", id).param("limit", "2").param("beforeId", "" + ids[1]))
            .andExpect(jsonPath("$.items[0].id").value(ids[0]))
            .andExpect(jsonPath("$.nextBeforeId").value((Object) null));
        mvc.perform(get("/api/conversations/{id}/messages", id).param("limit", "2").param("afterId", "" + ids[0]))
            .andExpect(jsonPath("$.items[0].id").value(ids[1]))
            .andExpect(jsonPath("$.items[1].id").value(ids[2]))
            .andExpect(jsonPath("$.nextAfterId").value(ids[2]));
        mvc.perform(get("/api/conversations/{id}/messages", id).param("limit", "2").param("afterId", "" + ids[2]))
            .andExpect(jsonPath("$.items[0].id").value(ids[3]))
            .andExpect(jsonPath("$.items[1].id").value(ids[4]))
            .andExpect(jsonPath("$.nextAfterId").value((Object) null));
    }

    @Test
    void notificationsAreRecipientScopedPaginatedAndReadIdempotently() throws Exception {
        long id = open();
        for (int i = 0; i < 3; i++) {
            int index = i;
            transactions.executeWithoutResult(tx -> other(1000).send(id, new MessageInput(UUID.randomUUID(), "Hi " + index)));
        }
        String first = mvc.perform(get("/api/notifications").param("limit", "2"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(2))
            .andExpect(jsonPath("$.items[0].type").value("MESSAGE"))
            .andExpect(jsonPath("$.items[0].targetId").value(id))
            .andExpect(jsonPath("$.items[0].actorName").value("Biscuit"))
            .andExpect(jsonPath("$.items[0].readAt").value((Object) null))
            .andReturn().getResponse().getContentAsString();
        mvc.perform(get("/api/notifications").param("limit", "2").param("beforeId", "" + number(first, "$.nextBeforeId")))
            .andExpect(jsonPath("$.items.length()").value(1))
            .andExpect(jsonPath("$.nextBeforeId").value((Object) null));
        long notificationId = number(first, "$.items[0].id");
        String read = mvc.perform(put("/api/notifications/{id}/read", notificationId))
            .andExpect(status().isOk()).andExpect(jsonPath("$.readAt").isNotEmpty())
            .andReturn().getResponse().getContentAsString();
        mvc.perform(put("/api/notifications/{id}/read", notificationId))
            .andExpect(jsonPath("$.readAt").value(JsonPath.<String>read(read, "$.readAt")));
    }

    @Test
    void transactionRollbackCannotLeaveMessageOrNotification() throws Exception {
        long id = open();
        transactions.executeWithoutResult(tx -> {
            messages.send(id, new MessageInput(UUID.randomUUID(), "Rolled back"));
            tx.setRollbackOnly();
        });
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM messages", Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM notifications", Long.class)).isZero();
    }

    @Test
    void validatesInputsAndDatabaseMembershipAndPairConstraints() throws Exception {
        mvc.perform(post("/api/conversations").contentType(MediaType.APPLICATION_JSON).content("{\"petId\":1}"))
            .andExpect(status().isBadRequest());
        mvc.perform(post("/api/conversations").contentType(MediaType.APPLICATION_JSON).content("{\"petId\":99999}"))
            .andExpect(status().isNotFound());
        long id = open();
        for (String payload : new String[] {"{\"body\":\"No UUID\"}", "{\"clientMessageId\":\"invalid\",\"body\":\"hi\"}",
            payload(UUID.randomUUID(), " "), payload(UUID.randomUUID(), "x".repeat(2001))}) {
            mvc.perform(post("/api/conversations/{id}/messages", id).contentType(MediaType.APPLICATION_JSON).content(payload))
                .andExpect(status().isBadRequest());
        }
        mvc.perform(get("/api/conversations").param("limit", "51")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/conversations/{id}/messages", id).param("beforeId", "1").param("afterId", "2"))
            .andExpect(status().isBadRequest());
        mvc.perform(get("/api/conversations/{id}/messages", id).param("afterId", "-1"))
            .andExpect(status().isBadRequest());
        mvc.perform(get("/api/notifications").param("limit", "0")).andExpect(status().isBadRequest());
        assertThatThrownBy(() -> jdbc.update("INSERT INTO conversations (first_pet_id, second_pet_id) VALUES (1, 1000)"))
            .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("INSERT INTO messages (conversation_id, sender_pet_id, client_message_id, body) "
            + "VALUES (?, 1001, ?, 'Not a member')", id, UUID.randomUUID()))
            .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> other(1001).detail(id)).isInstanceOf(ResponseStatusException.class);
    }

    private MessageService other(long petId) {
        return new MessageService(jdbc, pets, social, pairLock, notifications, petId);
    }

    @Test
    void deliveryAndReadReceiptsAreMonotonicAndUnreadCountsUsePostgres() throws Exception {
        long id = open();
        long sentId = number(send(id, UUID.randomUUID(), "Receipt test"), "$.id");
        mvc.perform(get("/api/conversations/{id}/messages", id))
            .andExpect(jsonPath("$.items[0].deliveryStatus").value("SENT"));
        transactions.executeWithoutResult(tx -> other(1000).receipt(id, new ReceiptInput(sentId, false)));
        mvc.perform(get("/api/conversations/{id}/messages", id))
            .andExpect(jsonPath("$.items[0].deliveryStatus").value("DELIVERED"));
        transactions.executeWithoutResult(tx -> other(1000).receipt(id, new ReceiptInput(sentId, true)));
        transactions.executeWithoutResult(tx -> other(1000).receipt(id, new ReceiptInput(0L, false)));
        mvc.perform(get("/api/conversations/{id}/messages", id))
            .andExpect(jsonPath("$.items[0].deliveryStatus").value("READ"));
        mvc.perform(get("/api/conversations/{id}", id))
            .andExpect(jsonPath("$.peerReadThroughId").value(sentId));
        long replyId = transactions.execute(tx -> other(1000).send(id,
            new MessageInput(UUID.randomUUID(), "Unread reply"))).id();
        mvc.perform(get("/api/notifications/unread"))
            .andExpect(jsonPath("$.messages").value(1)).andExpect(jsonPath("$.notifications").value(1));
        mvc.perform(get("/api/conversations"))
            .andExpect(jsonPath("$.items[0].unreadCount").value(1));
        mvc.perform(put("/api/conversations/{id}/receipt", id).contentType(MediaType.APPLICATION_JSON)
            .content("{\"throughMessageId\":" + replyId + ",\"read\":false}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.unreadCount").value(1));
        mvc.perform(put("/api/conversations/{id}/receipt", id).contentType(MediaType.APPLICATION_JSON)
            .content("{\"throughMessageId\":" + replyId + ",\"read\":true}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.unreadCount").value(0));
        mvc.perform(get("/api/notifications/unread"))
            .andExpect(jsonPath("$.messages").value(0)).andExpect(jsonPath("$.notifications").value(0));
        assertThat(jdbc.queryForObject("SELECT last_read_message_id FROM conversation_members "
            + "WHERE conversation_id = ? AND pet_id = 1", Long.class, id)).isEqualTo(replyId);
    }

    @Test
    void receiptsRequireMembershipAndMessageInThatConversation() throws Exception {
        long id = open();
        long otherConversation = transactions.execute(tx -> other(1000).open(1001)).id();
        long otherMessage = transactions.execute(tx -> other(1000).send(otherConversation,
            new MessageInput(UUID.randomUUID(), "Another conversation"))).id();
        mvc.perform(put("/api/conversations/{id}/receipt", id).contentType(MediaType.APPLICATION_JSON)
            .content("{\"throughMessageId\":" + otherMessage + ",\"read\":true}"))
            .andExpect(status().isBadRequest());
        mvc.perform(put("/api/conversations/{id}/receipt", otherConversation).contentType(MediaType.APPLICATION_JSON)
            .content("{\"throughMessageId\":" + otherMessage + ",\"read\":true}"))
            .andExpect(status().isNotFound());
        mvc.perform(put("/api/conversations/{id}/receipt", id).contentType(MediaType.APPLICATION_JSON)
            .content("{\"throughMessageId\":-1,\"read\":true}")).andExpect(status().isBadRequest());
        assertThatThrownBy(() -> jdbc.update("UPDATE conversation_members SET last_read_message_id = 10 "
            + "WHERE conversation_id = ? AND pet_id = 1", id)).isInstanceOf(DataIntegrityViolationException.class);
    }

    private long open() throws Exception {
        String body = mvc.perform(post("/api/conversations").contentType(MediaType.APPLICATION_JSON)
            .content("{\"petId\":1000}")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return number(body, "$.id");
    }

    private String send(long id, UUID clientId, String body) throws Exception {
        return mvc.perform(post("/api/conversations/{id}/messages", id).contentType(MediaType.APPLICATION_JSON)
            .content(payload(clientId, body))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    }

    private static String payload(UUID clientId, String body) {
        return "{\"clientMessageId\":\"" + clientId + "\",\"body\":\"" + body + "\"}";
    }

    private static long number(String json, String path) { return JsonPath.<Number>read(json, path).longValue(); }
}
