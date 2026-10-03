package com.wagwag.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
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
class CommunityApiIntegrationTest {
    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine");

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
        jdbc.update("DELETE FROM pet_follows");
        jdbc.update("DELETE FROM posts");
        jdbc.update("DELETE FROM community_members");
        jdbc.update("DELETE FROM communities");
        jdbc.update("UPDATE pets SET private_profile = FALSE WHERE id = 1000");
    }

    @Test
    void createDiscoverJoinLeaveAndMembers() throws Exception {
        long id = createCommunity("Houston Dog Parks");
        mvc.perform(get("/api/communities/{id}", id))
            .andExpect(jsonPath("$.joinedByMe").value(true))
            .andExpect(jsonPath("$.memberCount").value(1));
        mvc.perform(post("/api/communities").contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"houston dog parks\"}"))
            .andExpect(status().isConflict());
        mvc.perform(get("/api/communities").param("query", "Dog Park"))
            .andExpect(jsonPath("$.items[0].id").value(id));
        mvc.perform(get("/api/communities").param("query", "Cats"))
            .andExpect(jsonPath("$.items.length()").value(0));
        createCommunity("Puppy Training");
        mvc.perform(get("/api/communities").param("limit", "1"))
            .andExpect(jsonPath("$.items[0].name").value("Puppy Training"))
            .andExpect(jsonPath("$.nextPage").value(1));
        mvc.perform(get("/api/communities").param("limit", "1").param("page", "1"))
            .andExpect(jsonPath("$.items[0].id").value(id))
            .andExpect(jsonPath("$.nextPage").value((Object) null));
        mvc.perform(get("/api/communities/{id}/members", id))
            .andExpect(jsonPath("$.items[0].name").value("Mochi"));
        jdbc.update("INSERT INTO community_members (community_id, pet_id) VALUES (?, 1000)", id);
        mvc.perform(get("/api/communities/{id}/members", id).param("limit", "1"))
            .andExpect(jsonPath("$.items[0].name").value("Mochi"))
            .andExpect(jsonPath("$.nextPage").value(1));
        mvc.perform(get("/api/communities/{id}/members", id).param("limit", "1").param("page", "1"))
            .andExpect(jsonPath("$.items[0].name").value("Biscuit"))
            .andExpect(jsonPath("$.nextPage").value((Object) null));

        mvc.perform(delete("/api/communities/{id}/members", id))
            .andExpect(jsonPath("$.memberCount").value(1))
            .andExpect(jsonPath("$.joinedByMe").value(false));
        mvc.perform(delete("/api/communities/{id}/members", id)).andExpect(status().isOk());
        mvc.perform(post("/api/communities/{id}/members", id))
            .andExpect(jsonPath("$.memberCount").value(2));
        mvc.perform(post("/api/communities/{id}/members", id))
            .andExpect(jsonPath("$.memberCount").value(2));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM community_members WHERE community_id = ?",
            Long.class, id)).isEqualTo(2);
        mvc.perform(post("/api/communities").contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"   \"}"))
            .andExpect(status().isBadRequest());
        mvc.perform(get("/api/communities").param("limit", "0")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/communities/999999")).andExpect(status().isNotFound());
    }

    @Test
    void communityPostsRequireMembershipAndKeepFeedPagination() throws Exception {
        long id = createCommunity("Corgi Club");
        mvc.perform(delete("/api/communities/{id}/members", id)).andExpect(status().isOk());
        mvc.perform(post("/api/posts").contentType(MediaType.APPLICATION_JSON)
                .content("{\"body\":\"not a member\",\"communityId\":" + id + "}"))
            .andExpect(status().isForbidden());
        mvc.perform(post("/api/communities/{id}/members", id)).andExpect(status().isOk());

        long first = createCommunityPost(id, "First");
        long second = createCommunityPost(id, "Second");
        long third = createCommunityPost(id, "Third");
        jdbc.update("UPDATE posts SET created_at = TIMESTAMPTZ '2026-01-01 00:00:00+00' "
            + "WHERE id IN (?, ?, ?)", first, second, third);
        long outside = jdbc.queryForObject("INSERT INTO posts (pet_id, body) VALUES (1, 'Outside') "
            + "RETURNING id", Long.class);

        String page1 = mvc.perform(get("/api/communities/{id}/feed", id).param("limit", "2"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items[0].id").value(third))
            .andExpect(jsonPath("$.items[1].id").value(second))
            .andExpect(jsonPath("$.items[0].communityName").value("Corgi Club"))
            .andReturn().getResponse().getContentAsString();
        String cursor = JsonPath.read(page1, "$.nextCursor");
        assertThat(cursor).isNotBlank();
        mvc.perform(get("/api/communities/{id}/feed", id).param("limit", "2")
                .param("cursor", cursor))
            .andExpect(jsonPath("$.items[0].id").value(first))
            .andExpect(jsonPath("$.items.length()").value(1))
            .andExpect(jsonPath("$.nextCursor").value((Object) null));
        mvc.perform(get("/api/feed"))
            .andExpect(jsonPath("$.items[0].id").value(outside))
            .andExpect(jsonPath("$.items[1].communityId").value(id));
        mvc.perform(get("/api/posts/{id}", first))
            .andExpect(jsonPath("$.communityId").value(id));
        mvc.perform(get("/api/communities/{id}/feed", id).param("cursor", "bad"))
            .andExpect(status().isBadRequest());
    }

    @Test
    void communityFeedRespectsPrivateProfilesAndBlocks() throws Exception {
        long id = createCommunity("Neighborhood Pets");
        long postId = jdbc.queryForObject("INSERT INTO posts (pet_id, body) VALUES (1000, 'Biscuit') "
            + "RETURNING id", Long.class);
        jdbc.update("INSERT INTO post_communities (post_id, community_id) VALUES (?, ?)", postId, id);
        jdbc.update("INSERT INTO community_members (community_id, pet_id) VALUES (?, 1000)", id);
        jdbc.update("UPDATE pets SET private_profile = TRUE WHERE id = 1000");
        mvc.perform(get("/api/communities/{id}/feed", id))
            .andExpect(jsonPath("$.items.length()").value(0));
        jdbc.update("INSERT INTO pet_follows (follower_pet_id, following_pet_id) VALUES (1, 1000)");
        mvc.perform(get("/api/communities/{id}/feed", id))
            .andExpect(jsonPath("$.items[0].id").value(postId));
        jdbc.update("INSERT INTO pet_blocks (blocker_pet_id, blocked_pet_id) VALUES (1, 1000)");
        mvc.perform(get("/api/communities/{id}/feed", id))
            .andExpect(jsonPath("$.items.length()").value(0));
        mvc.perform(get("/api/communities/{id}/members", id))
            .andExpect(jsonPath("$.items.length()").value(1));
    }

    private long createCommunity(String name) throws Exception {
        String response = mvc.perform(post("/api/communities").contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"" + name + "\",\"description\":\"A place for pets\"}"))
            .andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(response, "$.id")).longValue();
    }

    private long createCommunityPost(long communityId, String body) throws Exception {
        String response = mvc.perform(post("/api/posts").contentType(MediaType.APPLICATION_JSON)
                .content("{\"body\":\"" + body + "\",\"communityId\":" + communityId + "}"))
            .andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(response, "$.id")).longValue();
    }
}
