package com.wagwag.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.util.UUID;
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
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.mock.web.MockHttpServletResponse;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("dev")
@Testcontainers
class WalkApiIntegrationTest {
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
    void clean() { jdbc.update("DELETE FROM walks"); }

    @Test
    void completedWalkPersistsOrderedRouteAndHistory() throws Exception {
        long first = createWalk("2026-01-01T10:00:00Z", "2026-01-01T10:05:00Z");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM walk_points WHERE walk_id = ?",
            Long.class, first)).isEqualTo(2);
        mvc.perform(get("/api/walks/{id}", first))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.petId").value(1))
            .andExpect(jsonPath("$.points.length()").value(2))
            .andExpect(jsonPath("$.points[0].latitude").value(29.7604))
            .andExpect(jsonPath("$.points[1].longitude").value(-95.368));
        long second = createWalk("2026-01-02T10:00:00Z", "2026-01-02T10:05:00Z");
        mvc.perform(get("/api/walks").param("limit", "1"))
            .andExpect(jsonPath("$.items[0].id").value(second))
            .andExpect(jsonPath("$.items[0].pointCount").value(2))
            .andExpect(jsonPath("$.nextPage").value(1));
        mvc.perform(get("/api/walks").param("limit", "1").param("page", "1"))
            .andExpect(jsonPath("$.items[0].id").value(first))
            .andExpect(jsonPath("$.nextPage").value((Object) null));
    }

    @Test
    void invalidWalksAreRejectedWithoutRows() throws Exception {
        invalid("{\"startedAt\":\"2026-01-01T10:00:00Z\",\"endedAt\":\"2026-01-01T10:05:00Z\",\"points\":[]}");
        invalid("{\"startedAt\":\"2026-01-01T10:05:00Z\",\"endedAt\":\"2026-01-01T10:00:00Z\",\"points\":[{\"latitude\":29,\"longitude\":-95,\"recordedAt\":\"2026-01-01T10:00:00Z\"}]}");
        invalid("{\"startedAt\":\"2026-01-01T10:00:00Z\",\"endedAt\":\"2026-01-01T10:05:00Z\",\"points\":[{\"latitude\":91,\"longitude\":-95,\"recordedAt\":\"2026-01-01T10:01:00Z\"}]}");
        invalid("{\"startedAt\":\"2026-01-01T10:00:00Z\",\"endedAt\":\"2026-01-01T10:05:00Z\",\"points\":[{\"latitude\":29,\"longitude\":-95,\"recordedAt\":\"2026-01-01T10:03:00Z\"},{\"latitude\":29,\"longitude\":-95,\"recordedAt\":\"2026-01-01T10:02:00Z\"}]}");
        mvc.perform(post("/api/walks").contentType(MediaType.APPLICATION_JSON)
            .content("{\"clientWalkId\":\"not-a-uuid\"}"))
            .andExpect(status().isBadRequest());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM walks", Long.class)).isZero();
    }

    @Test
    void exactRetryReturnsOneWalkAndOneSetOfPoints() throws Exception {
        UUID clientId = UUID.randomUUID();
        String body = walkBody(clientId, "2026-01-01T10:00:00Z", "2026-01-01T10:05:00Z");
        MockHttpServletResponse first = submit(body);
        MockHttpServletResponse retry = submit(body);
        assertThat(first.getStatus()).isEqualTo(201);
        assertThat(retry.getStatus()).isEqualTo(200);
        long id = walkId(first);
        assertThat(walkId(retry)).isEqualTo(id);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM walks", Long.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM walk_points WHERE walk_id = ?", Long.class, id))
            .isEqualTo(2);
        mvc.perform(get("/api/walks/{id}", id))
            .andExpect(jsonPath("$.points[0].latitude").value(29.7604))
            .andExpect(jsonPath("$.points[1].longitude").value(-95.368));
    }

    @Test
    void concurrentDuplicateSubmissionsResolveToOneWalk() throws Exception {
        String body = walkBody(UUID.randomUUID(), "2026-01-01T10:00:00Z", "2026-01-01T10:05:00Z");
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            java.util.concurrent.Callable<MockHttpServletResponse> task = () -> {
                ready.countDown();
                if (!go.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Start timed out");
                return submit(body);
            };
            Future<MockHttpServletResponse> first = executor.submit(task);
            Future<MockHttpServletResponse> second = executor.submit(task);
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            go.countDown();
            MockHttpServletResponse firstResponse = first.get(20, TimeUnit.SECONDS);
            MockHttpServletResponse secondResponse = second.get(20, TimeUnit.SECONDS);
            assertThat(firstResponse.getStatus()).isIn(200, 201);
            assertThat(secondResponse.getStatus()).isIn(200, 201);
            assertThat(walkId(firstResponse)).isEqualTo(walkId(secondResponse));
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM walks", Long.class)).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM walk_points", Long.class)).isEqualTo(2);
        } finally {
            go.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void changedRouteWithSameClientIdReturnsConflict() throws Exception {
        UUID clientId = UUID.randomUUID();
        long id = walkId(submit(walkBody(clientId, "2026-01-01T10:00:00Z", "2026-01-01T10:05:00Z")));
        String different = walkBody(clientId, "2026-01-01T12:00:00Z", "2026-01-01T12:05:00Z");
        mvc.perform(post("/api/walks").contentType(MediaType.APPLICATION_JSON).content(different))
            .andExpect(status().isConflict());
        String differentPoint = walkBody(clientId, "2026-01-01T10:00:00Z", "2026-01-01T10:05:00Z")
            .replace("29.7604", "29.7605");
        mvc.perform(post("/api/walks").contentType(MediaType.APPLICATION_JSON).content(differentPoint))
            .andExpect(status().isConflict());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM walks", Long.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM walk_points", Long.class)).isEqualTo(2);
        mvc.perform(get("/api/walks/{id}", id))
            .andExpect(jsonPath("$.startedAt").value("2026-01-01T10:00:00Z"));
    }

    @Test
    void walkHistoryIsScopedToActivePetAndCoordinatesHaveDatabaseConstraints() throws Exception {
        UUID clientId = UUID.randomUUID();
        long other = jdbc.queryForObject("INSERT INTO walks (pet_id, client_walk_id, started_at, ended_at) "
            + "VALUES (1000, ?, TIMESTAMPTZ '2026-01-01 10:00:00+00', "
            + "TIMESTAMPTZ '2026-01-01 10:05:00+00') RETURNING id", Long.class, clientId);
        mvc.perform(get("/api/walks/{id}", other)).andExpect(status().isNotFound());
        mvc.perform(get("/api/walks")).andExpect(jsonPath("$.items.length()").value(0));
        assertThatThrownBy(() -> jdbc.update("INSERT INTO walk_points "
            + "(walk_id, sequence_number, latitude, longitude, recorded_at) "
            + "VALUES (?, 0, 91, -95, CURRENT_TIMESTAMP)", other))
            .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("INSERT INTO walk_points "
            + "(walk_id, sequence_number, latitude, longitude, recorded_at) "
            + "VALUES (?, -1, 29, -95, CURRENT_TIMESTAMP)", other))
            .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("INSERT INTO walks "
            + "(pet_id, client_walk_id, started_at, ended_at) "
            + "VALUES (1000, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)", clientId))
            .isInstanceOf(DataIntegrityViolationException.class);
        mvc.perform(get("/api/walks").param("limit", "0")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/walks").param("page", "-1")).andExpect(status().isBadRequest());
    }

    private long createWalk(String startedAt, String endedAt) throws Exception {
        MockHttpServletResponse response = submit(walkBody(UUID.randomUUID(), startedAt, endedAt));
        assertThat(response.getStatus()).isEqualTo(201);
        return walkId(response);
    }

    private String walkBody(UUID clientId, String startedAt, String endedAt) {
        return "{\"clientWalkId\":\"" + clientId + "\",\"startedAt\":\"" + startedAt + "\",\"endedAt\":\"" + endedAt
            + "\",\"points\":[{\"latitude\":29.7604,\"longitude\":-95.3698,\"recordedAt\":\""
            + startedAt + "\"},{\"latitude\":29.761,\"longitude\":-95.368,\"recordedAt\":\""
            + endedAt + "\"}]}";
    }

    private MockHttpServletResponse submit(String body) throws Exception {
        return mvc.perform(post("/api/walks").contentType(MediaType.APPLICATION_JSON).content(body))
            .andReturn().getResponse();
    }

    private long walkId(MockHttpServletResponse response) throws Exception {
        return ((Number) JsonPath.read(response.getContentAsString(), "$.id")).longValue();
    }

    private void invalid(String body) throws Exception {
        String withId = body.replaceFirst("\\{", "{\"clientWalkId\":\"" + UUID.randomUUID() + "\",");
        mvc.perform(post("/api/walks").contentType(MediaType.APPLICATION_JSON).content(withId))
            .andExpect(status().isBadRequest());
    }
}
