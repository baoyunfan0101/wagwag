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
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.PutBucketPolicyRequest;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
class PetApiIntegrationTest {
    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine");

    @Container
    static final GenericContainer<?> minio = new GenericContainer<>("minio/minio:RELEASE.2025-09-07T16-13-09Z")
        .withExposedPorts(9000)
        .withEnv("MINIO_ROOT_USER", "testaccess")
        .withEnv("MINIO_ROOT_PASSWORD", "testsecret")
        .withCommand("server", "/data");

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("app.storage.bucket", () -> "wagwag-avatars");
        registry.add("app.storage.endpoint", PetApiIntegrationTest::minioUrl);
        registry.add("app.storage.public-base-url", () -> minioUrl() + "/wagwag-avatars");
        registry.add("app.storage.access-key", () -> "testaccess");
        registry.add("app.storage.secret-key", () -> "testsecret");
    }

    static String minioUrl() {
        return "http://" + minio.getHost() + ":" + minio.getMappedPort(9000);
    }

    @BeforeAll
    static void createBucket() {
        try (S3Client s3 = S3Client.builder()
            .region(Region.US_EAST_1)
            .endpointOverride(URI.create(minioUrl()))
            .forcePathStyle(true)
            .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("testaccess", "testsecret")))
            .build()) {
            s3.createBucket(CreateBucketRequest.builder().bucket("wagwag-avatars").build());
            String policy = """
                {"Version":"2012-10-17","Statement":[{"Effect":"Allow","Principal":"*",
                  "Action":["s3:GetObject"],"Resource":["arn:aws:s3:::wagwag-avatars/*"]}]}
                """;
            s3.putBucketPolicy(PutBucketPolicyRequest.builder().bucket("wagwag-avatars").policy(policy).build());
        }
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
}
