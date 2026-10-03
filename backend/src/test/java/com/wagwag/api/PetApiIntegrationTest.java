package com.wagwag.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Base64;
import java.util.UUID;
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
class PetApiIntegrationTest {
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
        registry.add("app.storage.endpoint", PetApiIntegrationTest::storageUrl);
        registry.add("app.storage.public-base-url", () -> storageUrl() + "/wagwag-avatars");
        registry.add("app.storage.access-key", () -> "testaccess");
        registry.add("app.storage.secret-key", () -> "testsecret");
    }

    static String storageUrl() {
        return "http://" + storage.getHost() + ":" + storage.getMappedPort(8333);
    }

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;

    @Test
    void profileChangesPersistAndOwnershipIsEnforced() throws Exception {
        mvc.perform(get("/api/pets/1"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.name").value("Mochi"));

        String body = """
            {"name":"Mochi Two","species":"Dog","breed":"Shiba Inu",
             "gender":"FEMALE","birthday":"2022-05-14","bio":"Updated in PostgreSQL"}
            """;
        mvc.perform(put("/api/pets/1").contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.name").value("Mochi Two"));
        assertThat(jdbc.queryForObject("SELECT bio FROM pets WHERE id = 1", String.class))
            .isEqualTo("Updated in PostgreSQL");

        mvc.perform(get("/api/pets/1"))
            .andExpect(jsonPath("$.name").value("Mochi Two"));
        mvc.perform(put("/api/pets/1").contentType(MediaType.APPLICATION_JSON)
            .content("{\"name\":\"\",\"species\":\"Dog\",\"gender\":\"MALE\"}"))
            .andExpect(status().isBadRequest());

        jdbc.update("INSERT INTO users (id, display_name) VALUES (50, 'Other')");
        jdbc.update("INSERT INTO pets (id, owner_id, name, species, gender) VALUES (50, 50, 'Other pet', 'Cat', 'UNKNOWN')");
        mvc.perform(put("/api/pets/50").contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isForbidden());
        mvc.perform(post("/api/pets/50/avatar-uploads").contentType(MediaType.APPLICATION_JSON)
            .content("{\"contentType\":\"image/png\"}"))
            .andExpect(status().isForbidden());
    }

    @Test
    void avatarUploadIsStoredAndPubliclyServed() throws Exception {
        String ticket = mvc.perform(post("/api/pets/1/avatar-uploads")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"contentType\":\"image/png\"}"))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
        String key = JsonPath.read(ticket, "$.key");
        String uploadUrl = JsonPath.read(ticket, "$.uploadUrl");
        String publicUrl = JsonPath.read(ticket, "$.publicUrl");
        byte[] png = Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+/TfQAAAAASUVORK5CYII=");
        try (HttpClient client = HttpClient.newHttpClient()) {
            var uploaded = client.send(HttpRequest.newBuilder(URI.create(uploadUrl))
                .header("Content-Type", "image/png")
                .PUT(HttpRequest.BodyPublishers.ofByteArray(png)).build(),
                HttpResponse.BodyHandlers.discarding());
            assertThat(uploaded.statusCode()).isEqualTo(200);
            mvc.perform(put("/api/pets/1/avatar").contentType(MediaType.APPLICATION_JSON)
                .content("{\"key\":\"" + key + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.avatarUrl").value(publicUrl));
            assertThat(jdbc.queryForObject("SELECT avatar_url FROM pets WHERE id = 1", String.class))
                .isEqualTo(publicUrl);
            var served = client.send(HttpRequest.newBuilder(URI.create(publicUrl)).GET().build(),
                HttpResponse.BodyHandlers.ofByteArray());
            assertThat(served.statusCode()).isEqualTo(200);
            assertThat(served.body()).isEqualTo(png);
        }
    }

    @Test
    void unsupportedAvatarContentTypeIsRejected() throws Exception {
        mvc.perform(post("/api/pets/1/avatar-uploads")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"contentType\":\"application/pdf\"}"))
            .andExpect(status().isBadRequest());
    }

    @Test
    void invalidAndWrongPetAvatarKeysAreRejected() throws Exception {
        for (String key : new String[] {"not-an-avatar-key", "pets/2/" + UUID.randomUUID() + ".png"}) {
            mvc.perform(put("/api/pets/1/avatar")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"key\":\"" + key + "\"}"))
                .andExpect(status().isBadRequest());
        }
    }

    @Test
    void oversizedUploadedAvatarIsRejectedByObjectMetadata() throws Exception {
        String ticket = mvc.perform(post("/api/pets/1/avatar-uploads")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"contentType\":\"image/png\"}"))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
        String key = JsonPath.read(ticket, "$.key");
        String uploadUrl = JsonPath.read(ticket, "$.uploadUrl");
        String previousAvatarUrl = jdbc.queryForObject("SELECT avatar_url FROM pets WHERE id = 1", String.class);

        try (HttpClient client = HttpClient.newHttpClient()) {
            var uploaded = client.send(HttpRequest.newBuilder(URI.create(uploadUrl))
                .header("Content-Type", "image/png")
                .PUT(HttpRequest.BodyPublishers.ofByteArray(new byte[5 * 1024 * 1024 + 1])).build(),
                HttpResponse.BodyHandlers.discarding());
            assertThat(uploaded.statusCode()).isEqualTo(200);
        }

        mvc.perform(put("/api/pets/1/avatar")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"key\":\"" + key + "\"}"))
            .andExpect(status().isBadRequest());
        assertThat(jdbc.queryForObject("SELECT avatar_url FROM pets WHERE id = 1", String.class))
            .isEqualTo(previousAvatarUrl);
    }
}
