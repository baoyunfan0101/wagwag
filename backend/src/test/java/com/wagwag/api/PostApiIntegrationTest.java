package com.wagwag.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import com.wagwag.api.storage.VideoProcessingWorker;
import com.wagwag.api.storage.FfmpegVideoProcessor;
import com.wagwag.api.storage.PostVideoStorage;
import com.wagwag.api.storage.S3Objects;
import org.springframework.transaction.support.TransactionTemplate;
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

@SpringBootTest(properties = "app.video.processing-enabled=false")
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
    @Autowired VideoProcessingWorker worker;
    @Autowired TransactionTemplate transactions;
    @Autowired S3Objects objects;
    @Autowired PostVideoStorage videos;

    @BeforeEach
    void clearPosts() {
        jdbc.update("DELETE FROM posts");
        jdbc.update("DELETE FROM video_uploads");
    }

    @Test
    void postsPersistAndFeedIsNewestFirst() throws Exception {
        long firstId = createPost("{\"body\":\" A new walk today \"}");
        long secondId = createPost("{\"body\":\" Another walk \"}");

        mvc.perform(get("/api/posts/{id}", firstId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.body").value("A new walk today"))
            .andExpect(jsonPath("$.petName").value("Mochi"))
            .andExpect(jsonPath("$.videoUrl").value((Object) null))
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

    @Test
    void videoUploadPublishesInFeedAndCannotBeReplaced() throws Exception {
        String ticket = videoTicket("video/mp4");
        String key = JsonPath.read(ticket, "$.key");
        byte[] original = videoBytes("mp4");
        assertThat(upload(ticket, original)).isBetween(200, 299);
        // A create-only ticket cannot be reused even before the post is published.
        assertThat(upload(ticket, new byte[] {9, 8, 7})).isGreaterThanOrEqualTo(300);
        String ready = processVideo(ticket);
        String url = JsonPath.read(ready, "$.videoUrl");
        String thumbnail = JsonPath.read(ready, "$.thumbnailUrl");
        long id = createPost("{\"body\":\"A video moment\",\"videoKey\":\"" + key + "\"}");
        mvc.perform(get("/api/posts/{id}", id)).andExpect(jsonPath("$.videoUrl").value(url))
            .andExpect(jsonPath("$.videoThumbnailUrl").value(thumbnail))
            .andExpect(jsonPath("$.imageUrls.length()").value(0));
        mvc.perform(get("/api/feed")).andExpect(jsonPath("$.items[0].videoUrl").value(url))
            .andExpect(jsonPath("$.items[0].videoThumbnailUrl").value(thumbnail));
        mvc.perform(post("/api/posts/{id}/likes", id)).andExpect(jsonPath("$.likeCount").value(1))
            .andExpect(jsonPath("$.videoUrl").value(url));
        mvc.perform(post("/api/posts/{id}/comments", id).contentType(MediaType.APPLICATION_JSON)
            .content("{\"body\":\"Great video!\"}")).andExpect(status().isCreated());
        assertThat(jdbc.queryForObject("SELECT video_url FROM posts WHERE id = ?", String.class, id)).isEqualTo(url);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM post_media WHERE post_id = ?", Long.class, id)).isZero();
        assertThat(upload(ticket, new byte[] {8, 8, 8})).isGreaterThanOrEqualTo(300);
        try (HttpClient client = HttpClient.newHttpClient()) {
            var served = client.send(HttpRequest.newBuilder(URI.create(sourceUrl(ticket))).GET().build(), HttpResponse.BodyHandlers.ofByteArray());
            assertThat(served.statusCode()).isEqualTo(200);
            assertThat(served.body()).isEqualTo(original);
        }
    }

    @Test
    void supportedVideoFormatsAndCommunityPostsWork() throws Exception {
        String community = mvc.perform(post("/api/communities").contentType(MediaType.APPLICATION_JSON)
            .content("{\"name\":\"Video pets\",\"description\":\"Pet moments\"}"))
            .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        long communityId = ((Number) JsonPath.read(community, "$.id")).longValue();
        for (String type : List.of("video/quicktime", "video/webm")) {
            String ticket = videoTicket(type);
            assertThat(upload(ticket, videoBytes(type.equals("video/webm") ? "webm" : "mov"))).isBetween(200, 299);
            String key = JsonPath.read(ticket, "$.key");
            String url = JsonPath.read(processVideo(ticket), "$.videoUrl");
            createPost("{\"videoKey\":\"" + key + "\",\"communityId\":" + communityId + "}");
            mvc.perform(get("/api/communities/{id}/feed", communityId))
                .andExpect(jsonPath("$.items[0].videoUrl").value(url));
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM posts", Long.class)).isEqualTo(2);
    }

    @Test
    void invalidOrMixedVideoInputsCreateNoPosts() throws Exception {
        mvc.perform(post("/api/posts/video-uploads").contentType(MediaType.APPLICATION_JSON)
            .content("{\"contentType\":\"application/pdf\"}")).andExpect(status().isBadRequest());
        for (String key : List.of("", "invalid", "pets/2/posts/videos/" + UUID.randomUUID() + ".mp4",
            "pets/1/posts/" + UUID.randomUUID() + ".png", "pets/1/posts/videos/" + UUID.randomUUID() + ".mp4")) {
            mvc.perform(post("/api/posts").contentType(MediaType.APPLICATION_JSON)
                .content("{\"body\":\"Video\",\"videoKey\":\"" + key + "\"}"))
                .andExpect(status().isBadRequest());
        }
        mvc.perform(post("/api/posts").contentType(MediaType.APPLICATION_JSON)
            .content("{\"body\":\"Link\",\"videoUrl\":\"https://example.test/video.mp4\"}"))
            .andExpect(status().isBadRequest());
        String ticket = videoTicket("video/mp4");
        assertThat(upload(ticket, new byte[] {1, 2, 3})).isBetween(200, 299);
        String key = JsonPath.read(ticket, "$.key");
        mvc.perform(post("/api/posts").contentType(MediaType.APPLICATION_JSON)
            .content("{\"videoKey\":\"" + key + "\",\"imageKeys\":[\"a\"]}"))
            .andExpect(status().isBadRequest());
        String oversized = videoTicket("video/mp4");
        assertThat(upload(oversized, new byte[50 * 1024 * 1024 + 1])).isBetween(200, 299);
        String oversizedKey = JsonPath.read(oversized, "$.key");
        mvc.perform(post("/api/posts").contentType(MediaType.APPLICATION_JSON)
            .content("{\"videoKey\":\"" + oversizedKey + "\"}")).andExpect(status().isBadRequest());
        String empty = videoTicket("video/mp4");
        assertThat(upload(empty, new byte[0])).isBetween(200, 299);
        mvc.perform(post("/api/posts").contentType(MediaType.APPLICATION_JSON)
            .content("{\"videoKey\":\"" + JsonPath.read(empty, "$.key") + "\"}"))
            .andExpect(status().isBadRequest());
        // SeaweedFS permits fixture writes; publishing must inspect the actual object metadata.
        String wrongType = videoTicket("video/mp4");
        try (HttpClient client = HttpClient.newHttpClient()) {
            var response = client.send(HttpRequest.newBuilder(URI.create(sourceUrl(wrongType)))
                .header("Content-Type", "application/pdf").PUT(HttpRequest.BodyPublishers.ofByteArray(new byte[] {1})).build(),
                HttpResponse.BodyHandlers.discarding());
            assertThat(response.statusCode()).isBetween(200, 299);
        }
        mvc.perform(post("/api/posts").contentType(MediaType.APPLICATION_JSON)
            .content("{\"videoKey\":\"" + JsonPath.read(wrongType, "$.key") + "\"}"))
            .andExpect(status().isBadRequest());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM posts", Long.class)).isZero();
    }

    @Test
    void videoPostsKeepPrivateBlockAndFollowingFeedRules() throws Exception {
        String url = "https://example.test/video.mp4";
        long id = jdbc.queryForObject("INSERT INTO posts (pet_id, video_url) VALUES (1000, ?) RETURNING id", Long.class, url);
        jdbc.update("UPDATE pets SET private_profile = TRUE WHERE id = 1000");
        try {
            mvc.perform(get("/api/feed")).andExpect(jsonPath("$.items.length()").value(0));
            mvc.perform(get("/api/posts/{id}", id)).andExpect(status().isNotFound());
            jdbc.update("INSERT INTO pet_follows (follower_pet_id, following_pet_id, accepted) VALUES (1, 1000, TRUE)");
            mvc.perform(get("/api/feed").param("following", "true"))
                .andExpect(jsonPath("$.items[0].videoUrl").value(url));
            mvc.perform(get("/api/posts/{id}", id)).andExpect(status().isOk());
            jdbc.update("INSERT INTO pet_blocks (blocker_pet_id, blocked_pet_id) VALUES (1000, 1)");
            mvc.perform(get("/api/feed")).andExpect(jsonPath("$.items.length()").value(0));
            mvc.perform(get("/api/posts/{id}", id)).andExpect(status().isNotFound());
        } finally {
            jdbc.update("DELETE FROM pet_blocks WHERE blocker_pet_id = 1000 AND blocked_pet_id = 1");
            jdbc.update("DELETE FROM pet_follows WHERE follower_pet_id = 1 AND following_pet_id = 1000");
            jdbc.update("UPDATE pets SET private_profile = FALSE WHERE id = 1000");
        }
    }

    @Test
    void videoCompletionIsOwnedAndRequiresAnUploadedObject() throws Exception {
        String ticket = videoTicket("video/mp4");
        String id = JsonPath.read(ticket, "$.id");
        String key = JsonPath.read(ticket, "$.key");
        mvc.perform(get("/api/posts/video-uploads/{id}", id)).andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("UPLOADING"))
            .andExpect(jsonPath("$.videoUrl").value((Object) null));
        mvc.perform(post("/api/posts/video-uploads/{id}/complete", id)).andExpect(status().isBadRequest());
        assertThat(upload(ticket, videoBytes("mp4"))).isBetween(200, 299);
        mvc.perform(post("/api/posts").contentType(MediaType.APPLICATION_JSON)
            .content("{\"videoKey\":\"" + key + "\"}")).andExpect(status().isConflict());
        jdbc.update("UPDATE video_uploads SET pet_id = 1000 WHERE id = ?", UUID.fromString(id));
        mvc.perform(get("/api/posts/video-uploads/{id}", id)).andExpect(status().isNotFound());
        for (String action : List.of("complete", "retry", "ticket")) {
            mvc.perform(post("/api/posts/video-uploads/{id}/{action}", id, action)).andExpect(status().isNotFound());
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM posts", Long.class)).isZero();
    }

    @Test
    void concurrentCompletionAndWorkersProcessOneJobAndProduceImmutableAssets() throws Exception {
        String ticket = videoTicket("video/mp4");
        String id = JsonPath.read(ticket, "$.id");
        assertThat(upload(ticket, videoBytes("mp4"))).isBetween(200, 299);
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            CountDownLatch start = new CountDownLatch(1);
            Runnable complete = () -> {
                try {
                    start.await();
                    mvc.perform(post("/api/posts/video-uploads/{id}/complete", id)).andExpect(status().isOk())
                        .andExpect(jsonPath("$.status").value("QUEUED"));
                } catch (Exception error) { throw new RuntimeException(error); }
            };
            Future<?> first = executor.submit(complete);
            Future<?> second = executor.submit(complete);
            start.countDown();
            first.get(10, TimeUnit.SECONDS);
            second.get(10, TimeUnit.SECONDS);
            CountDownLatch process = new CountDownLatch(1);
            var workerA = executor.submit(() -> { process.await(); return worker.processNext(); });
            var workerB = executor.submit(() -> { process.await(); return worker.processNext(); });
            process.countDown();
            assertThat(List.of(workerA.get(30, TimeUnit.SECONDS), workerB.get(30, TimeUnit.SECONDS)))
                .containsExactlyInAnyOrder(true, false);
        }
        assertThat(jdbc.queryForObject("SELECT attempts FROM video_uploads WHERE id = ?", Integer.class, UUID.fromString(id))).isEqualTo(1);
        String ready = mvc.perform(get("/api/posts/video-uploads/{id}", id)).andExpect(jsonPath("$.status").value("READY"))
            .andReturn().getResponse().getContentAsString();
        String videoUrl = JsonPath.read(ready, "$.videoUrl");
        String thumbnailUrl = JsonPath.read(ready, "$.thumbnailUrl");
        try (HttpClient client = HttpClient.newHttpClient()) {
            var video = client.send(HttpRequest.newBuilder(URI.create(videoUrl)).GET().build(), HttpResponse.BodyHandlers.ofByteArray());
            assertThat(video.statusCode()).isEqualTo(200);
            assertThat(video.headers().firstValue("Content-Type")).contains("video/mp4");
            assertThat(video.headers().firstValue("Cache-Control").orElseThrow()).contains("immutable");
            Path output = Files.createTempFile("wagwag-processed-test-", ".mp4");
            try {
                Files.write(output, video.body());
                Process probe = new ProcessBuilder("ffprobe", "-v", "error", "-show_entries", "stream=codec_name,pix_fmt,width,height",
                    "-of", "json", output.toString()).start();
                String info = new String(probe.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                assertThat(probe.waitFor(10, TimeUnit.SECONDS)).isTrue();
                assertThat(probe.exitValue()).isZero();
                assertThat(info).contains("h264", "yuv420p", "160", "90");
            } finally { Files.deleteIfExists(output); }
            var thumbnail = client.send(HttpRequest.newBuilder(URI.create(thumbnailUrl)).GET().build(), HttpResponse.BodyHandlers.ofByteArray());
            assertThat(thumbnail.statusCode()).isEqualTo(200);
            var image = javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(thumbnail.body()));
            assertThat(image.getWidth()).isEqualTo(320);
            var rejected = client.send(HttpRequest.newBuilder(URI.create(videoUrl)).header("If-None-Match", "*")
                .PUT(HttpRequest.BodyPublishers.ofByteArray(new byte[] {9})).build(), HttpResponse.BodyHandlers.discarding());
            assertThat(rejected.statusCode()).isGreaterThanOrEqualTo(300);
            var unchanged = client.send(HttpRequest.newBuilder(URI.create(videoUrl)).GET().build(), HttpResponse.BodyHandlers.ofByteArray());
            assertThat(unchanged.body()).isEqualTo(video.body());
        }
        mvc.perform(post("/api/posts/video-uploads/{id}/complete", id)).andExpect(jsonPath("$.videoUrl").value(videoUrl));
        mvc.perform(post("/api/posts/video-uploads/{id}/retry", id)).andExpect(jsonPath("$.videoUrl").value(videoUrl));
        mvc.perform(post("/api/posts/video-uploads/{id}/ticket", id)).andExpect(status().isConflict());
    }

    @Test
    void invalidVideoCannotBePublishedOrRetriedAsValidMedia() throws Exception {
        String ticket = videoTicket("video/mp4");
        assertThat(upload(ticket, new byte[] {1, 2, 3})).isBetween(200, 299);
        String id = JsonPath.read(ticket, "$.id");
        mvc.perform(post("/api/posts/video-uploads/{id}/complete", id)).andExpect(status().isOk());
        assertThat(worker.processNext()).isTrue();
        mvc.perform(get("/api/posts/video-uploads/{id}", id)).andExpect(jsonPath("$.status").value("FAILED"))
            .andExpect(jsonPath("$.error").value("INVALID_VIDEO"));
        mvc.perform(post("/api/posts/video-uploads/{id}/retry", id)).andExpect(status().isBadRequest());
        mvc.perform(post("/api/posts").contentType(MediaType.APPLICATION_JSON)
            .content("{\"videoKey\":\"" + JsonPath.read(ticket, "$.key") + "\"}")).andExpect(status().isConflict());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM posts", Long.class)).isZero();
    }

    @Test
    void transientProcessingFailuresAreBoundedAndExplicitRetryUsesTheSameSource() throws Exception {
        String ticket = videoTicket("video/mp4");
        String id = JsonPath.read(ticket, "$.id");
        assertThat(upload(ticket, videoBytes("mp4"))).isBetween(200, 299);
        mvc.perform(post("/api/posts/video-uploads/{id}/complete", id)).andExpect(status().isOk());
        var failing = new VideoProcessingWorker(jdbc, transactions, objects, videos,
            new FfmpegVideoProcessor("/nonexistent-wagwag-ffmpeg"), false);
        for (int attempt = 1; attempt <= 3; attempt++) {
            assertThat(failing.processNext()).isTrue();
            String expected = attempt < 3 ? "QUEUED" : "FAILED";
            mvc.perform(get("/api/posts/video-uploads/{id}", id)).andExpect(jsonPath("$.status").value(expected))
                .andExpect(jsonPath("$.error").value("PROCESSOR_UNAVAILABLE"));
            if (attempt < 3) {
                assertThat(failing.processNext()).isFalse();
                jdbc.update("UPDATE video_uploads SET next_attempt_at = CURRENT_TIMESTAMP WHERE id = ?", UUID.fromString(id));
            }
        }
        mvc.perform(post("/api/posts/video-uploads/{id}/retry", id)).andExpect(jsonPath("$.status").value("QUEUED"));
        assertThat(worker.processNext()).isTrue();
        mvc.perform(get("/api/posts/video-uploads/{id}", id)).andExpect(jsonPath("$.status").value("READY"))
            .andExpect(jsonPath("$.key").value(JsonPath.<String>read(ticket, "$.key")));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM video_uploads", Long.class)).isEqualTo(1);
        assertThat(upload(ticket, new byte[] {9})).isGreaterThanOrEqualTo(300);
    }

    @Test
    void expiredWorkerLeaseCanBeRecoveredWithoutReuploading() throws Exception {
        String ticket = videoTicket("video/mp4");
        String id = JsonPath.read(ticket, "$.id");
        assertThat(upload(ticket, videoBytes("mp4"))).isBetween(200, 299);
        mvc.perform(post("/api/posts/video-uploads/{id}/complete", id)).andExpect(status().isOk());
        jdbc.update("UPDATE video_uploads SET status = 'PROCESSING', attempts = 1, processing_token = ?, "
            + "lease_until = CURRENT_TIMESTAMP - INTERVAL '1 minute' WHERE id = ?", UUID.randomUUID(), UUID.fromString(id));
        assertThat(worker.processNext()).isTrue();
        mvc.perform(get("/api/posts/video-uploads/{id}", id)).andExpect(jsonPath("$.status").value("READY"));
        assertThat(jdbc.queryForObject("SELECT attempts FROM video_uploads WHERE id = ?", Integer.class, UUID.fromString(id))).isEqualTo(2);
    }

    @Test
    void compressionNormalizesLargeVideoAndAudio() throws Exception {
        Path source = Files.createTempFile("wagwag-large-video-", ".mov");
        try {
            Process generate = new ProcessBuilder("ffmpeg", "-hide_banner", "-loglevel", "error", "-y",
                "-f", "lavfi", "-i", "color=c=blue:s=1920x1080:r=30", "-f", "lavfi", "-i", "sine=frequency=440",
                "-t", "1", "-c:v", "mpeg4", "-q:v", "2", "-threads", "1", "-c:a", "pcm_s16le", source.toString()).inheritIO().start();
            assertThat(generate.waitFor(30, TimeUnit.SECONDS)).isTrue();
            assertThat(generate.exitValue()).isZero();
            String ticket = videoTicket("video/quicktime");
            byte[] original = Files.readAllBytes(source);
            assertThat(upload(ticket, original)).isBetween(200, 299);
            String ready = processVideo(ticket);
            try (HttpClient client = HttpClient.newHttpClient()) {
                var output = client.send(HttpRequest.newBuilder(URI.create(JsonPath.read(ready, "$.videoUrl"))).GET().build(),
                    HttpResponse.BodyHandlers.ofByteArray());
                assertThat(output.body().length).isLessThan(original.length);
                Files.write(source, output.body());
                Process probe = new ProcessBuilder("ffprobe", "-v", "error", "-show_entries", "stream=codec_name,width,height",
                    "-of", "json", source.toString()).start();
                String info = new String(probe.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                assertThat(probe.waitFor(10, TimeUnit.SECONDS)).isTrue();
                assertThat(probe.exitValue()).isZero();
                assertThat(info).contains("h264", "aac", "1280", "720").doesNotContain("1920", "1080", "pcm_s16le");
            }
        } finally { Files.deleteIfExists(source); }
    }

    @Test
    void configuredCdnPrefixIsUsedOnlyForProcessedAssets() throws Exception {
        String ticket = videoTicket("video/mp4");
        assertThat(upload(ticket, videoBytes("mp4"))).isBetween(200, 299);
        processVideo(ticket);
        var cdn = new PostVideoStorage(objects, jdbc, "https://media.example.test/assets/");
        var asset = cdn.verifyAndGetAsset(1, JsonPath.read(ticket, "$.key"));
        assertThat(asset.videoUrl()).startsWith("https://media.example.test/assets/pets/1/posts/processed/").endsWith(".mp4");
        assertThat(asset.thumbnailUrl()).startsWith("https://media.example.test/assets/pets/1/posts/processed/").endsWith(".jpg");
    }

    private String processVideo(String ticket) throws Exception {
        String id = JsonPath.read(ticket, "$.id");
        mvc.perform(post("/api/posts/video-uploads/{id}/complete", id))
            .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("QUEUED"));
        assertThat(worker.processNext()).isTrue();
        return mvc.perform(get("/api/posts/video-uploads/{id}", id))
            .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("READY"))
            .andReturn().getResponse().getContentAsString();
    }

    static byte[] videoBytes(String format) throws Exception {
        Path file = Files.createTempFile("wagwag-video-test-", "." + format);
        try {
            String codec = format.equals("webm") ? "libvpx-vp9" : "libx264";
            Process process = new ProcessBuilder("ffmpeg", "-hide_banner", "-loglevel", "error", "-y",
                "-f", "lavfi", "-i", "color=c=green:s=160x90:r=15", "-t", "1", "-c:v", codec,
                "-threads", "1", "-pix_fmt", "yuv420p", file.toString()).inheritIO().start();
            assertThat(process.waitFor(30, TimeUnit.SECONDS)).isTrue();
            assertThat(process.exitValue()).isZero();
            return Files.readAllBytes(file);
        } finally { Files.deleteIfExists(file); }
    }

    private static String sourceUrl(String ticket) {
        return storageUrl() + "/wagwag-avatars/" + JsonPath.read(ticket, "$.key");
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
        return ticket("/api/posts/media-uploads", contentType);
    }

    private String videoTicket(String contentType) throws Exception {
        return ticket("/api/posts/video-uploads", contentType);
    }

    private String ticket(String path, String contentType) throws Exception {
        String ticket = mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON)
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
