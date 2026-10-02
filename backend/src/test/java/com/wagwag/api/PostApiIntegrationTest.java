package com.wagwag.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
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
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("dev")
@Testcontainers
class PostApiIntegrationTest {
    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine");

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void clearPosts() {
        jdbc.update("DELETE FROM posts");
    }

    @Test
    void postsPersistAndFeedIsNewestFirst() throws Exception {
        long textId = createPost("{\"body\":\" A new walk today \"}");
        String imageUrl = "https://images.example.test/mochi.jpg";
        long imageId = createPost("{\"imageUrl\":\"" + imageUrl + "\"}");

        mvc.perform(get("/api/posts/{id}", textId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.body").value("A new walk today"))
            .andExpect(jsonPath("$.petName").value("Mochi"));
        mvc.perform(get("/api/posts/{id}", imageId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.imageUrl").value(imageUrl));
        mvc.perform(get("/api/feed"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items[0].id").value(imageId))
            .andExpect(jsonPath("$.items[0].imageUrl").value(imageUrl))
            .andExpect(jsonPath("$.items[1].id").value(textId))
            .andExpect(jsonPath("$.nextCursor").value((Object) null));

        assertThat(jdbc.queryForObject("SELECT body FROM posts WHERE id = ?", String.class, textId))
            .isEqualTo("A new walk today");
        assertThat(jdbc.queryForObject("SELECT url FROM post_media WHERE post_id = ?", String.class, imageId))
            .isEqualTo(imageUrl);
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
                .content("{\"imageUrl\":\"javascript:alert(1)\"}"))
            .andExpect(status().isBadRequest());
        mvc.perform(post("/api/posts/999999/comments").contentType(MediaType.APPLICATION_JSON)
                .content("{\"body\":\"Hello\"}"))
            .andExpect(status().isNotFound());
        long id = createPost("{\"body\":\"A valid post\"}");
        mvc.perform(post("/api/posts/{id}/comments", id).contentType(MediaType.APPLICATION_JSON)
                .content("{\"body\":\"  \"}"))
            .andExpect(status().isBadRequest());
    }

    private long createPost(String body) throws Exception {
        String json = mvc.perform(post("/api/posts").contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString();
        Number id = JsonPath.read(json, "$.id");
        return id.longValue();
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
