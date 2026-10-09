package com.wagwag.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import java.util.HashSet;
import org.testcontainers.containers.GenericContainer;
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

    @Container
    static final GenericContainer<?> storage = new GenericContainer<>("chrislusf/seaweedfs:4.48")
        .withExposedPorts(8333).withEnv("S3_BUCKET", "wagwag-avatars");

    static String storageUrl() { return "http://" + storage.getHost() + ":" + storage.getMappedPort(8333); }

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("app.follow-cache.enabled", () -> false);
        registry.add("app.storage.bucket", () -> "wagwag-avatars");
        registry.add("app.storage.endpoint", ListingApiIntegrationTest::storageUrl);
        registry.add("app.storage.public-base-url", () -> storageUrl() + "/wagwag-avatars");
        registry.add("app.storage.access-key", () -> "testaccess");
        registry.add("app.storage.secret-key", () -> "testsecret");
    }

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM pet_blocks");
        jdbc.update("DELETE FROM pet_mutes");
        jdbc.update("UPDATE pets SET private_profile = FALSE WHERE id = 1000");
        jdbc.update("DELETE FROM listing_ratings");
        jdbc.update("DELETE FROM listing_orders");
        jdbc.update("DELETE FROM listing_favorites");
        jdbc.update("DELETE FROM listing_media WHERE listing_id <> 1000");
        jdbc.update("DELETE FROM listings WHERE id <> 1000");
        jdbc.update("UPDATE listings SET status = 'AVAILABLE' WHERE id = 1000");
    }

    @Test
    void createBrowseAndMediaOrder() throws Exception {
        String first = upload(new byte[] {1, 2, 3});
        String second = upload(new byte[] {4, 5, 6});
        long id = createImages(List.of(first, second));
        mvc.perform(get("/api/listings/{id}", id)).andExpect(status().isOk())
            .andExpect(jsonPath("$.sellerPetId").value(1))
            .andExpect(jsonPath("$.imageUrls[0]").value(storageUrl() + "/wagwag-avatars/" + first))
            .andExpect(jsonPath("$.imageUrls[1]").value(storageUrl() + "/wagwag-avatars/" + second));
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
            "{\"title\":\"Toy\",\"description\":\"Good\",\"priceCents\":100,\"imageKeys\":[\"http://example.test/a\"]}",
            "{\"title\":\"Toy\",\"description\":\"Good\",\"priceCents\":100,\"imageKeys\":[\"HTTPS://example.test/a\"]}",
            "{\"title\":\"Toy\",\"description\":\"Good\",\"priceCents\":100,\"imageKeys\":[\"https://example.test/a\",\"https://example.test/a\"]}"
        }) {
            mvc.perform(post("/api/listings").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest());
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM listings", Long.class)).isEqualTo(1);
    }

    @Test
    void listingUploadIsImmutableAndMetadataVerified() throws Exception {
        String ticket = ticket();
        String key = JsonPath.read(ticket, "$.key");
        byte[] original = new byte[] {10, 20, 30};
        assertThat(put(ticket, original)).isBetween(200, 299);
        createImages(List.of(key));
        int replacement = put(ticket, new byte[] {40, 50});
        assertThat(replacement < 200 || replacement >= 300).isTrue();
        String url = JsonPath.read(ticket, "$.publicUrl");
        try (var client = HttpClient.newHttpClient()) {
            byte[] served = client.send(HttpRequest.newBuilder(URI.create(url)).GET().build(), HttpResponse.BodyHandlers.ofByteArray()).body();
            assertThat(served).containsExactly(original);
        }
        mvc.perform(post("/api/listings/media-uploads").contentType(MediaType.APPLICATION_JSON)
            .content("{\"contentType\":\"application/pdf\"}")).andExpect(status().isBadRequest());
        String missing = JsonPath.read(ticket(), "$.key");
        mvc.perform(post("/api/listings").contentType(MediaType.APPLICATION_JSON)
            .content("{\"title\":\"Missing\",\"description\":\"Test\",\"priceCents\":0,\"imageKeys\":[\"" + missing + "\"]}"))
            .andExpect(status().isBadRequest());
        String oversized = upload(new byte[5 * 1024 * 1024 + 1]);
        mvc.perform(post("/api/listings").contentType(MediaType.APPLICATION_JSON)
            .content("{\"title\":\"Large\",\"description\":\"Test\",\"priceCents\":0,\"imageKeys\":[\"" + oversized + "\"]}"))
            .andExpect(status().isBadRequest());
    }

    @Test
    void searchNearbyAndRecommendationsRespectVisibilityAndCoarseLocation() throws Exception {
        long own = created("{\"title\":\"Cat carrier\",\"description\":\"Travel crate\",\"priceCents\":0,"
            + "\"latitude\":29.760412,\"longitude\":-95.369845}");
        mvc.perform(get("/api/listings").param("query", "carrier"))
            .andExpect(jsonPath("$.items.length()").value(1)).andExpect(jsonPath("$.items[0].id").value(own))
            .andExpect(jsonPath("$.items[0].latitude").value(29.76)).andExpect(jsonPath("$.items[0].longitude").value(-95.37));
        jdbc.update("UPDATE listings SET latitude = 29.76, longitude = -95.37 WHERE id = 1000");
        mvc.perform(get("/api/listings/nearby").param("latitude", "29.76").param("longitude", "-95.37").param("limit", "1"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(1)).andExpect(jsonPath("$.nextPage").value(1));
        mvc.perform(get("/api/listings/recommended"))
            .andExpect(jsonPath("$[0].id").value(1000));
        jdbc.update("INSERT INTO pet_blocks (blocker_pet_id, blocked_pet_id) VALUES (1000, 1)");
        mvc.perform(get("/api/listings/recommended")).andExpect(jsonPath("$.length()").value(0));
        mvc.perform(get("/api/listings/nearby").param("latitude", "29.76").param("longitude", "-95.37"))
            .andExpect(jsonPath("$.items.length()").value(1)).andExpect(jsonPath("$.items[0].id").value(own));
        mvc.perform(get("/api/listings/nearby").param("latitude", "91").param("longitude", "0"))
            .andExpect(status().isBadRequest());
    }

    private int favoriteAfterLatch(CountDownLatch ready, CountDownLatch go) throws Exception {
        ready.countDown();
        go.await();
        return mvc.perform(post("/api/listings/1000/favorites")).andReturn().getResponse().getStatus();
    }

    private long create(String title) throws Exception {
        String body = "{\"title\":\"" + title + "\",\"description\":\"Clean pet gear\","
            + "\"priceCents\":1200,\"imageKeys\":[]}";
        return created(body);
    }

    private long createImages(List<String> keys) throws Exception {
        return created("{\"title\":\"First listing\",\"description\":\"Clean pet gear\",\"priceCents\":1200,\"imageKeys\":[\""
            + String.join("\",\"", keys) + "\"]}");
    }

    private long created(String body) throws Exception {
        String content = mvc.perform(post("/api/listings").contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(content, "$.id")).longValue();
    }

    private String upload(byte[] bytes) throws Exception {
        String ticket = ticket();
        assertThat(put(ticket, bytes)).isBetween(200, 299);
        return JsonPath.read(ticket, "$.key");
    }

    private String ticket() throws Exception {
        return mvc.perform(post("/api/listings/media-uploads").contentType(MediaType.APPLICATION_JSON)
            .content("{\"contentType\":\"image/jpeg\"}")).andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
    }

    private int put(String ticket, byte[] bytes) throws Exception {
        String url = JsonPath.read(ticket, "$.uploadUrl");
        Map<String, String> headers = JsonPath.read(ticket, "$.headers");
        var request = HttpRequest.newBuilder(URI.create(url));
        headers.forEach(request::header);
        try (var client = HttpClient.newHttpClient()) {
            return client.send(request.PUT(HttpRequest.BodyPublishers.ofByteArray(bytes)).build(),
                HttpResponse.BodyHandlers.discarding()).statusCode();
        }
    }
}
