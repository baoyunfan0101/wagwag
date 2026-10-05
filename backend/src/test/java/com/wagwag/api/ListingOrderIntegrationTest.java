package com.wagwag.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.wagwag.api.listing.ListingOrderService;
import com.wagwag.api.pet.PetRepository;
import com.wagwag.api.social.SocialPairLock;
import com.wagwag.api.social.SocialRestrictions;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
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

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("dev")
@Testcontainers
class ListingOrderIntegrationTest {
    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
        DockerImageName.parse("postgis/postgis:17-3.5").asCompatibleSubstituteFor("postgres"));
    @DynamicPropertySource
    static void database(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", postgres::getJdbcUrl);
        r.add("spring.datasource.username", postgres::getUsername);
        r.add("spring.datasource.password", postgres::getPassword);
        r.add("app.follow-cache.enabled", () -> false);
    }
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired PetRepository pets;
    @Autowired SocialRestrictions social;
    @Autowired SocialPairLock pairLock;
    @Autowired TransactionTemplate transactions;
    @Autowired ListingOrderService buyer;

    ListingOrderService seller() { return new ListingOrderService(jdbc, pets, social, pairLock, 1000); }
    ListingOrderService third() { return new ListingOrderService(jdbc, pets, social, pairLock, 1001); }

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM pet_blocks");
        jdbc.update("DELETE FROM listing_ratings");
        jdbc.update("DELETE FROM listing_orders");
        jdbc.update("DELETE FROM listings WHERE id <> 1000");
        jdbc.update("UPDATE listings SET status = 'AVAILABLE' WHERE id = 1000");
        jdbc.update("INSERT INTO pets (id, owner_id, name, species, gender) VALUES (1001, 1, 'Pip', 'Dog', 'UNKNOWN') ON CONFLICT DO NOTHING");
    }

    @Test
    void reserveRetryCompleteAndRateSeller() throws Exception {
        UUID key = UUID.randomUUID();
        long id = reserve(key);
        assertThat(reserve(key)).isEqualTo(id);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM listing_orders", Long.class)).isEqualTo(1);
        mvc.perform(get("/api/listings/1000")).andExpect(jsonPath("$.status").value("RESERVED"))
            .andExpect(jsonPath("$.myActiveOrderId").value(id));
        mvc.perform(get("/api/listings")).andExpect(jsonPath("$.items.length()").value(0));
        mvc.perform(get("/api/listing-orders")).andExpect(jsonPath("$.items[0].id").value(id));
        mvc.perform(post("/api/listing-orders/{id}/complete", id)).andExpect(status().isForbidden());
        mvc.perform(put("/api/listing-orders/{id}/rating", id).contentType(MediaType.APPLICATION_JSON)
            .content("{\"score\":5}")).andExpect(status().isConflict());
        transactions.executeWithoutResult(tx -> seller().complete(id));
        transactions.executeWithoutResult(tx -> seller().complete(id));
        mvc.perform(put("/api/listing-orders/{id}/rating", id).contentType(MediaType.APPLICATION_JSON)
            .content("{\"score\":5,\"comment\":\"Friendly handoff\"}")).andExpect(status().isOk())
            .andExpect(jsonPath("$.ratingScore").value(5));
        mvc.perform(put("/api/listing-orders/{id}/rating", id).contentType(MediaType.APPLICATION_JSON)
            .content("{\"score\":4}")).andExpect(status().isOk());
        mvc.perform(get("/api/listings/1000")).andExpect(jsonPath("$.status").value("SOLD"))
            .andExpect(jsonPath("$.sellerAverageRating").value(4.0)).andExpect(jsonPath("$.sellerRatingCount").value(1));
        mvc.perform(post("/api/listing-orders/{id}/cancel", id)).andExpect(status().isConflict());
        assertThatThrownBy(() -> transactions.execute(tx -> third().detail(id))).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> transactions.execute(tx -> seller().rate(id, 1, null))).isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void cancelRestoresInventoryAndRetryKeepsOldResource() throws Exception {
        UUID key = UUID.randomUUID();
        long id = reserve(key);
        mvc.perform(post("/api/listing-orders/{id}/cancel", id)).andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("CANCELLED"));
        mvc.perform(post("/api/listing-orders/{id}/cancel", id)).andExpect(status().isOk());
        assertThat(reserve(key)).isEqualTo(id);
        mvc.perform(get("/api/listings/1000")).andExpect(jsonPath("$.status").value("AVAILABLE"));
        long next = reserve(UUID.randomUUID());
        assertThat(next).isNotEqualTo(id);
        transactions.executeWithoutResult(tx -> seller().cancel(next));
        mvc.perform(get("/api/listings/1000")).andExpect(jsonPath("$.status").value("AVAILABLE"));
    }

    @Test
    void blockPreventsNewReservationButExistingParticipantsCanResolve() throws Exception {
        jdbc.update("INSERT INTO pet_blocks (blocker_pet_id, blocked_pet_id) VALUES (1000, 1)");
        mvc.perform(post("/api/listings/1000/orders").contentType(MediaType.APPLICATION_JSON)
            .content("{\"clientOrderId\":\"" + UUID.randomUUID() + "\"}")).andExpect(status().isNotFound());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM listing_orders", Long.class)).isZero();
        jdbc.update("DELETE FROM pet_blocks");
        UUID key = UUID.randomUUID();
        long id = reserve(key);
        jdbc.update("INSERT INTO pet_blocks (blocker_pet_id, blocked_pet_id) VALUES (1, 1000)");
        assertThat(reserve(key)).isEqualTo(id);
        mvc.perform(get("/api/listing-orders/{id}", id)).andExpect(status().isOk());
        transactions.executeWithoutResult(tx -> seller().complete(id));
        mvc.perform(get("/api/listing-orders/{id}", id)).andExpect(jsonPath("$.status").value("COMPLETED"));
    }

    @Test
    void concurrentBuyersCannotBothReserve() throws Exception {
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            Future<Integer> a = pool.submit(() -> race(buyer, UUID.randomUUID(), ready, go));
            Future<Integer> b = pool.submit(() -> race(third(), UUID.randomUUID(), ready, go));
            ready.await(); go.countDown();
            assertThat(new int[] {a.get(), b.get()}).containsExactlyInAnyOrder(200, 409);
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM listing_orders", Long.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT status FROM listings WHERE id = 1000", String.class)).isEqualTo("RESERVED");
    }

    @Test
    void concurrentExactRetriesCreateOneReservation() throws Exception {
        UUID key = UUID.randomUUID();
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            Future<Integer> a = pool.submit(() -> race(buyer, key, ready, go));
            Future<Integer> b = pool.submit(() -> race(buyer, key, ready, go));
            ready.await(); go.countDown();
            assertThat(a.get()).isEqualTo(200); assertThat(b.get()).isEqualTo(200);
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM listing_orders", Long.class)).isEqualTo(1);
    }

    @Test
    void invalidKeyAndForeignKeyReuseCannotCreateAnOrder() throws Exception {
        mvc.perform(post("/api/listings/1000/orders").contentType(MediaType.APPLICATION_JSON)
            .content("{\"clientOrderId\":\"bad\"}")).andExpect(status().isBadRequest());
        UUID key = UUID.randomUUID(); reserve(key);
        jdbc.update("INSERT INTO listings (id, seller_pet_id, title, description, price_cents) VALUES (1002, 1000, 'Toy', 'New', 10)");
        mvc.perform(post("/api/listings/1002/orders").contentType(MediaType.APPLICATION_JSON)
            .content("{\"clientOrderId\":\"" + key + "\"}")).andExpect(status().isConflict());
        assertThat(jdbc.queryForObject("SELECT status FROM listings WHERE id = 1002", String.class)).isEqualTo("AVAILABLE");
    }

    @Test
    void databaseRejectsTwoActiveOrdersAndInvalidRatings() throws Exception {
        long order = reserve(UUID.randomUUID());
        assertThatThrownBy(() -> jdbc.update("INSERT INTO listing_orders (client_order_id, listing_id, buyer_pet_id, price_cents) "
            + "VALUES (?, 1000, 1001, 1200)", UUID.randomUUID()))
            .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("INSERT INTO listing_ratings (order_id, score) VALUES (?, 6)", order))
            .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        mvc.perform(put("/api/listing-orders/{id}/rating", order).contentType(MediaType.APPLICATION_JSON)
            .content("{\"score\":6}")).andExpect(status().isBadRequest());
    }

    private int race(ListingOrderService service, UUID key, CountDownLatch ready, CountDownLatch go) throws Exception {
        ready.countDown(); go.await();
        try { transactions.execute(tx -> service.reserve(1000, key)); return 200; }
        catch (ResponseStatusException conflict) { return conflict.getStatusCode().value(); }
    }
    private long reserve(UUID key) throws Exception {
        String response = mvc.perform(post("/api/listings/1000/orders").contentType(MediaType.APPLICATION_JSON)
            .content("{\"clientOrderId\":\"" + key + "\"}")).andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(response, "$.id")).longValue();
    }
}
