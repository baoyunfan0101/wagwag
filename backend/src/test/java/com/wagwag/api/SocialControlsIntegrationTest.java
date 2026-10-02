package com.wagwag.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("dev")
@Testcontainers
class SocialControlsIntegrationTest {
    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine");

    @Container
    static final GenericContainer<?> redis = new GenericContainer<>("redis:7.4-alpine")
        .withExposedPorts(6379);

    @DynamicPropertySource
    static void services(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.data.redis.url", () -> "redis://" + redis.getHost() + ":" + redis.getMappedPort(6379));
    }

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired StringRedisTemplate cache;

    @BeforeEach
    void clearInteractions() {
        jdbc.update("DELETE FROM pet_blocks");
        jdbc.update("DELETE FROM pet_mutes");
        jdbc.update("DELETE FROM pet_follows");
        jdbc.update("DELETE FROM posts");
        jdbc.update("UPDATE pets SET private_profile = FALSE WHERE id IN (1, 1000)");
        Set<String> keys = cache.keys("wagwag:social:*");
        if (keys != null && !keys.isEmpty()) cache.delete(keys);
    }

    @Test
    void privatePostsRequireApprovedFollowAndRequestsCanBeReviewed() throws Exception {
        long postId = insertPost(1000, "Private photo-free post");
        jdbc.update("UPDATE pets SET private_profile = TRUE WHERE id = 1000");
        mvc.perform(get("/api/feed"))
            .andExpect(jsonPath("$.items.length()").value(0));
        mvc.perform(get("/api/posts/{id}", postId)).andExpect(status().isNotFound());
        mvc.perform(post("/api/posts/{id}/likes", postId)).andExpect(status().isNotFound());
        mvc.perform(get("/api/pets/discover"))
            .andExpect(jsonPath("$.items[0].privateProfile").value(true));

        mvc.perform(post("/api/pets/1000/follow"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.requestedByMe").value(true))
            .andExpect(jsonPath("$.followedByMe").value(false))
            .andExpect(jsonPath("$.followerCount").value(0));
        mvc.perform(get("/api/feed"))
            .andExpect(jsonPath("$.items.length()").value(0));
        mvc.perform(get("/api/pets/1000/followers")).andExpect(status().isNotFound());
        mvc.perform(get("/api/pets/1000/follow-requests")).andExpect(status().isForbidden());
        mvc.perform(delete("/api/pets/1000/follow"))
            .andExpect(jsonPath("$.requestedByMe").value(false));

        jdbc.update("UPDATE pets SET private_profile = FALSE WHERE id = 1000");
        mvc.perform(post("/api/pets/1000/follow"))
            .andExpect(jsonPath("$.followedByMe").value(true));
        jdbc.update("UPDATE pets SET private_profile = TRUE WHERE id = 1000");
        mvc.perform(get("/api/posts/{id}", postId)).andExpect(status().isOk());
        mvc.perform(get("/api/feed").param("following", "true"))
            .andExpect(jsonPath("$.items[0].id").value(postId));

        mvc.perform(put("/api/pets/1/privacy").contentType(MediaType.APPLICATION_JSON)
                .content("{\"privateProfile\":true}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.privateProfile").value(true));
        mvc.perform(put("/api/pets/1000/privacy").contentType(MediaType.APPLICATION_JSON)
                .content("{\"privateProfile\":true}"))
            .andExpect(status().isForbidden());
        mvc.perform(put("/api/pets/1/privacy").contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
            .andExpect(status().isBadRequest());
        jdbc.update("INSERT INTO pet_follows (follower_pet_id, following_pet_id, accepted) "
            + "VALUES (1000, 1, FALSE)");
        mvc.perform(get("/api/pets/1/follow-requests"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items[0].name").value("Biscuit"));
        mvc.perform(post("/api/pets/1/follow-requests/1000"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.followerCount").value(1));
        mvc.perform(get("/api/pets/1/followers"))
            .andExpect(jsonPath("$.items[0].id").value(1000));
    }

    @Test
    void muteHidesFeedAndBlockRemovesRelationshipsAndPreventsInteraction() throws Exception {
        long postId = insertPost(1000, "Neighbor post");
        mvc.perform(post("/api/pets/1000/follow")).andExpect(status().isOk());
        assertThat(feedIds(false)).contains(postId);
        assertThat(feedIds(true)).contains(postId);

        mvc.perform(post("/api/pets/1000/mute"))
            .andExpect(jsonPath("$.mutedByMe").value(true));
        assertThat(feedIds(false)).doesNotContain(postId);
        assertThat(feedIds(true)).doesNotContain(postId);
        mvc.perform(get("/api/posts/{id}", postId)).andExpect(status().isOk());
        mvc.perform(delete("/api/pets/1000/mute"))
            .andExpect(jsonPath("$.mutedByMe").value(false));
        assertThat(feedIds(false)).contains(postId);

        mvc.perform(post("/api/pets/1000/block"))
            .andExpect(jsonPath("$.blockedByMe").value(true))
            .andExpect(jsonPath("$.followedByMe").value(false));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM pet_follows", Long.class)).isZero();
        assertThat(feedIds(false)).doesNotContain(postId);
        mvc.perform(get("/api/posts/{id}", postId)).andExpect(status().isNotFound());
        mvc.perform(get("/api/pets/discover"))
            .andExpect(jsonPath("$.items.length()").value(0));
        mvc.perform(post("/api/pets/1000/follow")).andExpect(status().isForbidden());
        mvc.perform(delete("/api/pets/1000/block"))
            .andExpect(jsonPath("$.blockedByMe").value(false));
        assertThat(feedIds(false)).contains(postId);

        mvc.perform(post("/api/pets/1/block")).andExpect(status().isBadRequest());
        mvc.perform(post("/api/pets/1/mute")).andExpect(status().isBadRequest());
    }

    @Test
    void redisCachesCountsAndRelationshipsThenInvalidatesAfterFollowChanges() throws Exception {
        mvc.perform(get("/api/pets/1000/social"))
            .andExpect(jsonPath("$.followerCount").value(0));
        assertThat(cache.opsForValue().get("wagwag:social:followers:1000")).isEqualTo("0");

        mvc.perform(post("/api/pets/1000/follow"))
            .andExpect(jsonPath("$.followerCount").value(1));
        mvc.perform(get("/api/pets/1000/social"))
            .andExpect(jsonPath("$.followerCount").value(1))
            .andExpect(jsonPath("$.followedByMe").value(true));
        assertThat(cache.opsForValue().get("wagwag:social:followers:1000")).isEqualTo("1");
        assertThat(cache.opsForValue().get("wagwag:social:relation:1:1000")).isEqualTo("FOLLOWING");

        mvc.perform(delete("/api/pets/1000/follow"))
            .andExpect(jsonPath("$.followerCount").value(0));
        mvc.perform(get("/api/pets/1000/social"))
            .andExpect(jsonPath("$.followerCount").value(0))
            .andExpect(jsonPath("$.followedByMe").value(false));
    }

    @Test
    void declinedRequestsAndReverseBlocksDoNotExposePets() throws Exception {
        jdbc.update("UPDATE pets SET private_profile = TRUE WHERE id = 1");
        jdbc.update("INSERT INTO pet_follows (follower_pet_id, following_pet_id, accepted) "
            + "VALUES (1000, 1, FALSE)");
        mvc.perform(delete("/api/pets/1/follow-requests/1000"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.followerCount").value(0));
        mvc.perform(get("/api/pets/1/follow-requests"))
            .andExpect(jsonPath("$.items.length()").value(0));

        long postId = insertPost(1000, "Hidden by reverse block");
        jdbc.update("INSERT INTO pet_blocks (blocker_pet_id, blocked_pet_id) VALUES (1000, 1)");
        assertThat(feedIds(false)).doesNotContain(postId);
        mvc.perform(get("/api/pets/discover"))
            .andExpect(jsonPath("$.items.length()").value(0));
        mvc.perform(post("/api/pets/1000/follow")).andExpect(status().isForbidden());
        mvc.perform(get("/api/posts/{id}", postId)).andExpect(status().isNotFound());
    }

    private long insertPost(long petId, String body) {
        return jdbc.queryForObject("INSERT INTO posts (pet_id, body) VALUES (?, ?) RETURNING id",
            Long.class, petId, body);
    }

    private List<Long> feedIds(boolean following) throws Exception {
        String page = mvc.perform(get("/api/feed").param("following", Boolean.toString(following)))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        List<Number> ids = JsonPath.read(page, "$.items[*].id");
        return ids.stream().map(Number::longValue).toList();
    }
}
