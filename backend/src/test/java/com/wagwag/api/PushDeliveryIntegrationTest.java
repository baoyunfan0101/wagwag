package com.wagwag.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sun.net.httpserver.HttpServer;
import com.wagwag.api.message.MessageInput;
import com.wagwag.api.message.MessageService;
import com.wagwag.api.notification.ExpoPushClient;
import com.wagwag.api.notification.NotificationService;
import com.wagwag.api.notification.PushDeliveryWorker;
import com.wagwag.api.notification.PushDeviceInput;
import com.wagwag.api.notification.PushDeviceService;
import com.wagwag.api.pet.PetRepository;
import com.wagwag.api.social.SocialPairLock;
import com.wagwag.api.social.SocialRestrictions;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
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
import tools.jackson.databind.ObjectMapper;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("dev")
@Testcontainers
class PushDeliveryIntegrationTest {
    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
        DockerImageName.parse("postgis/postgis:17-3.5").asCompatibleSubstituteFor("postgres"));

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired PetRepository pets;
    @Autowired SocialPairLock pairLock;
    @Autowired SocialRestrictions social;
    @Autowired NotificationService notifications;
    @Autowired TransactionTemplate transactions;
    @Autowired ObjectMapper json;
    HttpServer provider;
    PushDeliveryWorker worker;
    final AtomicReference<String> sendResult = new AtomicReference<>();
    final AtomicReference<String> receiptResult = new AtomicReference<>();
    final AtomicReference<String> requestBody = new AtomicReference<>();
    final AtomicInteger httpStatus = new AtomicInteger(200);
    final AtomicInteger sends = new AtomicInteger();
    UUID device;

    @BeforeEach
    void setup() throws Exception {
        jdbc.update("DELETE FROM conversations");
        jdbc.update("DELETE FROM push_deliveries");
        jdbc.update("DELETE FROM push_devices");
        device = UUID.randomUUID();
        sendResult.set("{\"data\":[{\"status\":\"ok\",\"id\":\"ticket-a\"}]}");
        receiptResult.set("{\"data\":{\"ticket-a\":{\"status\":\"ok\"}}}");
        provider = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        provider.createContext("/send", exchange -> {
            sends.incrementAndGet();
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] body = sendResult.get().getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(httpStatus.get(), body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        provider.createContext("/getReceipts", exchange -> {
            byte[] body = receiptResult.get().getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(httpStatus.get(), body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        provider.start();
        worker = new PushDeliveryWorker(jdbc, transactions,
            new ExpoPushClient(json, "http://127.0.0.1:" + provider.getAddress().getPort(), ""));
    }

    @AfterEach void stopProvider() { provider.stop(0); }

    @Test
    void registrationIsIdempotentPrivateAndExplicitDisablePermitsDeviceHandoff() throws Exception {
        register();
        register();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM push_devices", Long.class)).isEqualTo(1);
        PushDeviceService other = new PushDeviceService(jdbc, pets, 1000, false);
        assertThatThrownBy(() -> transactions.execute(tx -> other.register(device,
            new PushDeviceInput("ExpoPushToken[local_test]", PushDeviceInput.Platform.IOS))))
            .isInstanceOf(ResponseStatusException.class);
        transactions.executeWithoutResult(tx -> other.disable(device));
        assertThat(jdbc.queryForObject("SELECT enabled FROM push_devices WHERE id = ?", Boolean.class, device)).isTrue();
        mvc.perform(delete("/api/notifications/push-devices/{id}", device)).andExpect(status().isNoContent());
        mvc.perform(delete("/api/notifications/push-devices/{id}", device)).andExpect(status().isNoContent());
        transactions.executeWithoutResult(tx -> other.register(device,
            new PushDeviceInput("ExpoPushToken[local_test]", PushDeviceInput.Platform.IOS)));
        mvc.perform(get("/api/notifications/push-devices/{id}", device))
            .andExpect(jsonPath("$.registered").value(false));
        mvc.perform(put("/api/notifications/push-devices/{id}", UUID.randomUUID()).contentType(MediaType.APPLICATION_JSON)
            .content("{\"expoPushToken\":\"invalid\",\"platform\":\"IOS\"}")).andExpect(status().isBadRequest());
    }

    @Test
    void notificationsQueueOnceInSameTransactionAndProviderReceiptDoesNotMeanDeviceDelivery() throws Exception {
        register();
        MessageService peer = peer();
        long id = transactions.execute(tx -> peer.open(1)).id();
        MessageInput input = new MessageInput(UUID.randomUUID(), "Private body must stay private");
        transactions.executeWithoutResult(tx -> peer.send(id, input));
        transactions.executeWithoutResult(tx -> peer.send(id, input));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM push_deliveries", Long.class)).isEqualTo(1);
        worker.runOnce();
        assertThat(state()).isEqualTo("TICKET");
        assertThat(requestBody.get()).contains("You have a new message", "notificationId", "targetId")
            .doesNotContain("Private body must stay private");
        due();
        worker.runOnce();
        assertThat(state()).isEqualTo("COMPLETE");
        assertThat(sends.get()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT last_delivered_message_id FROM conversation_members "
            + "WHERE conversation_id = ? AND pet_id = 1", Long.class, id)).isZero();
        transactions.executeWithoutResult(tx -> {
            peer.send(id, new MessageInput(UUID.randomUUID(), "Rollback"));
            tx.setRollbackOnly();
        });
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM push_deliveries", Long.class)).isEqualTo(1);
    }

    @Test
    void taskAcceptanceQueuesOneGenericPushForCreatorEvenWhenAcceptanceIsRetried() throws Exception {
        PushDeviceService creatorDevice = new PushDeviceService(jdbc, pets, 1000, false);
        transactions.executeWithoutResult(tx -> creatorDevice.register(device,
            new PushDeviceInput("ExpoPushToken[local_test]", PushDeviceInput.Platform.ANDROID)));
        long taskId = jdbc.queryForObject("INSERT INTO tasks (creator_pet_id, title, description, category, latitude, longitude) "
            + "VALUES (1000, 'Private task title', 'Private instructions', 'CHECK_IN', 29.760412, -95.369845) RETURNING id", Long.class);
        try {
            mvc.perform(post("/api/tasks/{id}/accept", taskId)).andExpect(status().isOk());
            mvc.perform(post("/api/tasks/{id}/accept", taskId)).andExpect(status().isOk());
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM push_deliveries", Long.class)).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT recipient_pet_id FROM notifications n JOIN push_deliveries d "
                + "ON d.notification_id = n.id", Long.class)).isEqualTo(1000);
            worker.runOnce();
            assertThat(state()).isEqualTo("TICKET");
            assertThat(requestBody.get()).contains("A pet-care task was updated", "TASK_STATUS")
                .doesNotContain("Private task title", "Private instructions", "29.760412", "-95.369845");
        } finally { jdbc.update("DELETE FROM tasks WHERE id = ?", taskId); }
    }

    @Test
    void transientFailuresRetryWithBoundAndAlreadyReadNotificationsSkipSending() throws Exception {
        queue();
        httpStatus.set(429);
        for (int attempt = 0; attempt < 5; attempt++) { due(); worker.runOnce(); }
        assertThat(sends.get()).isEqualTo(5);
        assertThat(state()).isEqualTo("FAILED");
        worker.runOnce();
        assertThat(sends.get()).isEqualTo(5);
        jdbc.update("UPDATE push_deliveries SET status = 'PENDING'");
        jdbc.update("UPDATE notifications SET read_at = CURRENT_TIMESTAMP");
        due();
        worker.runOnce();
        assertThat(state()).isEqualTo("SKIPPED");
        assertThat(sends.get()).isEqualTo(5);
    }

    @Test
    void deviceNotRegisteredInTicketDisablesDeviceAndQueuedWorkStops() throws Exception {
        queue();
        sendResult.set("{\"data\":[{\"status\":\"error\",\"details\":{\"error\":\"DeviceNotRegistered\"}}]}");
        worker.runOnce();
        assertThat(state()).isEqualTo("FAILED");
        assertThat(jdbc.queryForObject("SELECT enabled FROM push_devices WHERE id = ?", Boolean.class, device)).isFalse();
        register();
        jdbc.update("UPDATE push_deliveries SET status = 'PENDING'");
        mvc.perform(delete("/api/notifications/push-devices/{id}", device)).andExpect(status().isNoContent());
        due();
        worker.runOnce();
        assertThat(state()).isEqualTo("SKIPPED");
        assertThat(sends.get()).isEqualTo(1);
    }

    @Test
    void oldTokenReceiptCannotDisableRotatedRegistration() throws Exception {
        queue();
        worker.runOnce();
        mvc.perform(put("/api/notifications/push-devices/{id}", device).contentType(MediaType.APPLICATION_JSON)
            .content("{\"expoPushToken\":\"ExpoPushToken[rotated_token]\",\"platform\":\"IOS\"}"))
            .andExpect(status().isOk());
        receiptResult.set("{\"data\":{\"ticket-a\":{\"status\":\"error\",\"details\":{\"error\":\"DeviceNotRegistered\"}}}}");
        due();
        worker.runOnce();
        assertThat(state()).isEqualTo("FAILED");
        assertThat(jdbc.queryForObject("SELECT enabled FROM push_devices WHERE id = ?", Boolean.class, device)).isTrue();
    }

    @Test
    void concurrentWorkersSkipLockedDeliveryInsteadOfDoubleSending() throws Exception {
        queue();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        provider.removeContext("/send");
        provider.createContext("/send", exchange -> {
            sends.incrementAndGet();
            entered.countDown();
            try { release.await(5, TimeUnit.SECONDS); } catch (InterruptedException error) { Thread.currentThread().interrupt(); }
            byte[] body = sendResult.get().getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(worker::runOnce);
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            var second = executor.submit(worker::runOnce);
            second.get(2, TimeUnit.SECONDS);
            release.countDown();
            first.get(5, TimeUnit.SECONDS);
        } finally { release.countDown(); }
        assertThat(sends.get()).isEqualTo(1);
        assertThat(state()).isEqualTo("TICKET");
    }

    private void queue() throws Exception {
        register();
        MessageService peer = peer();
        long id = transactions.execute(tx -> peer.open(1)).id();
        transactions.executeWithoutResult(tx -> peer.send(id, new MessageInput(UUID.randomUUID(), "Queued hello")));
    }

    private void register() throws Exception {
        mvc.perform(put("/api/notifications/push-devices/{id}", device).contentType(MediaType.APPLICATION_JSON)
            .content("{\"expoPushToken\":\"ExpoPushToken[local_test]\",\"platform\":\"IOS\"}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.registered").value(true));
    }

    private MessageService peer() { return new MessageService(jdbc, pets, social, pairLock, notifications, 1000); }
    private String state() { return jdbc.queryForObject("SELECT status FROM push_deliveries LIMIT 1", String.class); }
    private void due() { jdbc.update("UPDATE push_deliveries SET next_attempt_at = CURRENT_TIMESTAMP - INTERVAL '1 second'"); }
}
