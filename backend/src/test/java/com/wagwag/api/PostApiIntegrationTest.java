package com.wagwag.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
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
            .andExpect(jsonPath("$[0].id").value(imageId))
            .andExpect(jsonPath("$[1].id").value(textId));

        assertThat(jdbc.queryForObject("SELECT body FROM posts WHERE id = ?", String.class, textId))
            .isEqualTo("A new walk today");
        assertThat(jdbc.queryForObject("SELECT url FROM post_media WHERE post_id = ?", String.class, imageId))
            .isEqualTo(imageUrl);
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
}
