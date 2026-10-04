package com.wagwag.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.wagwag.api.message.MessageInput;
import com.wagwag.api.message.MessageService;
import com.wagwag.api.notification.NotificationService;
import com.wagwag.api.pet.PetRepository;
import com.wagwag.api.realtime.RealtimeBus;
import com.wagwag.api.realtime.RealtimeSocketHandler;
import com.wagwag.api.social.SocialPairLock;
import com.wagwag.api.social.SocialRestrictions;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("dev")
@Testcontainers
class RealtimeIntegrationTest {
    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
        DockerImageName.parse("postgis/postgis:17-3.5").asCompatibleSubstituteFor("postgres"));
    @Container
    static final GenericContainer<?> redisContainer = new GenericContainer<>("redis:7.4-alpine").withExposedPorts(6379);

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.data.redis.url", () -> "redis://" + redisContainer.getHost() + ":" + redisContainer.getMappedPort(6379));
        registry.add("app.realtime.redis-enabled", () -> true);
    }

    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    @Autowired PetRepository pets;
    @Autowired SocialPairLock pairLock;
    @Autowired SocialRestrictions social;
    @Autowired TransactionTemplate transactions;
    @Autowired RealtimeBus bus;
    @Autowired RealtimeSocketHandler sockets;
    @Autowired StringRedisTemplate redis;
    @Autowired RedisConnectionFactory connections;
    @Autowired ObjectMapper json;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM conversations");
        jdbc.update("DELETE FROM pet_blocks");
        bus.connect();
    }

    @Test
    void committedMessageOnAnotherInstanceReachesOnlyRecipientViaRedisAndWebSocket() throws Exception {
        Listener listener = new Listener();
        WebSocket socket = connect(listener);
        assertThat(listener.next()).contains("SYNC");
        RealtimeBus remote = new RealtimeBus(new RealtimeSocketHandler(), redis, json, connections, true);
        try {
            MessageService peer = peer(remote);
            long id = transactions.execute(tx -> peer.open(1)).id();
            long messageId = transactions.execute(tx -> peer.send(id, new MessageInput(UUID.randomUUID(), "Remote hello"))).id();
            String event = listener.next();
            assertThat(json.readTree(event).path("type").asString()).isEqualTo("MESSAGE");
            assertThat(json.readTree(event).path("conversationId").asLong()).isEqualTo(id);
            assertThat(json.readTree(event).path("messageId").asLong()).isEqualTo(messageId);
            assertThat(event).doesNotContain("Remote hello");
            assertThat(listener.events.poll(300, TimeUnit.MILLISECONDS)).isNull();
            transactions.executeWithoutResult(tx -> remote.afterCommit(1000, new RealtimeBus.Event("NOTIFICATIONS", null, null, null)));
            assertThat(listener.events.poll(300, TimeUnit.MILLISECONDS)).isNull();
        } finally { remote.destroy(); socket.abort(); }
    }

    @Test
    void rollbackEmitsNothingAndLocalDeliveryWorksWithoutRedisRelay() throws Exception {
        Listener listener = new Listener();
        WebSocket socket = connect(listener);
        listener.next();
        RealtimeBus local = new RealtimeBus(sockets, redis, json, connections, false);
        try {
            MessageService peer = peer(local);
            long id = transactions.execute(tx -> peer.open(1)).id();
            transactions.executeWithoutResult(tx -> {
                peer.send(id, new MessageInput(UUID.randomUUID(), "Never committed"));
                tx.setRollbackOnly();
            });
            assertThat(listener.events.poll(300, TimeUnit.MILLISECONDS)).isNull();
            transactions.executeWithoutResult(tx -> peer.send(id, new MessageInput(UUID.randomUUID(), "Local hello")));
            assertThat(listener.next()).contains("MESSAGE");
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM messages", Long.class)).isEqualTo(1);
        } finally { local.destroy(); socket.abort(); }
    }

    @Test
    void reconnectResyncsAndHandshakeRejectsIdentitySelectionAndUnapprovedOrigin() throws Exception {
        Listener first = new Listener();
        WebSocket initial = connect(first);
        assertThat(first.next()).contains("SYNC");
        initial.sendText("ping", true).join();
        assertThat(first.next()).contains("PONG");
        initial.abort();
        Listener second = new Listener();
        WebSocket reconnected = connect(second);
        assertThat(second.next()).contains("SYNC");
        reconnected.abort();
        assertThatThrownBy(() -> HttpClient.newHttpClient().newWebSocketBuilder()
            .buildAsync(URI.create("ws://localhost:" + port + "/api/events?petId=1000"), new Listener()).get(5, TimeUnit.SECONDS))
            .hasCauseInstanceOf(java.net.http.WebSocketHandshakeException.class);
        assertThatThrownBy(() -> HttpClient.newHttpClient().newWebSocketBuilder().header("Origin", "https://unapproved.example")
            .buildAsync(URI.create("ws://localhost:" + port + "/api/events"), new Listener()).get(5, TimeUnit.SECONDS))
            .hasCauseInstanceOf(java.net.http.WebSocketHandshakeException.class);
    }

    private WebSocket connect(Listener listener) throws Exception {
        return HttpClient.newHttpClient().newWebSocketBuilder()
            .buildAsync(URI.create("ws://localhost:" + port + "/api/events"), listener).get(5, TimeUnit.SECONDS);
    }

    private MessageService peer(RealtimeBus publisher) {
        return new MessageService(jdbc, pets, social, pairLock, new NotificationService(jdbc, pets, publisher, 1000), 1000);
    }

    private static class Listener implements WebSocket.Listener {
        final LinkedBlockingQueue<String> events = new LinkedBlockingQueue<>();
        final StringBuilder fragments = new StringBuilder();

        @Override public void onOpen(WebSocket socket) { socket.request(1); }
        @Override public CompletionStage<?> onText(WebSocket socket, CharSequence data, boolean last) {
            fragments.append(data);
            if (last) { events.add(fragments.toString()); fragments.setLength(0); }
            socket.request(1);
            return CompletableFuture.completedFuture(null);
        }
        String next() throws InterruptedException {
            String event = events.poll(5, TimeUnit.SECONDS);
            assertThat(event).isNotNull();
            return event;
        }
    }
}
