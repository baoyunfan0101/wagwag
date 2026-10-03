package com.wagwag.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import org.springframework.dao.DataIntegrityViolationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.data.redis.core.StringRedisTemplate;
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
class CommunityApiIntegrationTest {
    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
        DockerImageName.parse("postgis/postgis:17-3.5").asCompatibleSubstituteFor("postgres"));

    @Container
    static final GenericContainer<?> redis = new GenericContainer<>("redis:7.4-alpine")
        .withExposedPorts(6379);

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("app.follow-cache.enabled", () -> false);
        registry.add("spring.data.redis.url", () -> "redis://" + redis.getHost() + ":" + redis.getMappedPort(6379));
    }

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired StringRedisTemplate cache;

    @BeforeEach
    void clean() {
        cache.delete("wagwag:community:hot-ids");
        jdbc.update("DELETE FROM pet_blocks");
        jdbc.update("DELETE FROM pet_mutes");
        jdbc.update("DELETE FROM pet_follows");
        jdbc.update("DELETE FROM posts");
        jdbc.update("DELETE FROM community_members");
        jdbc.update("DELETE FROM communities");
        jdbc.update("DELETE FROM pets WHERE id NOT IN (1, 1000)");
        jdbc.update("UPDATE pets SET private_profile = FALSE WHERE id = 1000");
    }

    @Test
    void createDiscoverJoinLeaveAndMembers() throws Exception {
        long id = createCommunity("Houston Dog Parks");
        mvc.perform(get("/api/communities/{id}", id))
            .andExpect(jsonPath("$.joinedByMe").value(true))
            .andExpect(jsonPath("$.memberCount").value(1))
            .andExpect(jsonPath("$.myRole").value("OWNER"));
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
            .andExpect(jsonPath("$.items[0].name").value("Mochi"))
            .andExpect(jsonPath("$.items[0].role").value("OWNER"));
        jdbc.update("INSERT INTO community_members (community_id, pet_id) VALUES (?, 1000)", id);
        mvc.perform(get("/api/communities/{id}/members", id).param("limit", "1"))
            .andExpect(jsonPath("$.items[0].name").value("Mochi"))
            .andExpect(jsonPath("$.nextPage").value(1));
        mvc.perform(get("/api/communities/{id}/members", id).param("limit", "1").param("page", "1"))
            .andExpect(jsonPath("$.items[0].name").value("Biscuit"))
            .andExpect(jsonPath("$.nextPage").value((Object) null));

        mvc.perform(delete("/api/communities/{id}/members", id)).andExpect(status().isConflict());
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

        long other = jdbc.queryForObject("INSERT INTO communities (name, created_by_pet_id) "
            + "VALUES ('Owned by Biscuit', 1000) RETURNING id", Long.class);
        jdbc.update("INSERT INTO community_members (community_id, pet_id, role) "
            + "VALUES (?, 1000, 'OWNER')", other);
        mvc.perform(post("/api/communities/{id}/members", other))
            .andExpect(jsonPath("$.myRole").value("MEMBER"));
        mvc.perform(delete("/api/communities/{id}/members", other))
            .andExpect(jsonPath("$.joinedByMe").value(false));
        mvc.perform(delete("/api/communities/{id}/members", other)).andExpect(status().isOk());
    }

    @Test
    void rulesRolesAndManagerPermissions() throws Exception {
        long id = createCommunity("Trail Friends");
        jdbc.update("INSERT INTO community_members (community_id, pet_id) VALUES (?, 1000)", id);
        mvc.perform(put("/api/communities/{id}", id).contentType(MediaType.APPLICATION_JSON)
                .content("{\"description\":\"  Local hiking routes  \",\"rules\":\"  Stay on trails  \"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.rules").value("Stay on trails"))
            .andExpect(jsonPath("$.description").value("Local hiking routes"));
        mvc.perform(put("/api/communities/{id}/members/1000/role", id)
                .contentType(MediaType.APPLICATION_JSON).content("{\"role\":\"MODERATOR\"}"))
            .andExpect(status().isNoContent());
        mvc.perform(get("/api/communities/{id}/members", id))
            .andExpect(jsonPath("$.items[1].role").value("MODERATOR"));
        mvc.perform(put("/api/communities/{id}/members/1/role", id)
                .contentType(MediaType.APPLICATION_JSON).content("{\"role\":\"MEMBER\"}"))
            .andExpect(status().isConflict());
        mvc.perform(put("/api/communities/{id}/members/1000/role", id)
                .contentType(MediaType.APPLICATION_JSON).content("{\"role\":\"OWNER\"}"))
            .andExpect(status().isBadRequest());
        long postId = createCommunityPost(id, "Moderated post");
        mvc.perform(delete("/api/communities/{id}/posts/{postId}", id, postId))
            .andExpect(status().isNoContent());
        mvc.perform(get("/api/communities/{id}/feed", id))
            .andExpect(jsonPath("$.items.length()").value(0));
        mvc.perform(get("/api/posts/{id}", postId)).andExpect(status().isOk());
        mvc.perform(delete("/api/communities/{id}/posts/{postId}", id, postId))
            .andExpect(status().isNotFound());
        mvc.perform(delete("/api/communities/{id}/members/1000", id)).andExpect(status().isNoContent());
        mvc.perform(delete("/api/communities/{id}/members/1000", id)).andExpect(status().isNoContent());
        mvc.perform(get("/api/communities/{id}", id)).andExpect(jsonPath("$.memberCount").value(1));
        assertThatThrownBy(() -> jdbc.update("UPDATE community_members SET role = 'ADMIN' "
            + "WHERE community_id = ? AND pet_id = 1", id))
            .isInstanceOf(DataIntegrityViolationException.class);

        jdbc.update("UPDATE community_members SET role = 'MEMBER' WHERE community_id = ? AND pet_id = 1", id);
        mvc.perform(put("/api/communities/{id}", id).contentType(MediaType.APPLICATION_JSON)
                .content("{\"rules\":\"No rules\"}"))
            .andExpect(status().isForbidden());
        mvc.perform(put("/api/communities/{id}/members/1000/role", id)
                .contentType(MediaType.APPLICATION_JSON).content("{\"role\":\"MODERATOR\"}"))
            .andExpect(status().isForbidden());

        long moderated = jdbc.queryForObject("INSERT INTO communities (name, created_by_pet_id) "
            + "VALUES ('Biscuit Club', 1000) RETURNING id", Long.class);
        jdbc.update("INSERT INTO community_members (community_id, pet_id, role) "
            + "VALUES (?, 1000, 'OWNER'), (?, 1, 'MODERATOR')", moderated, moderated);
        long thirdPet = jdbc.queryForObject("INSERT INTO pets (owner_id, name, species, gender) "
            + "VALUES (1, 'Peanut', 'Dog', 'UNKNOWN') RETURNING id", Long.class);
        jdbc.update("INSERT INTO community_members (community_id, pet_id) VALUES (?, ?)", moderated, thirdPet);
        mvc.perform(put("/api/communities/{id}", moderated).contentType(MediaType.APPLICATION_JSON)
                .content("{\"rules\":\"Be friendly\"}"))
            .andExpect(jsonPath("$.rules").value("Be friendly"));
        mvc.perform(put("/api/communities/{id}/members/{petId}/role", moderated, thirdPet)
                .contentType(MediaType.APPLICATION_JSON).content("{\"role\":\"MODERATOR\"}"))
            .andExpect(status().isForbidden());
        mvc.perform(delete("/api/communities/{id}/members/1000", moderated))
            .andExpect(status().isForbidden());
        mvc.perform(delete("/api/communities/{id}/members/{petId}", moderated, thirdPet))
            .andExpect(status().isNoContent());
    }

    @Test
    void fullTextSearchAndHotSort() throws Exception {
        long trails = createCommunity("Trail Friends");
        long cats = createCommunity("Senior Cats");
        mvc.perform(put("/api/communities/{id}", trails).contentType(MediaType.APPLICATION_JSON)
                .content("{\"description\":\"Group hiking routes\",\"rules\":\"Respect nature\"}"))
            .andExpect(status().isOk());
        mvc.perform(get("/api/communities").param("query", "hiking"))
            .andExpect(jsonPath("$.items[0].id").value(trails));
        mvc.perform(get("/api/communities").param("query", "nature"))
            .andExpect(jsonPath("$.items[0].id").value(trails));
        mvc.perform(get("/api/communities").param("query", "unrelated"))
            .andExpect(jsonPath("$.items.length()").value(0));
        mvc.perform(get("/api/communities").param("sort", "recent"))
            .andExpect(jsonPath("$.items[0].id").value(cats));
        mvc.perform(get("/api/communities").param("sort", "hot"))
            .andExpect(jsonPath("$.items[0].id").value(cats));
        mvc.perform(get("/api/communities").param("sort", "hot").param("limit", "1"))
            .andExpect(jsonPath("$.items[0].id").value(cats))
            .andExpect(jsonPath("$.nextPage").value(1));
        mvc.perform(get("/api/communities").param("sort", "hot")
                .param("limit", "1").param("page", "1"))
            .andExpect(jsonPath("$.items[0].id").value(trails));
        assertThat(cache.hasKey("wagwag:community:hot-ids")).isTrue();
        createCommunityPost(trails, "Today's trail meetup");
        assertThat(cache.hasKey("wagwag:community:hot-ids")).isFalse();
        mvc.perform(get("/api/communities").param("sort", "hot"))
            .andExpect(jsonPath("$.items[0].id").value(trails));
        mvc.perform(get("/api/communities").param("sort", "unknown"))
            .andExpect(status().isBadRequest());
        mvc.perform(get("/api/communities").param("query", "x".repeat(101)))
            .andExpect(status().isBadRequest());
    }

    @Test
    void communityPostsRequireMembershipAndKeepFeedPagination() throws Exception {
        long id = jdbc.queryForObject("INSERT INTO communities (name, created_by_pet_id) "
            + "VALUES ('Corgi Club', 1000) RETURNING id", Long.class);
        jdbc.update("INSERT INTO community_members (community_id, pet_id, role) "
            + "VALUES (?, 1000, 'OWNER')", id);
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
        long id = createOtherPetCommunity("Neighborhood Pets");
        jdbc.update("INSERT INTO community_members (community_id, pet_id) VALUES (?, 1)", id);
        long postId = jdbc.queryForObject("INSERT INTO posts (pet_id, body) VALUES (1000, 'Biscuit') "
            + "RETURNING id", Long.class);
        jdbc.update("INSERT INTO post_communities (post_id, community_id) VALUES (?, ?)", postId, id);
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

    @Test
    void ownerCanModerateReverseBlockedMemberAndPost() throws Exception {
        long id = createCommunity("Open Trails");
        jdbc.update("INSERT INTO community_members (community_id, pet_id) VALUES (?, 1000)", id);
        long first = attachPost(id, 1000, "First member post");
        long second = attachPost(id, 1000, "Second member post");
        long third = attachPost(id, 1000, "Third member post");
        jdbc.update("UPDATE posts SET created_at = TIMESTAMPTZ '2026-01-01 00:00:00+00' "
            + "WHERE id IN (?, ?, ?)", first, second, third);
        jdbc.update("INSERT INTO pet_blocks (blocker_pet_id, blocked_pet_id) VALUES (1000, 1)");

        String firstPage = mvc.perform(get("/api/communities/{id}/feed", id).param("limit", "2"))
            .andExpect(jsonPath("$.items[0].id").value(third))
            .andExpect(jsonPath("$.items[1].id").value(second))
            .andReturn().getResponse().getContentAsString();
        String cursor = JsonPath.read(firstPage, "$.nextCursor");
        assertThat(cursor).isNotBlank();
        mvc.perform(get("/api/communities/{id}/feed", id).param("limit", "2").param("cursor", cursor))
            .andExpect(jsonPath("$.items[0].id").value(first))
            .andExpect(jsonPath("$.nextCursor").value((Object) null));
        mvc.perform(get("/api/communities/{id}/members", id))
            .andExpect(jsonPath("$.items.length()").value(2))
            .andExpect(jsonPath("$.items[1].id").value(1000));
        mvc.perform(get("/api/feed")).andExpect(jsonPath("$.items.length()").value(0));
        mvc.perform(get("/api/posts/{id}", third)).andExpect(status().isNotFound());
        jdbc.update("INSERT INTO pet_blocks (blocker_pet_id, blocked_pet_id) VALUES (1, 1000)");
        mvc.perform(get("/api/communities/{id}/feed", id))
            .andExpect(jsonPath("$.items.length()").value(3));
        mvc.perform(get("/api/communities/{id}/members", id))
            .andExpect(jsonPath("$.items.length()").value(2));

        mvc.perform(delete("/api/communities/{id}/posts/{postId}", id, third))
            .andExpect(status().isNoContent());
        mvc.perform(delete("/api/communities/{id}/members/1000", id))
            .andExpect(status().isNoContent());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM post_communities WHERE post_id = ?",
            Long.class, third)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM community_members "
            + "WHERE community_id = ? AND pet_id = 1000", Long.class, id)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM posts WHERE id = ?", Long.class, third))
            .isEqualTo(1);
    }

    @Test
    void moderatorCanSeeAndRemoveBlockedMemberAndPostOnlyInOwnCommunity() throws Exception {
        long managed = createOtherPetCommunity("Managed Club");
        jdbc.update("INSERT INTO community_members (community_id, pet_id, role) "
            + "VALUES (?, 1, 'MODERATOR')", managed);
        long thirdPet = createThirdPet();
        jdbc.update("INSERT INTO community_members (community_id, pet_id) VALUES (?, ?)", managed, thirdPet);
        long moderatedPost = attachPost(managed, thirdPet, "Moderated story");

        long ordinary = createOtherPetCommunity("Ordinary Club");
        jdbc.update("INSERT INTO community_members (community_id, pet_id) VALUES (?, 1), (?, ?)",
            ordinary, ordinary, thirdPet);
        long ordinaryPost = attachPost(ordinary, thirdPet, "Ordinary story");
        jdbc.update("INSERT INTO pet_blocks (blocker_pet_id, blocked_pet_id) VALUES (?, 1)", thirdPet);

        mvc.perform(get("/api/communities/{id}/feed", managed))
            .andExpect(jsonPath("$.items[0].id").value(moderatedPost))
            .andExpect(jsonPath("$.items.length()").value(1));
        mvc.perform(get("/api/communities/{id}/members", managed))
            .andExpect(jsonPath("$.items.length()").value(3));
        mvc.perform(get("/api/communities/{id}/feed", ordinary))
            .andExpect(jsonPath("$.items.length()").value(0));
        mvc.perform(get("/api/communities/{id}/members", ordinary))
            .andExpect(jsonPath("$.items.length()").value(2));
        mvc.perform(get("/api/posts/{id}", moderatedPost)).andExpect(status().isNotFound());
        mvc.perform(get("/api/feed")).andExpect(jsonPath("$.items.length()").value(0));

        mvc.perform(delete("/api/communities/{id}/posts/{postId}", managed, moderatedPost))
            .andExpect(status().isNoContent());
        mvc.perform(delete("/api/communities/{id}/members/{petId}", managed, thirdPet))
            .andExpect(status().isNoContent());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM post_communities WHERE post_id = ?",
            Long.class, moderatedPost)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM community_members "
            + "WHERE community_id = ? AND pet_id = ?", Long.class, managed, thirdPet)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM posts WHERE id = ?", Long.class, moderatedPost))
            .isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM post_communities WHERE post_id = ?",
            Long.class, ordinaryPost)).isEqualTo(1);
    }

    @Test
    void ownerCanModeratePrivateAndMutedAuthorWithoutSocialVisibility() throws Exception {
        long id = createCommunity("Quiet Pets");
        jdbc.update("INSERT INTO community_members (community_id, pet_id) VALUES (?, 1000)", id);
        long postId = attachPost(id, 1000, "Private community story");
        jdbc.update("UPDATE pets SET private_profile = TRUE WHERE id = 1000");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM pet_follows WHERE follower_pet_id = 1 "
            + "AND following_pet_id = 1000", Long.class)).isZero();
        mvc.perform(get("/api/communities/{id}/feed", id))
            .andExpect(jsonPath("$.items[0].id").value(postId));
        mvc.perform(get("/api/feed")).andExpect(jsonPath("$.items.length()").value(0));
        mvc.perform(get("/api/posts/{id}", postId)).andExpect(status().isNotFound());

        jdbc.update("UPDATE pets SET private_profile = FALSE WHERE id = 1000");
        jdbc.update("INSERT INTO pet_mutes (muter_pet_id, muted_pet_id) VALUES (1, 1000)");
        mvc.perform(get("/api/feed")).andExpect(jsonPath("$.items.length()").value(0));
        mvc.perform(get("/api/communities/{id}/feed", id))
            .andExpect(jsonPath("$.items[0].id").value(postId));
    }

    @Test
    void ordinaryMemberStillRespectsReverseBlock() throws Exception {
        long id = createOtherPetCommunity("Everyday Club");
        jdbc.update("INSERT INTO community_members (community_id, pet_id) VALUES (?, 1)", id);
        long thirdPet = createThirdPet();
        jdbc.update("INSERT INTO community_members (community_id, pet_id) VALUES (?, ?)", id, thirdPet);
        attachPost(id, thirdPet, "Hidden story");
        jdbc.update("INSERT INTO pet_mutes (muter_pet_id, muted_pet_id) VALUES (1, ?)", thirdPet);
        mvc.perform(get("/api/communities/{id}/feed", id))
            .andExpect(jsonPath("$.items.length()").value(0));
        mvc.perform(get("/api/communities/{id}/members", id))
            .andExpect(jsonPath("$.items.length()").value(3));
        jdbc.update("DELETE FROM pet_mutes WHERE muter_pet_id = 1 AND muted_pet_id = ?", thirdPet);
        jdbc.update("INSERT INTO pet_blocks (blocker_pet_id, blocked_pet_id) VALUES (?, 1)", thirdPet);
        mvc.perform(get("/api/communities/{id}/feed", id))
            .andExpect(jsonPath("$.items.length()").value(0));
        mvc.perform(get("/api/communities/{id}/members", id))
            .andExpect(jsonPath("$.items.length()").value(2));
    }

    private long createOtherPetCommunity(String name) {
        long id = jdbc.queryForObject("INSERT INTO communities (name, created_by_pet_id) "
            + "VALUES (?, 1000) RETURNING id", Long.class, name);
        jdbc.update("INSERT INTO community_members (community_id, pet_id, role) "
            + "VALUES (?, 1000, 'OWNER')", id);
        return id;
    }

    private long createThirdPet() {
        return jdbc.queryForObject("INSERT INTO pets (owner_id, name, species, gender) "
            + "VALUES (1, 'Peanut', 'Dog', 'UNKNOWN') RETURNING id", Long.class);
    }

    private long attachPost(long communityId, long petId, String body) {
        long postId = jdbc.queryForObject("INSERT INTO posts (pet_id, body) VALUES (?, ?) RETURNING id",
            Long.class, petId, body);
        jdbc.update("INSERT INTO post_communities (post_id, community_id) VALUES (?, ?)", postId, communityId);
        return postId;
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
