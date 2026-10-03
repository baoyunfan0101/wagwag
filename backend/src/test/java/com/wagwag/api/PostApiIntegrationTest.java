package com.wagwag.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
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
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("dev")
@Testcontainers
class PostApiIntegrationTest {
    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
        DockerImageName.parse("postgis/postgis:17-3.5").asCompatibleSubstituteFor("postgres"));

    @Container
    static final GenericContainer<?> storage = new GenericContainer<>("chrislusf/seaweedfs:4.48")
        .withExposedPorts(8333)
        .withEnv("S3_BUCKET", "wagwag-avatars");

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("app.storage.bucket", () -> "wagwag-avatars");
        registry.add("app.storage.endpoint", PostApiIntegrationTest::storageUrl);
        registry.add("app.storage.public-base-url", () -> storageUrl() + "/wagwag-avatars");
        registry.add("app.storage.access-key", () -> "testaccess");
        registry.add("app.storage.secret-key", () -> "testsecret");
    }

    static String storageUrl() {
        return "http://" + storage.getHost() + ":" + storage.getMappedPort(8333);
    }

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void clearPosts() {
        jdbc.update("DELETE FROM posts");
    }

    @Test
    void postsPersistAndFeedIsNewestFirst() throws Exception {
        long firstId = createPost("{\"body\":\" A new walk today \"}");
        long secondId = createPost("{\"body\":\" Another walk \"}");

        mvc.perform(get("/api/posts/{id}", firstId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.body").value("A new walk today"))
            .andExpect(jsonPath("$.petName").value("Mochi"))
            .andExpect(jsonPath("$.imageUrls.length()").value(0))
            .andExpect(jsonPath("$.imageUrl").doesNotExist());
        mvc.perform(get("/api/feed"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items[0].id").value(secondId))
            .andExpect(jsonPath("$.items[1].id").value(firstId))
            .andExpect(jsonPath("$.nextCursor").value((Object) null));

        assertThat(jdbc.queryForObject("SELECT body FROM posts WHERE id = ?", String.class, firstId))
            .isEqualTo("A new walk today");
    }

    @Test
    void feedPagesAreOrderedAndEndWithoutDuplicates() throws Exception {
        long first = createPost("{\"body\":\"First\"}");
        long second = createPost("{\"body\":\"Second\"}");
        long third = createPost("{\"body\":\"Third\"}");
        long fourth = createPost("{\"body\":\"Fourth\"}");
        long fifth = createPost("{\"body\":\"Fifth\"}");

        String page1 = feed(2, null);
        assertThat(ids(page1)).containsExactly(fifth, fourth);
        String cursor1 = JsonPath.read(page1, "$.nextCursor");
        assertThat(cursor1).isNotBlank();

        String page2 = feed(2, cursor1);
        assertThat(ids(page2)).containsExactly(third, second);
        String cursor2 = JsonPath.read(page2, "$.nextCursor");
        assertThat(cursor2).isNotBlank();

        String page3 = feed(2, cursor2);
        assertThat(ids(page3)).containsExactly(first);
        assertThat((Object) JsonPath.read(page3, "$.nextCursor")).isNull();
    }

    @Test
    void feedUsesIdToBreakEqualTimestampTies() throws Exception {
        long first = createPost("{\"body\":\"First\"}");
        long second = createPost("{\"body\":\"Second\"}");
        long third = createPost("{\"body\":\"Third\"}");
        long fourth = createPost("{\"body\":\"Fourth\"}");
        jdbc.update("UPDATE posts SET created_at = TIMESTAMPTZ '2026-01-01 00:00:00+00'");

        String page1 = feed(2, null);
        String page2 = feed(2, JsonPath.read(page1, "$.nextCursor"));
        assertThat(ids(page1)).containsExactly(fourth, third);
        assertThat(ids(page2)).containsExactly(second, first);
        assertThat((Object) JsonPath.read(page2, "$.nextCursor")).isNull();
    }

    @Test
    void invalidFeedCursorAndLimitsAreRejected() throws Exception {
        mvc.perform(get("/api/feed").param("cursor", "not-a-valid-cursor"))
            .andExpect(status().isBadRequest());
        for (String limit : List.of("0", "-1", "51")) {
            mvc.perform(get("/api/feed").param("limit", limit))
                .andExpect(status().isBadRequest());
        }
    }

    @Test
    void concurrentLikesBothSucceedAndCreateOneRow() throws Exception {
        long id = createPost("{\"body\":\"Like me\"}");
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> first = pool.submit(() -> likeAfterSignal(id, start));
            Future<?> second = pool.submit(() -> likeAfterSignal(id, start));
            start.countDown();
            first.get(10, TimeUnit.SECONDS);
            second.get(10, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM likes WHERE post_id = ?", Long.class, id))
            .isEqualTo(1);
        mvc.perform(get("/api/posts/{id}", id))
            .andExpect(jsonPath("$.likeCount").value(1))
            .andExpect(jsonPath("$.likedByMe").value(true));
    }

    @Test
    void likesAndCommentsPersistWithoutDuplicateLikes() throws Exception {
        long id = createPost("{\"body\":\"Hello, WagWag!\"}");

        for (int attempt = 0; attempt < 2; attempt++) {
            mvc.perform(post("/api/posts/{id}/likes", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.likeCount").value(1))
                .andExpect(jsonPath("$.likedByMe").value(true));
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM likes WHERE post_id = ?", Long.class, id))
            .isEqualTo(1);

        mvc.perform(post("/api/posts/{id}/comments", id).contentType(MediaType.APPLICATION_JSON)
                .content("{\"body\":\" So cute! \"}"))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.body").value("So cute!"))
            .andExpect(jsonPath("$.petName").value("Mochi"));
        mvc.perform(get("/api/posts/{id}/comments", id))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].body").value("So cute!"));
        mvc.perform(get("/api/posts/{id}", id))
            .andExpect(jsonPath("$.commentCount").value(1));
        mvc.perform(get("/api/feed"))
            .andExpect(jsonPath("$.items[0].likeCount").value(1))
            .andExpect(jsonPath("$.items[0].commentCount").value(1))
            .andExpect(jsonPath("$.items[0].likedByMe").value(true));
        assertThat(jdbc.queryForObject("SELECT body FROM comments WHERE post_id = ?", String.class, id))
            .isEqualTo("So cute!");

        for (int attempt = 0; attempt < 2; attempt++) {
            mvc.perform(delete("/api/posts/{id}/likes", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.likeCount").value(0))
                .andExpect(jsonPath("$.likedByMe").value(false));
        }
    }

    @Test
    void invalidPostsAndCommentsAreRejected() throws Exception {
        mvc.perform(post("/api/posts").contentType(MediaType.APPLICATION_JSON)
                .content("{\"body\":\"  \"}"))
            .andExpect(status().isBadRequest());
        mvc.perform(post("/api/posts").contentType(MediaType.APPLICATION_JSON)
                .content("{\"imageUrl\":\"https://example.com/old-client.jpg\"}"))
            .andExpect(status().isBadRequest());
        mvc.perform(post("/api/posts").contentType(MediaType.APPLICATION_JSON)
                .content("{\"body\":\"Old client\",\"imageUrl\":\"https://example.com/old-client.jpg\"}"))
            .andExpect(status().isBadRequest());
        mvc.perform(post("/api/posts/999999/comments").contentType(MediaType.APPLICATION_JSON)
                .content("{\"body\":\"Hello\"}"))
            .andExpect(status().isNotFound());
        long id = createPost("{\"body\":\"A valid post\"}");
        mvc.perform(post("/api/posts/{id}/comments", id).contentType(MediaType.APPLICATION_JSON)
                .content("{\"body\":\"  \"}"))
            .andExpect(status().isBadRequest());
    }

    @Test
    void uploadedImagesPersistInOrderAndAppearInFeed() throws Exception {
        String firstTicket = imageTicket("image/png");
        String secondTicket = imageTicket("image/jpeg");
        byte[] firstBytes = new byte[] {1, 2, 3};
        byte[] secondBytes = new byte[] {4, 5, 6};
        assertThat(upload(firstTicket, firstBytes)).isBetween(200, 299);
        assertThat(upload(secondTicket, secondBytes)).isBetween(200, 299);
        String firstKey = JsonPath.read(firstTicket, "$.key");
        String secondKey = JsonPath.read(secondTicket, "$.key");
        String firstUrl = JsonPath.read(firstTicket, "$.publicUrl");
        String secondUrl = JsonPath.read(secondTicket, "$.publicUrl");

        long id = createPost("{\"body\":\"Two photos\",\"imageKeys\":[\"" + firstKey + "\",\"" + secondKey + "\"]}");
        mvc.perform(get("/api/posts/{id}", id))
            .andExpect(jsonPath("$.imageUrl").doesNotExist())
            .andExpect(jsonPath("$.imageUrls[0]").value(firstUrl))
            .andExpect(jsonPath("$.imageUrls[1]").value(secondUrl));
        mvc.perform(get("/api/feed"))
            .andExpect(jsonPath("$.items[0].imageUrls[0]").value(firstUrl))
            .andExpect(jsonPath("$.items[0].imageUrls[1]").value(secondUrl));
        assertThat(jdbc.queryForList("SELECT url FROM post_media WHERE post_id = ? ORDER BY sort_order", String.class, id))
            .containsExactly(firstUrl, secondUrl);
        try (HttpClient client = HttpClient.newHttpClient()) {
            var served = client.send(HttpRequest.newBuilder(URI.create(secondUrl)).GET().build(),
                HttpResponse.BodyHandlers.ofByteArray());
            assertThat(served.statusCode()).isEqualTo(200);
            assertThat(served.body()).isEqualTo(secondBytes);
        }
    }

    @Test
    void publishedPostImageCannotBeReplacedUsingItsUploadTicket() throws Exception {
        String ticket = imageTicket("image/jpeg");
        byte[] originalBytes = new byte[] {1, 2, 3, 4};
        byte[] replacementBytes = new byte[] {9, 8, 7, 6};
        assertThat(upload(ticket, originalBytes)).isBetween(200, 299);

        String key = JsonPath.read(ticket, "$.key");
        String publicUrl = JsonPath.read(ticket, "$.publicUrl");
        long id = createPost("{\"body\":\"Immutable photo\",\"imageKeys\":[\"" + key + "\"]}");
        mvc.perform(get("/api/posts/{id}", id))
            .andExpect(jsonPath("$.imageUrl").doesNotExist())
            .andExpect(jsonPath("$.imageUrls[0]").value(publicUrl));

        assertThat(upload(ticket, replacementBytes)).isGreaterThanOrEqualTo(300);
        try (HttpClient client = HttpClient.newHttpClient()) {
            var served = client.send(HttpRequest.newBuilder(URI.create(publicUrl)).GET().build(),
                HttpResponse.BodyHandlers.ofByteArray());
            assertThat(served.statusCode()).isEqualTo(200);
            assertThat(served.body()).isEqualTo(originalBytes).isNotEqualTo(replacementBytes);
        }
    }

    @Test
    void invalidPostImagesDoNotCreatePosts() throws Exception {
        mvc.perform(post("/api/posts/media-uploads").contentType(MediaType.APPLICATION_JSON)
                .content("{\"contentType\":\"application/pdf\"}"))
            .andExpect(status().isBadRequest());
        for (String key : List.of("invalid", "pets/2/posts/" + UUID.randomUUID() + ".png",
                "pets/1/posts/" + UUID.randomUUID() + ".png")) {
            mvc.perform(post("/api/posts").contentType(MediaType.APPLICATION_JSON)
                    .content("{\"imageKeys\":[\"" + key + "\"]}"))
                .andExpect(status().isBadRequest());
        }
        String repeatedKey = "pets/1/posts/" + UUID.randomUUID() + ".png";
        mvc.perform(post("/api/posts").contentType(MediaType.APPLICATION_JSON)
                .content("{\"imageKeys\":[\"" + repeatedKey + "\",\"" + repeatedKey + "\"]}"))
            .andExpect(status().isBadRequest());
        mvc.perform(post("/api/posts").contentType(MediaType.APPLICATION_JSON)
                .content("{\"imageKeys\":[\"a\",\"b\",\"c\",\"d\",\"e\"]}"))
            .andExpect(status().isBadRequest());
        String ticket = imageTicket("image/png");
        assertThat(upload(ticket, new byte[5 * 1024 * 1024 + 1])).isBetween(200, 299);
        String key = JsonPath.read(ticket, "$.key");
        mvc.perform(post("/api/posts").contentType(MediaType.APPLICATION_JSON)
                .content("{\"imageKeys\":[\"" + key + "\"]}"))
            .andExpect(status().isBadRequest());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM posts", Long.class)).isZero();
    }

    private long createPost(String body) throws Exception {
        String json = mvc.perform(post("/api/posts").contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString();
        Number id = JsonPath.read(json, "$.id");
        return id.longValue();
    }

    private String imageTicket(String contentType) throws Exception {
        String ticket = mvc.perform(post("/api/posts/media-uploads").contentType(MediaType.APPLICATION_JSON)
                .content("{\"contentType\":\"" + contentType + "\"}"))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
        Map<String, String> headers = JsonPath.read(ticket, "$.headers");
        assertThat(headers).containsEntry("Content-Type", contentType)
            .containsEntry("If-None-Match", "*");
        String uploadUrl = JsonPath.read(ticket, "$.uploadUrl");
        String signedQuery = URLDecoder.decode(URI.create(uploadUrl).getRawQuery(), StandardCharsets.UTF_8);
        assertThat(signedQuery).contains("if-none-match");
        return ticket;
    }

    private int upload(String ticket, byte[] bytes) throws Exception {
        String uploadUrl = JsonPath.read(ticket, "$.uploadUrl");
        Map<String, String> headers = JsonPath.read(ticket, "$.headers");
        var request = HttpRequest.newBuilder(URI.create(uploadUrl));
        headers.forEach(request::header);
        try (HttpClient client = HttpClient.newHttpClient()) {
            var response = client.send(request.PUT(HttpRequest.BodyPublishers.ofByteArray(bytes)).build(),
                HttpResponse.BodyHandlers.discarding());
            return response.statusCode();
        }
    }

    private String feed(int limit, String cursor) throws Exception {
        var request = get("/api/feed").param("limit", String.valueOf(limit));
        if (cursor != null) request.param("cursor", cursor);
        return mvc.perform(request).andExpect(status().isOk())
            .andExpect(jsonPath("$.items.length()").value(org.hamcrest.Matchers.lessThanOrEqualTo(limit)))
            .andReturn().getResponse().getContentAsString();
    }

    private static List<Long> ids(String page) {
        List<Number> numbers = JsonPath.read(page, "$.items[*].id");
        return numbers.stream().map(Number::longValue).toList();
    }

    private void likeAfterSignal(long id, CountDownLatch start) {
        try {
            start.await();
            mvc.perform(post("/api/posts/{id}/likes", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.likeCount").value(1))
                .andExpect(jsonPath("$.likedByMe").value(true));
        } catch (Exception error) {
            throw new RuntimeException(error);
        }
    }
}
