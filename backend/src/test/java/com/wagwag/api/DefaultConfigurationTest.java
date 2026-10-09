package com.wagwag.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest
@Testcontainers
class DefaultConfigurationTest {
    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
        DockerImageName.parse("postgis/postgis:17-3.5").asCompatibleSubstituteFor("postgres"));

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired JdbcTemplate jdbc;

    @Test
    void defaultConfigurationCreatesCurrentSchemaWithoutDevelopmentData() {
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM users", Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM pets", Long.class)).isZero();
        for (String table : new String[] {"posts", "post_media", "video_uploads", "likes", "comments",
                "pet_follows", "pet_blocks", "pet_mutes", "communities",
                "community_members", "post_communities", "tasks", "task_assignments",
                "task_events", "task_ratings", "task_availability", "conversations",
                "conversation_members", "messages", "notifications", "push_devices", "push_deliveries",
                "listings", "listing_media", "listing_favorites", "listing_orders", "listing_ratings"}) {
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Long.class)).isZero();
        }

        jdbc.update("INSERT INTO users (id, display_name) VALUES (1, 'Test user')");
        jdbc.update("INSERT INTO pets (id, owner_id, name, species, gender) "
            + "VALUES (1, 1, 'One', 'Dog', 'UNKNOWN'), (2, 1, 'Two', 'Cat', 'UNKNOWN')");
        var uploadId = java.util.UUID.randomUUID();
        jdbc.update("INSERT INTO video_uploads (id, pet_id, source_key, content_type) VALUES (?, 1, 'source.mp4', 'video/mp4')", uploadId);
        assertThatThrownBy(() -> jdbc.update("INSERT INTO video_uploads (id, pet_id, source_key, content_type) "
            + "VALUES (?, 1, 'source.mp4', 'video/mp4')", java.util.UUID.randomUUID()))
            .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("UPDATE video_uploads SET status = 'READY' WHERE id = ?", uploadId))
            .isInstanceOf(DataIntegrityViolationException.class);

        jdbc.update("INSERT INTO posts (id, pet_id, body) VALUES (1, 1, 'Test post')");
        jdbc.update("INSERT INTO post_media (post_id, url, sort_order) VALUES (1, 'https://example.test/a', 0)");
        assertThatThrownBy(() -> jdbc.update("INSERT INTO post_media (post_id, url, sort_order) "
            + "VALUES (1, 'https://example.test/b', 0)"))
            .isInstanceOf(DataIntegrityViolationException.class);

        jdbc.update("INSERT INTO likes (post_id, pet_id) VALUES (1, 2)");
        assertThatThrownBy(() -> jdbc.update("INSERT INTO likes (post_id, pet_id) VALUES (1, 2)"))
            .isInstanceOf(DataIntegrityViolationException.class);

        jdbc.update("INSERT INTO pet_follows (follower_pet_id, following_pet_id) VALUES (1, 2)");
        assertThatThrownBy(() -> jdbc.update("INSERT INTO pet_follows (follower_pet_id, following_pet_id) "
            + "VALUES (1, 2)"))
            .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("INSERT INTO pet_follows (follower_pet_id, following_pet_id) "
            + "VALUES (1, 1)"))
            .isInstanceOf(DataIntegrityViolationException.class);

        jdbc.update("INSERT INTO communities (id, name, created_by_pet_id) VALUES (1, 'Dog Parks', 1)");
        assertThatThrownBy(() -> jdbc.update("INSERT INTO communities (name, created_by_pet_id) "
            + "VALUES ('dog parks', 1)"))
            .isInstanceOf(DataIntegrityViolationException.class);
        jdbc.update("INSERT INTO community_members (community_id, pet_id) VALUES (1, 1)");
        assertThatThrownBy(() -> jdbc.update("INSERT INTO community_members (community_id, pet_id) "
            + "VALUES (1, 1)"))
            .isInstanceOf(DataIntegrityViolationException.class);
        jdbc.update("INSERT INTO post_communities (post_id, community_id) VALUES (1, 1)");
        assertThatThrownBy(() -> jdbc.update("INSERT INTO post_communities (post_id, community_id) "
            + "VALUES (1, 1)"))
            .isInstanceOf(DataIntegrityViolationException.class);

        jdbc.update("INSERT INTO tasks (id, creator_pet_id, title, description, category, latitude, longitude) "
            + "VALUES (1, 1, 'Walk', 'Walk the dog', 'DOG_WALKING', 29.76, -95.37)");
        jdbc.update("INSERT INTO task_assignments (task_id, pet_id) VALUES (1, 2)");
        assertThatThrownBy(() -> jdbc.update("INSERT INTO task_assignments (task_id, pet_id) VALUES (1, 1)"))
            .isInstanceOf(DataIntegrityViolationException.class);

        jdbc.update("INSERT INTO listings (id, seller_pet_id, title, description, price_cents) "
            + "VALUES (1, 1, 'Leash', 'Clean pet gear', 1200)");
        assertThatThrownBy(() -> jdbc.update("INSERT INTO listings (seller_pet_id, title, description, price_cents) "
            + "VALUES (1, 'Invalid', 'Negative price', -1)"))
            .isInstanceOf(DataIntegrityViolationException.class);
        jdbc.update("INSERT INTO listing_media (listing_id, url, sort_order) "
            + "VALUES (1, 'https://example.test/a', 0)");
        assertThatThrownBy(() -> jdbc.update("INSERT INTO listing_media (listing_id, url, sort_order) "
            + "VALUES (1, 'https://example.test/b', 0)"))
            .isInstanceOf(DataIntegrityViolationException.class);
        jdbc.update("INSERT INTO listing_favorites (listing_id, pet_id) VALUES (1, 2)");
        assertThatThrownBy(() -> jdbc.update("INSERT INTO listing_favorites (listing_id, pet_id) VALUES (1, 2)"))
            .isInstanceOf(DataIntegrityViolationException.class);
    }
}
