package com.wagwag.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("dev")
@Testcontainers
class ListingApiIntegrationTest {
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

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM pet_blocks");
        jdbc.update("DELETE FROM pet_mutes");
        jdbc.update("UPDATE pets SET private_profile = FALSE WHERE id = 1000");
        jdbc.update("DELETE FROM listing_favorites");
        jdbc.update("DELETE FROM listing_media WHERE listing_id <> 1000");
        jdbc.update("DELETE FROM listings WHERE id <> 1000");
        jdbc.update("UPDATE listings SET status = 'AVAILABLE' WHERE id = 1000");
    }

    @Test
    void createBrowseAndMediaOrder() throws Exception {
        long id = create("First listing");
        mvc.perform(get("/api/listings/{id}", id)).andExpect(status().isOk())
            .andExpect(jsonPath("$.sellerPetId").value(1))
            .andExpect(jsonPath("$.imageUrls[0]").value("https://example.test/first.jpg"))
            .andExpect(jsonPath("$.imageUrls[1]").value("https://example.test/second.jpg"));
        mvc.perform(get("/api/listings")).andExpect(status().isOk())
            .andExpect(jsonPath("$.items[0].id").value(id));
        mvc.perform(get("/api/listings").param("scope", "mine"))
            .andExpect(jsonPath("$.items.length()").value(1))
            .andExpect(jsonPath("$.items[0].id").value(id));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM listing_media WHERE listing_id = ?", Long.class, id))
            .isEqualTo(2);
    }

    @Test
    void keysetPagesHandleEqualTimestampsAndInvalidInput() throws Exception {
        long first = create("First");
        long second = create("Second");
        long third = create("Third");
        jdbc.update("UPDATE listings SET created_at = '2026-10-04 12:00:00+00' WHERE id IN (?, ?, ?)",
            first, second, third);
        String page1 = mvc.perform(get("/api/listings").param("scope", "mine").param("limit", "2"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(2))
            .andReturn().getResponse().getContentAsString();
        assertThat((Integer) JsonPath.read(page1, "$.items[0].id")).isEqualTo((int) third);
        assertThat((Integer) JsonPath.read(page1, "$.items[1].id")).isEqualTo((int) second);
        String cursor = JsonPath.read(page1, "$.nextCursor");
        String page2 = mvc.perform(get("/api/listings").param("scope", "mine").param("limit", "2")
                .param("cursor", cursor)).andExpect(status().isOk())
            .andExpect(jsonPath("$.items.length()").value(1))
            .andExpect(jsonPath("$.nextCursor").value((Object) null))
            .andReturn().getResponse().getContentAsString();
        Set<Integer> ids = new HashSet<>();
        ids.add(JsonPath.read(page1, "$.items[0].id"));
        ids.add(JsonPath.read(page1, "$.items[1].id"));
        ids.add(JsonPath.read(page2, "$.items[0].id"));
        assertThat(ids).containsExactlyInAnyOrder((int) first, (int) second, (int) third);
        mvc.perform(get("/api/listings").param("cursor", "bad"))
            .andExpect(status().isBadRequest());
        mvc.perform(get("/api/listings").param("limit", "0"))
            .andExpect(status().isBadRequest());
        mvc.perform(get("/api/listings").param("limit", "51"))
            .andExpect(status().isBadRequest());
        mvc.perform(get("/api/listings").param("scope", "unknown"))
            .andExpect(status().isBadRequest());
    }

    @Test
    void favoritesAreIdempotentAndSoldListingsLeaveBrowse() throws Exception {
        mvc.perform(post("/api/listings/1000/favorites")).andExpect(status().isOk())
            .andExpect(jsonPath("$.favoritedByMe").value(true));
        mvc.perform(post("/api/listings/1000/favorites")).andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM listing_favorites WHERE listing_id = 1000",
            Long.class)).isEqualTo(1);
        mvc.perform(get("/api/listings").param("scope", "favorites"))
            .andExpect(jsonPath("$.items[0].id").value(1000));
        long own = create("Own listing");
        mvc.perform(post("/api/listings/{id}/favorites", own)).andExpect(status().isForbidden());
        mvc.perform(post("/api/listings/{id}/sold", own)).andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("SOLD"));
        mvc.perform(post("/api/listings/{id}/sold", own)).andExpect(status().isOk());
        mvc.perform(get("/api/listings").param("scope", "mine"))
            .andExpect(jsonPath("$.items[0].status").value("SOLD"));
        mvc.perform(get("/api/listings").param("scope", "available"))
            .andExpect(jsonPath("$.items.length()").value(1));
        mvc.perform(post("/api/listings/1000/sold")).andExpect(status().isForbidden());
        jdbc.update("UPDATE listings SET status = 'SOLD' WHERE id = 1000");
        mvc.perform(get("/api/listings").param("scope", "favorites"))
            .andExpect(jsonPath("$.items[0].status").value("SOLD"));
        mvc.perform(post("/api/listings/1000/favorites")).andExpect(status().isOk())
            .andExpect(jsonPath("$.favoritedByMe").value(true));
        mvc.perform(delete("/api/listings/1000/favorites")).andExpect(status().isOk())
            .andExpect(jsonPath("$.favoritedByMe").value(false));
        mvc.perform(delete("/api/listings/1000/favorites")).andExpect(status().isOk());
        mvc.perform(post("/api/listings/1000/favorites")).andExpect(status().isConflict());
    }

    @Test
    void concurrentFavoriteRequestsCreateOneRow() throws Exception {
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<Integer> a = executor.submit(() -> favoriteAfterLatch(ready, go));
            Future<Integer> b = executor.submit(() -> favoriteAfterLatch(ready, go));
            ready.await();
            go.countDown();
            assertThat(a.get()).isEqualTo(200);
            assertThat(b.get()).isEqualTo(200);
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM listing_favorites WHERE listing_id = 1000",
            Long.class)).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void blockEitherDirectionHidesListingAndPreventsFavorite(boolean sellerBlocksViewer) throws Exception {
        jdbc.update("INSERT INTO pet_blocks (blocker_pet_id, blocked_pet_id) VALUES (?, ?)",
            sellerBlocksViewer ? 1000 : 1, sellerBlocksViewer ? 1 : 1000);
        mvc.perform(get("/api/listings")).andExpect(status().isOk())
            .andExpect(jsonPath("$.items.length()").value(0));
        mvc.perform(get("/api/listings/1000")).andExpect(status().isNotFound());
        mvc.perform(post("/api/listings/1000/favorites")).andExpect(status().isNotFound());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM listing_favorites WHERE listing_id = 1000",
            Long.class)).isZero();
    }

    @Test
    void muteAndPrivateProfileDoNotHideMarketplace() throws Exception {
        jdbc.update("UPDATE pets SET private_profile = TRUE WHERE id = 1000");
        jdbc.update("INSERT INTO pet_mutes (muter_pet_id, muted_pet_id) VALUES (1, 1000)");
        mvc.perform(get("/api/listings")).andExpect(jsonPath("$.items[0].id").value(1000));
        mvc.perform(get("/api/listings/1000")).andExpect(status().isOk());
    }

    @Test
    void savedListingIsHiddenDuringBlockAndReturnsAfterUnblock() throws Exception {
        mvc.perform(post("/api/listings/1000/favorites")).andExpect(status().isOk());
        jdbc.update("INSERT INTO pet_blocks (blocker_pet_id, blocked_pet_id) VALUES (1000, 1)");
        mvc.perform(get("/api/listings").param("scope", "favorites"))
            .andExpect(jsonPath("$.items.length()").value(0));
        jdbc.update("DELETE FROM pet_blocks WHERE blocker_pet_id = 1000 AND blocked_pet_id = 1");
        mvc.perform(get("/api/listings").param("scope", "favorites"))
            .andExpect(jsonPath("$.items[0].id").value(1000));
    }

    @Test
    void contactSellerReusesConversationWithoutSendingMessage() throws Exception {
        String body = "{\"petId\":1000}";
        String first = mvc.perform(post("/api/conversations").contentType(MediaType.APPLICATION_JSON)
                .content(body)).andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
        String second = mvc.perform(post("/api/conversations").contentType(MediaType.APPLICATION_JSON)
                .content(body)).andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
        Number firstId = JsonPath.read(first, "$.id");
        Number secondId = JsonPath.read(second, "$.id");
        assertThat(secondId.longValue()).isEqualTo(firstId.longValue());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM messages WHERE conversation_id = ?",
            Long.class, firstId.longValue())).isZero();
    }

    @Test
    void invalidListingInputDoesNotPersist() throws Exception {
        for (String body : new String[] {
            "{\"title\":\" \",\"description\":\"Good\",\"priceCents\":100}",
            "{\"title\":\"Toy\",\"description\":\"Good\",\"priceCents\":-1}",
            "{\"title\":\"Toy\",\"description\":\"Good\",\"priceCents\":100,\"imageUrls\":[\"http://example.test/a\"]}",
            "{\"title\":\"Toy\",\"description\":\"Good\",\"priceCents\":100,\"imageUrls\":[\"HTTPS://example.test/a\"]}",
            "{\"title\":\"Toy\",\"description\":\"Good\",\"priceCents\":100,\"imageUrls\":[\"https://example.test/a\",\"https://example.test/a\"]}"
        }) {
            mvc.perform(post("/api/listings").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest());
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM listings", Long.class)).isEqualTo(1);
    }

    private int favoriteAfterLatch(CountDownLatch ready, CountDownLatch go) throws Exception {
        ready.countDown();
        go.await();
        return mvc.perform(post("/api/listings/1000/favorites")).andReturn().getResponse().getStatus();
    }

    private long create(String title) throws Exception {
        String body = "{\"title\":\"" + title + "\",\"description\":\"Clean pet gear\","
            + "\"priceCents\":1200,\"imageUrls\":[\"https://example.test/first.jpg\","
            + "\"https://example.test/second.jpg\"]}";
        String content = mvc.perform(post("/api/listings").contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(content, "$.id")).longValue();
    }
}
