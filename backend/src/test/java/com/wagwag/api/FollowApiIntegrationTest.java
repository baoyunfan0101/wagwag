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
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("dev")
@Testcontainers
class FollowApiIntegrationTest {
    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
        DockerImageName.parse("postgis/postgis:17-3.5").asCompatibleSubstituteFor("postgres"));

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void clearInteractions() {
        jdbc.update("DELETE FROM pet_follows");
        jdbc.update("DELETE FROM posts");
    }

    @Test
    void followUnfollowAndListsUsePersistedRelationships() throws Exception {
        mvc.perform(get("/api/pets/discover"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items[0].id").value(1000))
            .andExpect(jsonPath("$.items[0].name").value("Biscuit"))
            .andExpect(jsonPath("$.items[0].followedByMe").value(false));
        for (int attempt = 0; attempt < 2; attempt++) {
            mvc.perform(post("/api/pets/1000/follow"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.followerCount").value(1))
                .andExpect(jsonPath("$.followedByMe").value(true));
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM pet_follows", Long.class)).isEqualTo(1);
        mvc.perform(get("/api/pets/1/following"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items[0].name").value("Biscuit"))
            .andExpect(jsonPath("$.items[0].followedByMe").value(true))
            .andExpect(jsonPath("$.nextPage").value((Object) null));
        mvc.perform(get("/api/pets/1000/followers"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items[0].name").value("Mochi"));
        mvc.perform(get("/api/pets/1/social"))
            .andExpect(jsonPath("$.followingCount").value(1));
        for (int attempt = 0; attempt < 2; attempt++) {
            mvc.perform(delete("/api/pets/1000/follow"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.followerCount").value(0))
                .andExpect(jsonPath("$.followedByMe").value(false));
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM pet_follows", Long.class)).isZero();
    }

    @Test
    void followingFeedUsesKeysetPagesAndOnlyFollowedPets() throws Exception {
        long first = insertPost(1000, "First", "2026-01-01 00:00:00+00");
        long second = insertPost(1000, "Second", "2026-01-01 00:00:00+00");
        long third = insertPost(1000, "Third", "2026-01-01 00:00:00+00");
        long mine = insertPost(1, "Mine", "2026-01-02 00:00:00+00");

        mvc.perform(get("/api/feed").param("following", "true"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items.length()").value(0));
        mvc.perform(post("/api/pets/1000/follow")).andExpect(status().isOk());

        String page1 = mvc.perform(get("/api/feed").param("following", "true").param("limit", "2"))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
        assertThat(ids(page1)).containsExactly(third, second);
        String cursor = JsonPath.read(page1, "$.nextCursor");
        assertThat(cursor).isNotBlank();
        String page2 = mvc.perform(get("/api/feed").param("following", "true")
                .param("limit", "2").param("cursor", cursor))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
        assertThat(ids(page2)).containsExactly(first);
        assertThat((Object) JsonPath.read(page2, "$.nextCursor")).isNull();
        assertThat(ids(page1)).doesNotContain(mine);

        mvc.perform(delete("/api/pets/1000/follow")).andExpect(status().isOk());
        mvc.perform(get("/api/feed").param("following", "true"))
            .andExpect(jsonPath("$.items.length()").value(0));
    }

    @Test
    void invalidTargetsAndListParametersAreRejected() throws Exception {
        mvc.perform(post("/api/pets/1/follow")).andExpect(status().isBadRequest());
        mvc.perform(post("/api/pets/999999/follow")).andExpect(status().isNotFound());
        mvc.perform(get("/api/pets/999999/followers")).andExpect(status().isNotFound());
        mvc.perform(get("/api/pets/discover").param("limit", "0"))
            .andExpect(status().isBadRequest());
        mvc.perform(get("/api/pets/1/following").param("page", "-1"))
            .andExpect(status().isBadRequest());
    }

    @Test
    void concurrentDuplicateFollowsCreateOneRow() throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var first = pool.submit(() -> followAfterSignal(start));
            var second = pool.submit(() -> followAfterSignal(start));
            start.countDown();
            first.get(10, TimeUnit.SECONDS);
            second.get(10, TimeUnit.SECONDS);
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM pet_follows WHERE follower_pet_id = 1 "
            + "AND following_pet_id = 1000", Long.class)).isEqualTo(1);
    }

    private long insertPost(long petId, String body, String createdAt) {
        return jdbc.queryForObject("INSERT INTO posts (pet_id, body, created_at, updated_at) "
            + "VALUES (?, ?, CAST(? AS TIMESTAMP WITH TIME ZONE), CAST(? AS TIMESTAMP WITH TIME ZONE)) "
            + "RETURNING id", Long.class, petId, body, createdAt, createdAt);
    }

    private static List<Long> ids(String page) {
        List<Number> values = JsonPath.read(page, "$.items[*].id");
        return values.stream().map(Number::longValue).toList();
    }

    private void followAfterSignal(CountDownLatch start) {
        try {
            start.await();
            mvc.perform(post("/api/pets/1000/follow"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.followerCount").value(1));
        } catch (Exception error) {
            throw new RuntimeException(error);
        }
    }
}
