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
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.mock.web.MockHttpServletResponse;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("dev")
@Testcontainers
class WalkApiIntegrationTest {
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
        registry.add("spring.data.redis.url", () -> "redis://" + redis.getHost() + ":" + redis.getMappedPort(6379));
    }

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired StringRedisTemplate cache;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM walks");
        jdbc.update("DELETE FROM pet_blocks");
        cache.delete("wagwag:territory:leaderboard");
    }

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
    void routeGeometryDistanceAndSimplifiedDisplayPreserveOriginalPoints() throws Exception {
        String body = "{\"clientWalkId\":\"" + UUID.randomUUID() + "\","
            + "\"startedAt\":\"2026-01-01T10:00:00Z\",\"endedAt\":\"2026-01-01T10:05:00Z\",\"points\":["
            + "{\"latitude\":29.76,\"longitude\":-95.37,\"recordedAt\":\"2026-01-01T10:00:00Z\"},"
            + "{\"latitude\":29.76,\"longitude\":-95.369,\"recordedAt\":\"2026-01-01T10:01:00Z\"},"
            + "{\"latitude\":29.76,\"longitude\":-95.368,\"recordedAt\":\"2026-01-01T10:02:00Z\"}]}";
        MockHttpServletResponse response = submit(body);
        assertThat(response.getStatus()).isEqualTo(201);
        long id = walkId(response);
        assertThat(jdbc.queryForObject("SELECT GeometryType(route) FROM walks WHERE id = ?", String.class, id))
            .isEqualTo("LINESTRING");
        assertThat(jdbc.queryForObject("SELECT ST_SRID(route) FROM walks WHERE id = ?", Integer.class, id)).isEqualTo(4326);
        assertThat(jdbc.queryForObject("SELECT ST_NPoints(route) FROM walks WHERE id = ?", Integer.class, id)).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM pg_indexes WHERE indexname = 'walks_route_geography_idx' "
            + "AND indexdef LIKE '%gist%'", Long.class)).isEqualTo(1);
        mvc.perform(get("/api/walks/{id}", id))
            .andExpect(jsonPath("$.points.length()").value(3))
            .andExpect(jsonPath("$.distanceMeters").value(org.hamcrest.Matchers.greaterThan(190.0)))
            .andExpect(jsonPath("$.route.type").value("LineString"))
            .andExpect(jsonPath("$.route.coordinates.length()").value(2))
            .andExpect(jsonPath("$.route.coordinates[0][0]").value(-95.37));
        assertThat(submit(body).getStatus()).isEqualTo(200);
    }

    @Test
    void singlePointWalkHasZeroDistanceAndCanStillBeRetried() throws Exception {
        String body = "{\"clientWalkId\":\"" + UUID.randomUUID() + "\","
            + "\"startedAt\":\"2026-01-01T10:00:00Z\",\"endedAt\":\"2026-01-01T10:05:00Z\","
            + "\"points\":[{\"latitude\":29.76,\"longitude\":-95.37,\"recordedAt\":\"2026-01-01T10:00:00Z\"}]}";
        MockHttpServletResponse response = submit(body);
        assertThat(response.getStatus()).isEqualTo(201);
        mvc.perform(get("/api/walks/{id}", walkId(response)))
            .andExpect(jsonPath("$.distanceMeters").value(0.0))
            .andExpect(jsonPath("$.points.length()").value(1));
        mvc.perform(post("/api/walks/{id}/territory", walkId(response)))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.areaSquareMeters")
                .value(org.hamcrest.Matchers.greaterThan(1000.0)));
        assertThat(submit(body).getStatus()).isEqualTo(200);
    }

    @Test
    void nearbyUsesRouteDistanceAndOnlyReturnsActivePetsWalks() throws Exception {
        long near = createWalk("2026-01-01T10:00:00Z", "2026-01-01T10:05:00Z");
        String far = walkBody(UUID.randomUUID(), "2026-01-02T10:00:00Z", "2026-01-02T10:05:00Z")
            .replace("29.7604", "30.7604").replace("29.761", "30.761");
        assertThat(submit(far).getStatus()).isEqualTo(201);
        jdbc.update("INSERT INTO walks (pet_id, client_walk_id, started_at, ended_at, route) "
            + "VALUES (1000, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, "
            + "ST_GeomFromText('LINESTRING(-95.3698 29.7604,-95.368 29.761)', 4326))", UUID.randomUUID());
        mvc.perform(get("/api/walks/nearby").param("latitude", "29.7604").param("longitude", "-95.3698")
                .param("radiusMeters", "100"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items.length()").value(1))
            .andExpect(jsonPath("$.items[0].walk.id").value(near))
            .andExpect(jsonPath("$.items[0].proximityMeters").value(0.0));
        long second = createWalk("2026-01-03T10:00:00Z", "2026-01-03T10:05:00Z");
        mvc.perform(get("/api/walks/nearby").param("latitude", "29.7604").param("longitude", "-95.3698")
                .param("radiusMeters", "100").param("limit", "1"))
            .andExpect(jsonPath("$.items[0].walk.id").value(second))
            .andExpect(jsonPath("$.nextPage").value(1));
        mvc.perform(get("/api/walks/nearby").param("latitude", "29.7604").param("longitude", "-95.3698")
                .param("radiusMeters", "100").param("limit", "1").param("page", "1"))
            .andExpect(jsonPath("$.items[0].walk.id").value(near))
            .andExpect(jsonPath("$.nextPage").value((Object) null));
        mvc.perform(get("/api/walks/nearby").param("latitude", "91").param("longitude", "0"))
            .andExpect(status().isBadRequest());
        mvc.perform(get("/api/walks/nearby").param("latitude", "29").param("longitude", "-95")
            .param("radiusMeters", "10001")).andExpect(status().isBadRequest());
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
    void impossibleSpeedCannotCreateWalkOrTerritory() throws Exception {
        String body = routeBody("2026-01-01T10:00:00Z", "2026-01-01T10:01:00Z",
            point(29.7604, -95.3698, "2026-01-01T10:00:00Z"),
            point(40.7128, -74.0060, "2026-01-01T10:01:00Z"));
        mvc.perform(post("/api/walks").contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isBadRequest());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM walks", Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM walk_points", Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM territories", Long.class)).isZero();
    }

    @Test
    void equalPointTimestampsAreRejectedEvenForStationaryPoints() throws Exception {
        String body = routeBody("2026-01-01T10:00:00Z", "2026-01-01T10:01:00Z",
            point(29.7604, -95.3698, "2026-01-01T10:00:00Z"),
            point(29.7610, -95.3680, "2026-01-01T10:00:00Z"));
        mvc.perform(post("/api/walks").contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isBadRequest());
        String stationary = routeBody("2026-01-01T10:00:00Z", "2026-01-01T10:01:00Z",
            point(29.7604, -95.3698, "2026-01-01T10:00:00Z"),
            point(29.7604, -95.3698, "2026-01-01T10:00:00Z"));
        mvc.perform(post("/api/walks").contentType(MediaType.APPLICATION_JSON).content(stationary))
            .andExpect(status().isBadRequest());
        String sameStoredMicrosecond = routeBody("2026-01-01T10:00:00Z", "2026-01-01T10:01:00Z",
            point(29.7604, -95.3698, "2026-01-01T10:00:00.000000100Z"),
            point(29.7604, -95.3698, "2026-01-01T10:00:00.000000900Z"));
        mvc.perform(post("/api/walks").contentType(MediaType.APPLICATION_JSON).content(sameStoredMicrosecond))
            .andExpect(status().isBadRequest());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM walks", Long.class)).isZero();
    }

    @Test
    void plausibleFastWalkAndStationarySegmentCanCreateTerritory() throws Exception {
        String body = routeBody("2026-01-01T10:00:00Z", "2026-01-01T10:00:20Z",
            point(29.7604, -95.3698, "2026-01-01T10:00:00Z"),
            point(29.7609, -95.3698, "2026-01-01T10:00:10Z"),
            point(29.7609, -95.3698, "2026-01-01T10:00:20Z"));
        MockHttpServletResponse created = submit(body);
        assertThat(created.getStatus()).isEqualTo(201);
        long id = walkId(created);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM walk_points WHERE walk_id = ?", Long.class, id))
            .isEqualTo(3);
        mvc.perform(post("/api/walks/{id}/territory", id)).andExpect(status().isCreated());
        assertThat(submit(body).getStatus()).isEqualTo(200);
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
        long other = jdbc.queryForObject("INSERT INTO walks (pet_id, client_walk_id, started_at, ended_at, route) "
            + "VALUES (1000, ?, TIMESTAMPTZ '2026-01-01 10:00:00+00', "
            + "TIMESTAMPTZ '2026-01-01 10:05:00+00', ST_GeomFromText('LINESTRING(-95 29,-95 29)', 4326)) RETURNING id", Long.class, clientId);
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
            + "(pet_id, client_walk_id, started_at, ended_at, route) "
            + "VALUES (1000, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, ST_GeomFromText('LINESTRING(-95 29,-95 29)', 4326))", clientId))
            .isInstanceOf(DataIntegrityViolationException.class);
        mvc.perform(get("/api/walks").param("limit", "0")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/walks").param("page", "-1")).andExpect(status().isBadRequest());
    }

    @Test
    void savedWalkCreatesOnePersistentTerritoryWithPolygonGeometry() throws Exception {
        long walkId = createWalk("2026-01-01T10:00:00Z", "2026-01-01T10:05:00Z");
        mvc.perform(get("/api/walks/{id}/territory", walkId)).andExpect(status().isNotFound());

        MockHttpServletResponse created = mvc.perform(post("/api/walks/{id}/territory", walkId))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.walkId").value(walkId))
            .andExpect(jsonPath("$.petId").value(1))
            .andExpect(jsonPath("$.area.type").value("Polygon"))
            .andExpect(jsonPath("$.area.coordinates[0].length()")
                .value(org.hamcrest.Matchers.greaterThan(3)))
            .andExpect(jsonPath("$.areaSquareMeters")
                .value(org.hamcrest.Matchers.greaterThan(1000.0)))
            .andReturn().getResponse();
        long territoryId = ((Number) JsonPath.read(created.getContentAsString(), "$.id")).longValue();
        assertThat(jdbc.queryForObject("SELECT GeometryType(area) FROM territories WHERE id = ?",
            String.class, territoryId)).isEqualTo("POLYGON");
        assertThat(jdbc.queryForObject("SELECT ST_SRID(area) FROM territories WHERE id = ?",
            Integer.class, territoryId)).isEqualTo(4326);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM pg_indexes WHERE indexname = 'territories_area_idx' "
            + "AND indexdef LIKE '%gist%'", Long.class)).isEqualTo(1);

        mvc.perform(get("/api/walks/{id}/territory", walkId))
            .andExpect(status().isOk()).andExpect(jsonPath("$.id").value(territoryId));
        mvc.perform(post("/api/walks/{id}/territory", walkId))
            .andExpect(status().isOk()).andExpect(jsonPath("$.id").value(territoryId));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM territories", Long.class)).isEqualTo(1);
    }

    @Test
    void territoryClaimRequiresAnOwnedWalk() throws Exception {
        long other = jdbc.queryForObject("INSERT INTO walks (pet_id, client_walk_id, started_at, ended_at, route) "
            + "VALUES (1000, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, "
            + "ST_GeomFromText('LINESTRING(-95 29,-95.001 29)', 4326)) RETURNING id",
            Long.class, UUID.randomUUID());
        mvc.perform(post("/api/walks/{id}/territory", other)).andExpect(status().isNotFound());
        mvc.perform(get("/api/walks/{id}/territory", other)).andExpect(status().isNotFound());
        mvc.perform(post("/api/walks/{id}/territory", Long.MAX_VALUE)).andExpect(status().isNotFound());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM territories", Long.class)).isZero();
    }

    @Test
    void concurrentClaimsCannotCreateTwoTerritoriesForTheSameWalk() throws Exception {
        long walkId = createWalk("2026-01-01T10:00:00Z", "2026-01-01T10:05:00Z");
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            java.util.concurrent.Callable<MockHttpServletResponse> task = () -> {
                ready.countDown();
                if (!go.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Start timed out");
                return mvc.perform(post("/api/walks/{id}/territory", walkId)).andReturn().getResponse();
            };
            Future<MockHttpServletResponse> first = executor.submit(task);
            Future<MockHttpServletResponse> second = executor.submit(task);
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            go.countDown();
            MockHttpServletResponse firstResponse = first.get(20, TimeUnit.SECONDS);
            MockHttpServletResponse secondResponse = second.get(20, TimeUnit.SECONDS);
            assertThat(firstResponse.getStatus()).isIn(200, 201);
            assertThat(secondResponse.getStatus()).isIn(200, 201);
            assertThat(JsonPath.<Number>read(firstResponse.getContentAsString(), "$.id").longValue())
                .isEqualTo(JsonPath.<Number>read(secondResponse.getContentAsString(), "$.id").longValue());
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM territories", Long.class)).isEqualTo(1);
        } finally {
            go.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void strongerRivalOwnsOverlapWhileOriginalClaimRemainsInHistory() throws Exception {
        long ownWalk = createWalk("2026-01-01T10:00:00Z", "2026-01-01T10:05:00Z");
        mvc.perform(post("/api/walks/{id}/territory", ownWalk)).andExpect(status().isCreated());
        insertRivalTerritory("LINESTRING(-95.3698 29.7604,-95.368 29.761)", 5, 0);

        mvc.perform(get("/api/walks/{id}/territory", ownWalk))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.area.type").value("Polygon"))
            .andExpect(jsonPath("$.ownedArea.type").value("MultiPolygon"))
            .andExpect(jsonPath("$.ownedAreaSquareMeters").value(0.0))
            .andExpect(jsonPath("$.contestedAreaSquareMeters")
                .value(org.hamcrest.Matchers.greaterThan(1000.0)));
        mvc.perform(get("/api/territories/history"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items.length()").value(1))
            .andExpect(jsonPath("$.items[0].walkId").value(ownWalk))
            .andExpect(jsonPath("$.items[0].ownedAreaSquareMeters").value(0.0));
        mvc.perform(get("/api/territories/leaderboard"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].petId").value(1000))
            .andExpect(jsonPath("$[0].areaSquareMeters")
                .value(org.hamcrest.Matchers.greaterThan(1000.0)));
        jdbc.update("INSERT INTO pet_blocks (blocker_pet_id, blocked_pet_id) VALUES (1000, 1)");
        mvc.perform(get("/api/territories/leaderboard"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void partialOverlapIsExcludedFromWeakerClaimArea() throws Exception {
        long ownWalk = createWalk("2026-01-01T10:00:00Z", "2026-01-01T10:05:00Z");
        mvc.perform(post("/api/walks/{id}/territory", ownWalk)).andExpect(status().isCreated());
        insertRivalTerritory("LINESTRING(-95.3698 29.7606,-95.368 29.7612)", 5, 0);
        String body = mvc.perform(get("/api/walks/{id}/territory", ownWalk))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        double claimed = JsonPath.read(body, "$.areaSquareMeters");
        double owned = JsonPath.read(body, "$.ownedAreaSquareMeters");
        assertThat(owned).isGreaterThan(0).isLessThan(claimed);
    }

    @Test
    void weeklyDecayLetsFreshClaimBeatOldStrongerClaim() throws Exception {
        insertRivalTerritory("LINESTRING(-95.3698 29.7604,-95.368 29.761)", 5, 35);
        long ownWalk = createWalk("2026-01-01T10:00:00Z", "2026-01-01T10:05:00Z");
        mvc.perform(post("/api/walks/{id}/territory", ownWalk)).andExpect(status().isCreated());
        String body = mvc.perform(get("/api/walks/{id}/territory", ownWalk))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.effectiveStrength").value(1))
            .andReturn().getResponse().getContentAsString();
        double claimed = JsonPath.read(body, "$.areaSquareMeters");
        double owned = JsonPath.read(body, "$.ownedAreaSquareMeters");
        assertThat(owned).isCloseTo(claimed, org.assertj.core.data.Offset.offset(0.01));
        mvc.perform(get("/api/territories/leaderboard"))
            .andExpect(jsonPath("$[0].petId").value(1));
    }

    @Test
    void leaderboardCacheInvalidatesAfterClaimAndHistoryPaginates() throws Exception {
        mvc.perform(get("/api/territories/leaderboard"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));
        long first = createWalk("2026-01-01T10:00:00Z", "2026-01-01T10:05:00Z");
        mvc.perform(post("/api/walks/{id}/territory", first)).andExpect(status().isCreated());
        mvc.perform(get("/api/territories/leaderboard"))
            .andExpect(status().isOk()).andExpect(jsonPath("$[0].petId").value(1));
        long second = createWalk("2026-01-02T10:00:00Z", "2026-01-02T10:05:00Z");
        mvc.perform(post("/api/walks/{id}/territory", second)).andExpect(status().isCreated());
        String leaderboard = mvc.perform(get("/api/territories/leaderboard"))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String secondClaim = mvc.perform(get("/api/walks/{id}/territory", second))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(JsonPath.<Double>read(leaderboard, "$[0].areaSquareMeters"))
            .isCloseTo(JsonPath.read(secondClaim, "$.areaSquareMeters"),
                org.assertj.core.data.Offset.offset(0.01));
        mvc.perform(get("/api/territories/history").param("limit", "1"))
            .andExpect(jsonPath("$.items[0].walkId").value(second))
            .andExpect(jsonPath("$.nextPage").value(1));
        mvc.perform(get("/api/territories/history").param("limit", "1").param("page", "1"))
            .andExpect(jsonPath("$.items[0].walkId").value(first))
            .andExpect(jsonPath("$.nextPage").value((Object) null));
        mvc.perform(get("/api/territories/history").param("limit", "0"))
            .andExpect(status().isBadRequest());
        mvc.perform(get("/api/territories/leaderboard").param("limit", "51"))
            .andExpect(status().isBadRequest());
    }

    @Test
    void longPlausibleWalkCapsClaimStrengthAtFive() throws Exception {
        String body = routeBody("2026-01-01T10:00:00Z", "2026-01-01T10:05:00Z",
            point(29.7604, -95.3698, "2026-01-01T10:00:00Z"),
            point(29.7604, -95.3568, "2026-01-01T10:05:00Z"));
        long walkId = walkId(submit(body));
        mvc.perform(post("/api/walks/{id}/territory", walkId))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.baseStrength").value(5))
            .andExpect(jsonPath("$.effectiveStrength").value(5));
    }

    private long insertRivalTerritory(String route, int strength, int ageDays) {
        long walkId = jdbc.queryForObject("INSERT INTO walks (pet_id, client_walk_id, started_at, ended_at, route) "
            + "VALUES (1000, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, ST_GeomFromText(?, 4326)) RETURNING id",
            Long.class, UUID.randomUUID(), route);
        jdbc.update("INSERT INTO territories (walk_id, area, base_strength, created_at) "
            + "SELECT id, ST_Buffer(route::geography, 20)::geometry, ?, CURRENT_TIMESTAMP - (? * INTERVAL '1 day') "
            + "FROM walks WHERE id = ?", strength, ageDays, walkId);
        return walkId;
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

    private String routeBody(String startedAt, String endedAt, String... points) {
        return "{\"clientWalkId\":\"" + UUID.randomUUID() + "\",\"startedAt\":\"" + startedAt
            + "\",\"endedAt\":\"" + endedAt + "\",\"points\":[" + String.join(",", points) + "]}";
    }

    private String point(double latitude, double longitude, String recordedAt) {
        return "{\"latitude\":" + latitude + ",\"longitude\":" + longitude
            + ",\"recordedAt\":\"" + recordedAt + "\"}";
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
