package com.wagwag.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.wagwag.api.pet.PetRepository;
import com.wagwag.api.social.SocialPairLock;
import com.wagwag.api.social.SocialRestrictions;
import com.wagwag.api.task.TaskService;
import com.wagwag.api.task.TaskRatingInput;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("dev")
@Testcontainers
class TaskApiIntegrationTest {
    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
        DockerImageName.parse("postgis/postgis:17-3.5").asCompatibleSubstituteFor("postgres"));

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("app.follow-cache.enabled", () -> false);
    }

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired PetRepository pets;
    @Autowired SocialRestrictions social;
    @Autowired SocialPairLock pairLock;
    @Autowired TransactionTemplate transactions;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM pet_blocks");
        jdbc.update("DELETE FROM pet_mutes");
        jdbc.update("UPDATE pets SET private_profile = FALSE WHERE id = 1000");
        jdbc.update("DELETE FROM task_assignments");
        jdbc.update("DELETE FROM task_events");
        jdbc.update("DELETE FROM task_availability");
        jdbc.update("DELETE FROM tasks WHERE id <> 1000");
        jdbc.update("UPDATE tasks SET status = 'OPEN', latitude = 29.760412, longitude = -95.369845 WHERE id = 1000");
        jdbc.update("INSERT INTO task_events (task_id, actor_pet_id, status) VALUES (1000, 1000, 'OPEN')");
    }

    @Test
    void createBrowseAndCancelOwnTask() throws Exception {
        long id = createTask();
        mvc.perform(get("/api/tasks/{id}", id)).andExpect(status().isOk())
            .andExpect(jsonPath("$.creatorPetId").value(1))
            .andExpect(jsonPath("$.status").value("OPEN"));
        mvc.perform(get("/api/tasks").param("limit", "1"))
            .andExpect(jsonPath("$.items[0].id").value(id))
            .andExpect(jsonPath("$.nextPage").value(1));
        mvc.perform(get("/api/tasks").param("limit", "1").param("page", "1"))
            .andExpect(jsonPath("$.items[0].id").value(1000))
            .andExpect(jsonPath("$.nextPage").value((Object) null));
        mvc.perform(get("/api/tasks").param("scope", "mine"))
            .andExpect(jsonPath("$.items.length()").value(1));
        mvc.perform(post("/api/tasks/{id}/accept", id)).andExpect(status().isForbidden());
        mvc.perform(post("/api/tasks/{id}/cancel", id)).andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("CANCELLED"));
        mvc.perform(get("/api/tasks")).andExpect(jsonPath("$.items.length()").value(1));
        mvc.perform(get("/api/tasks").param("scope", "mine"))
            .andExpect(jsonPath("$.items[0].status").value("CANCELLED"));
        mvc.perform(post("/api/tasks/{id}/cancel", id)).andExpect(status().isConflict());
    }

    @Test
    void acceptStartCompleteAndRejectInvalidTransitions() throws Exception {
        mvc.perform(post("/api/tasks/1000/start")).andExpect(status().isForbidden());
        mvc.perform(post("/api/tasks/1000/complete")).andExpect(status().isForbidden());
        mvc.perform(post("/api/tasks/1000/accept")).andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("ACCEPTED"))
            .andExpect(jsonPath("$.assigneePetId").value(1));
        mvc.perform(post("/api/tasks/1000/accept")).andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM task_assignments WHERE task_id = 1000",
            Long.class)).isEqualTo(1);
        mvc.perform(post("/api/tasks/1000/complete")).andExpect(status().isConflict());
        mvc.perform(post("/api/tasks/1000/start")).andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("IN_PROGRESS"));
        mvc.perform(post("/api/tasks/1000/complete")).andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("COMPLETED"));
        mvc.perform(post("/api/tasks/1000/start")).andExpect(status().isConflict());
        mvc.perform(get("/api/tasks").param("scope", "mine"))
            .andExpect(jsonPath("$.items[0].status").value("COMPLETED"));
    }

    @Test
    void creatorMayCancelAcceptedTaskWithoutDeletingAssignment() throws Exception {
        long id = createTask();
        jdbc.update("INSERT INTO task_assignments (task_id, pet_id) VALUES (?, 1000)", id);
        jdbc.update("UPDATE tasks SET status = 'ACCEPTED' WHERE id = ?", id);
        jdbc.update("INSERT INTO pet_blocks (blocker_pet_id, blocked_pet_id) VALUES (1000, 1)");
        mvc.perform(get("/api/tasks/{id}", id)).andExpect(status().isOk())
            .andExpect(jsonPath("$.locationExact").value(true));
        mvc.perform(get("/api/tasks").param("scope", "mine"))
            .andExpect(jsonPath("$.items[0].id").value(id));
        mvc.perform(post("/api/tasks/{id}/cancel", id)).andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("CANCELLED"));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM task_assignments WHERE task_id = ?",
            Long.class, id)).isEqualTo(1);
        mvc.perform(post("/api/tasks/{id}/accept", id)).andExpect(status().isForbidden());
    }

    @Test
    void concurrentDifferentPetsCannotBothAccept() throws Exception {
        jdbc.update("INSERT INTO pets (id, owner_id, name, species, gender) "
            + "VALUES (1001, 1, 'Pip', 'Dog', 'UNKNOWN') ON CONFLICT (id) DO NOTHING");
        TaskService other = new TaskService(jdbc, pets, social, pairLock, 1001);
        AtomicInteger accepted = new AtomicInteger();
        AtomicInteger conflicted = new AtomicInteger();
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<?> first = executor.submit(() -> {
                ready.countDown();
                go.await();
                try {
                    int result = mvc.perform(post("/api/tasks/1000/accept"))
                        .andReturn().getResponse().getStatus();
                    if (result == 200) accepted.incrementAndGet();
                    else if (result == 409) conflicted.incrementAndGet();
                    else throw new AssertionError("Unexpected status: " + result);
                } catch (Exception error) { throw new RuntimeException(error); }
                return null;
            });
            Future<?> second = executor.submit(() -> {
                ready.countDown();
                go.await();
                try {
                    transactions.executeWithoutResult(status -> {
                        try { other.accept(1000); accepted.incrementAndGet(); }
                        catch (org.springframework.web.server.ResponseStatusException conflict) {
                            assertThat(conflict.getStatusCode().value()).isEqualTo(409);
                            conflicted.incrementAndGet();
                        }
                    });
                } catch (Exception error) { throw new RuntimeException(error); }
                return null;
            });
            ready.await();
            go.countDown();
            first.get();
            second.get();
        }
        assertThat(accepted.get()).isEqualTo(1);
        assertThat(conflicted.get()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM task_assignments WHERE task_id = 1000",
            Long.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT status FROM tasks WHERE id = 1000", String.class))
            .isEqualTo("ACCEPTED");
    }

    @Test
    void inputAndDatabaseConstraints() throws Exception {
        mvc.perform(post("/api/tasks").contentType(MediaType.APPLICATION_JSON)
            .content("{\"title\":\"x\",\"description\":\"help\",\"category\":\"DOG_WALKING\","
                + "\"latitude\":91,\"longitude\":0}"))
            .andExpect(status().isBadRequest());
        mvc.perform(post("/api/tasks").contentType(MediaType.APPLICATION_JSON)
            .content("{\"title\":\" \",\"description\":\"help\",\"category\":\"DOG_WALKING\","
                + "\"latitude\":0,\"longitude\":0}"))
            .andExpect(status().isBadRequest());
        mvc.perform(get("/api/tasks").param("limit", "0")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/tasks").param("scope", "unknown")).andExpect(status().isBadRequest());
        assertThatThrownBy(() -> jdbc.update("INSERT INTO tasks (creator_pet_id, title, description, category, "
            + "latitude, longitude) VALUES (1, 'Invalid', 'Test', 'DOG_WALKING', 91, 0)"))
            .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void blockEitherDirectionHidesTaskAndPreventsNewAssignment(boolean creatorBlocksViewer) throws Exception {
        jdbc.update("INSERT INTO pet_blocks (blocker_pet_id, blocked_pet_id) VALUES (?, ?)",
            creatorBlocksViewer ? 1000 : 1, creatorBlocksViewer ? 1 : 1000);
        long ownTask = createTask();
        mvc.perform(get("/api/tasks").param("limit", "1"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items.length()").value(1))
            .andExpect(jsonPath("$.items[0].id").value(ownTask))
            .andExpect(jsonPath("$.nextPage").value((Object) null));
        mvc.perform(get("/api/tasks/1000")).andExpect(status().isNotFound());
        mvc.perform(post("/api/tasks/1000/accept")).andExpect(status().isNotFound());
        assertThat(jdbc.queryForObject("SELECT status FROM tasks WHERE id = 1000", String.class))
            .isEqualTo("OPEN");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM task_assignments", Long.class)).isZero();
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void existingAssigneeKeepsExactLocationAndCanFinishAfterBlock(boolean creatorBlocksViewer) throws Exception {
        mvc.perform(post("/api/tasks/1000/accept"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.locationExact").value(true));
        jdbc.update("INSERT INTO pet_blocks (blocker_pet_id, blocked_pet_id) VALUES (?, ?)",
            creatorBlocksViewer ? 1000 : 1, creatorBlocksViewer ? 1 : 1000);
        mvc.perform(get("/api/tasks/1000")).andExpect(status().isOk())
            .andExpect(jsonPath("$.locationExact").value(true))
            .andExpect(jsonPath("$.latitude").value(29.760412))
            .andExpect(jsonPath("$.longitude").value(-95.369845));
        mvc.perform(get("/api/tasks").param("scope", "mine"))
            .andExpect(jsonPath("$.items[0].id").value(1000))
            .andExpect(jsonPath("$.items[0].locationExact").value(true));
        mvc.perform(post("/api/tasks/1000/accept")).andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM task_assignments WHERE task_id = 1000",
            Long.class)).isEqualTo(1);
        mvc.perform(post("/api/tasks/1000/start")).andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("IN_PROGRESS"));
        mvc.perform(post("/api/tasks/1000/complete")).andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("COMPLETED"))
            .andExpect(jsonPath("$.locationExact").value(true));
    }

    @Test
    void publicCoordinatesAreApproximateUntilAssigned() throws Exception {
        String list = mvc.perform(get("/api/tasks"))
            .andExpect(jsonPath("$.items[0].locationExact").value(false))
            .andExpect(jsonPath("$.items[0].latitude").value(29.76))
            .andExpect(jsonPath("$.items[0].longitude").value(-95.37))
            .andReturn().getResponse().getContentAsString();
        String detail = mvc.perform(get("/api/tasks/1000"))
            .andExpect(jsonPath("$.locationExact").value(false))
            .andExpect(jsonPath("$.latitude").value(29.76))
            .andExpect(jsonPath("$.longitude").value(-95.37))
            .andReturn().getResponse().getContentAsString();
        assertThat(list).doesNotContain("29.760412", "-95.369845");
        assertThat(detail).doesNotContain("29.760412", "-95.369845");
        assertThat(jdbc.queryForObject("SELECT latitude FROM tasks WHERE id = 1000", Double.class))
            .isEqualTo(29.760412);
        mvc.perform(post("/api/tasks/1000/accept"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.locationExact").value(true))
            .andExpect(jsonPath("$.latitude").value(29.760412))
            .andExpect(jsonPath("$.longitude").value(-95.369845));
        mvc.perform(get("/api/tasks/1000"))
            .andExpect(jsonPath("$.locationExact").value(true))
            .andExpect(jsonPath("$.latitude").value(29.760412))
            .andExpect(jsonPath("$.longitude").value(-95.369845));
        mvc.perform(get("/api/tasks").param("scope", "mine"))
            .andExpect(jsonPath("$.items[0].locationExact").value(true))
            .andExpect(jsonPath("$.items[0].latitude").value(29.760412))
            .andExpect(jsonPath("$.items[0].longitude").value(-95.369845));
    }

    @Test
    void creatorReceivesExactLocationOnEverySurface() throws Exception {
        long id = createTask();
        mvc.perform(get("/api/tasks/{id}", id))
            .andExpect(jsonPath("$.locationExact").value(true))
            .andExpect(jsonPath("$.latitude").value(29.760412))
            .andExpect(jsonPath("$.longitude").value(-95.369845));
        for (String scope : new String[] {"open", "mine"}) {
            mvc.perform(get("/api/tasks").param("scope", scope))
                .andExpect(jsonPath("$.items[0].id").value(id))
                .andExpect(jsonPath("$.items[0].locationExact").value(true))
                .andExpect(jsonPath("$.items[0].latitude").value(29.760412))
                .andExpect(jsonPath("$.items[0].longitude").value(-95.369845));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"ACCEPTED", "COMPLETED", "CANCELLED"})
    void assignmentAndTerminalStatesDoNotExposeExactLocationToThirdParty(String taskStatus) throws Exception {
        jdbc.update("INSERT INTO pets (id, owner_id, name, species, gender) "
            + "VALUES (1001, 1, 'Pip', 'Dog', 'UNKNOWN') ON CONFLICT (id) DO NOTHING");
        jdbc.update("INSERT INTO task_assignments (task_id, pet_id) VALUES (1000, 1001)");
        jdbc.update("UPDATE tasks SET status = ? WHERE id = 1000", taskStatus);
        String detail = mvc.perform(get("/api/tasks/1000"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.locationExact").value(false))
            .andExpect(jsonPath("$.latitude").value(29.76))
            .andExpect(jsonPath("$.longitude").value(-95.37))
            .andReturn().getResponse().getContentAsString();
        assertThat(detail).doesNotContain("29.760412", "-95.369845");
    }

    @Test
    void privateProfilesAndMutesDoNotHideMarketplaceTasks() throws Exception {
        jdbc.update("UPDATE pets SET private_profile = TRUE WHERE id = 1000");
        jdbc.update("INSERT INTO pet_mutes (muter_pet_id, muted_pet_id) VALUES (1, 1000)");
        mvc.perform(get("/api/tasks"))
            .andExpect(jsonPath("$.items[0].id").value(1000));
        mvc.perform(get("/api/tasks/1000")).andExpect(status().isOk());
        mvc.perform(post("/api/tasks/1000/accept")).andExpect(status().isOk());
    }

    @Test
    void nearbyUsesCoarsePublicLocationAndExactCreatorLocation() throws Exception {
        String body = mvc.perform(get("/api/tasks/nearby").param("latitude", "29.76")
                .param("longitude", "-95.37").param("radiusMeters", "10"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items[0].task.id").value(1000))
            .andExpect(jsonPath("$.items[0].task.locationExact").value(false))
            .andExpect(jsonPath("$.items[0].distanceMeters").value(0.0))
            .andReturn().getResponse().getContentAsString();
        assertThat(body).doesNotContain("29.760412", "-95.369845");
        mvc.perform(get("/api/tasks/nearby").param("latitude", "29.760412")
                .param("longitude", "-95.369845").param("radiusMeters", "1"))
            .andExpect(jsonPath("$.items.length()").value(0));
        long ownTask = createTask();
        mvc.perform(get("/api/tasks/nearby").param("latitude", "29.760412")
                .param("longitude", "-95.369845").param("radiusMeters", "1"))
            .andExpect(jsonPath("$.items.length()").value(1))
            .andExpect(jsonPath("$.items[0].task.id").value(ownTask))
            .andExpect(jsonPath("$.items[0].task.locationExact").value(true));
    }

    @Test
    void nearbySortsPaginatesAndExcludesUnavailableTasks() throws Exception {
        long near = fixtureTask(29.77, -95.37);
        fixtureTask(29.79, -95.37);
        long cancelled = fixtureTask(29.76, -95.37);
        jdbc.update("UPDATE tasks SET status = 'CANCELLED' WHERE id = ?", cancelled);
        mvc.perform(get("/api/tasks/nearby").param("latitude", "29.76").param("longitude", "-95.37")
                .param("radiusMeters", "3000").param("limit", "1"))
            .andExpect(jsonPath("$.items[0].task.id").value(1000))
            .andExpect(jsonPath("$.nextPage").value(1));
        mvc.perform(get("/api/tasks/nearby").param("latitude", "29.76").param("longitude", "-95.37")
                .param("radiusMeters", "3000").param("limit", "1").param("page", "1"))
            .andExpect(jsonPath("$.items[0].task.id").value(near))
            .andExpect(jsonPath("$.nextPage").value((Object) null));
        jdbc.update("INSERT INTO pet_blocks (blocker_pet_id, blocked_pet_id) VALUES (1000, 1)");
        mvc.perform(get("/api/tasks/nearby").param("latitude", "29.76").param("longitude", "-95.37"))
            .andExpect(jsonPath("$.items.length()").value(0));
        mvc.perform(get("/api/tasks/nearby").param("latitude", "NaN").param("longitude", "0"))
            .andExpect(status().isBadRequest());
        mvc.perform(get("/api/tasks/nearby").param("latitude", "0").param("longitude", "0")
                .param("radiusMeters", "20001"))
            .andExpect(status().isBadRequest());
    }

    @Test
    void participantsHaveCompleteOrderedHistoryWithoutDuplicateEvents() throws Exception {
        mvc.perform(get("/api/tasks/1000/history")).andExpect(status().isNotFound());
        mvc.perform(post("/api/tasks/1000/accept")).andExpect(status().isOk());
        mvc.perform(post("/api/tasks/1000/accept")).andExpect(status().isOk());
        mvc.perform(post("/api/tasks/1000/start")).andExpect(status().isOk());
        mvc.perform(post("/api/tasks/1000/complete")).andExpect(status().isOk());
        mvc.perform(get("/api/tasks/1000/history"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(4))
            .andExpect(jsonPath("$[0].status").value("OPEN"))
            .andExpect(jsonPath("$[1].status").value("ACCEPTED"))
            .andExpect(jsonPath("$[2].status").value("IN_PROGRESS"))
            .andExpect(jsonPath("$[3].status").value("COMPLETED"));
        mvc.perform(post("/api/tasks/1000/start")).andExpect(status().isConflict());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM task_events WHERE task_id = 1000", Long.class))
            .isEqualTo(4);
        long own = createTask();
        mvc.perform(post("/api/tasks/{id}/cancel", own)).andExpect(status().isOk());
        mvc.perform(get("/api/tasks/{id}/history", own))
            .andExpect(jsonPath("$.length()").value(2))
            .andExpect(jsonPath("$[1].status").value("CANCELLED"));
    }

    @Test
    void availabilityOnlyPreventsNewAcceptances() throws Exception {
        mvc.perform(get("/api/tasks/profile"))
            .andExpect(jsonPath("$.acceptingTasks").value(true))
            .andExpect(jsonPath("$.ratingCount").value(0));
        mvc.perform(put("/api/tasks/availability").contentType(MediaType.APPLICATION_JSON)
                .content("{\"acceptingTasks\":false}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.acceptingTasks").value(false));
        mvc.perform(post("/api/tasks/1000/accept")).andExpect(status().isConflict());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM task_assignments", Long.class)).isZero();
        mvc.perform(put("/api/tasks/availability").contentType(MediaType.APPLICATION_JSON)
                .content("{\"acceptingTasks\":true}"))
            .andExpect(status().isOk());
        mvc.perform(post("/api/tasks/1000/accept")).andExpect(status().isOk());
        mvc.perform(put("/api/tasks/availability").contentType(MediaType.APPLICATION_JSON)
                .content("{\"acceptingTasks\":false}"))
            .andExpect(status().isOk());
        mvc.perform(post("/api/tasks/1000/accept")).andExpect(status().isOk());
        mvc.perform(post("/api/tasks/1000/start")).andExpect(status().isOk());
        mvc.perform(post("/api/tasks/1000/complete")).andExpect(status().isOk());
    }

    @Test
    void onlyCreatorCanRateCompletedTaskAndRatingsCountOnce() throws Exception {
        long own = createTask();
        mvc.perform(put("/api/tasks/{id}/rating", own).contentType(MediaType.APPLICATION_JSON)
                .content("{\"score\":5}"))
            .andExpect(status().isConflict());
        jdbc.update("INSERT INTO task_assignments (task_id, pet_id) VALUES (?, 1000)", own);
        jdbc.update("UPDATE tasks SET status = 'COMPLETED' WHERE id = ?", own);
        mvc.perform(put("/api/tasks/{id}/rating", own).contentType(MediaType.APPLICATION_JSON)
                .content("{\"score\":4,\"comment\":\"  Helpful  \"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.rating.score").value(4))
            .andExpect(jsonPath("$.rating.comment").value("Helpful"));
        mvc.perform(put("/api/tasks/{id}/rating", own).contentType(MediaType.APPLICATION_JSON)
                .content("{\"score\":5}"))
            .andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM task_ratings WHERE task_id = ?", Long.class, own))
            .isEqualTo(1);
        mvc.perform(put("/api/tasks/{id}/rating", own).contentType(MediaType.APPLICATION_JSON)
                .content("{\"score\":6}"))
            .andExpect(status().isBadRequest());
        assertThatThrownBy(() -> jdbc.update("UPDATE task_ratings SET score = 6 WHERE task_id = ?", own))
            .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        mvc.perform(post("/api/tasks/1000/accept")).andExpect(status().isOk());
        mvc.perform(post("/api/tasks/1000/start")).andExpect(status().isOk());
        mvc.perform(post("/api/tasks/1000/complete")).andExpect(status().isOk());
        mvc.perform(put("/api/tasks/1000/rating").contentType(MediaType.APPLICATION_JSON)
                .content("{\"score\":5}"))
            .andExpect(status().isForbidden());
        TaskService creator = new TaskService(jdbc, pets, social, pairLock, 1000);
        transactions.executeWithoutResult(status -> creator.rate(1000, new TaskRatingInput(4, "Great walk")));
        mvc.perform(get("/api/tasks/profile"))
            .andExpect(jsonPath("$.averageRating").value(4.0))
            .andExpect(jsonPath("$.ratingCount").value(1));
    }

    @Test
    void concurrentSamePetAcceptanceReturnsSameAssignmentAndOneEvent() throws Exception {
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            java.util.concurrent.Callable<Void> accept = () -> {
                ready.countDown();
                go.await();
                mvc.perform(post("/api/tasks/1000/accept")).andExpect(status().isOk())
                    .andExpect(jsonPath("$.assigneePetId").value(1));
                return null;
            };
            Future<Void> first = executor.submit(accept);
            Future<Void> second = executor.submit(accept);
            ready.await();
            go.countDown();
            first.get();
            second.get();
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM task_assignments WHERE task_id = 1000", Long.class))
            .isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM task_events WHERE task_id = 1000 AND status = 'ACCEPTED'",
            Long.class)).isEqualTo(1);
    }

    private long fixtureTask(double latitude, double longitude) {
        return jdbc.queryForObject("INSERT INTO tasks (creator_pet_id, title, description, category, latitude, longitude) "
            + "VALUES (1000, 'Nearby help', 'Walk a dog', 'DOG_WALKING', ?, ?) RETURNING id", Long.class,
            latitude, longitude);
    }

    private long createTask() throws Exception {
        String body = mvc.perform(post("/api/tasks").contentType(MediaType.APPLICATION_JSON)
            .content("{\"title\":\"Walk Mochi\",\"description\":\"Park visit\","
                + "\"category\":\"DOG_WALKING\",\"latitude\":29.760412,\"longitude\":-95.369845}"))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.locationExact").value(true))
            .andExpect(jsonPath("$.latitude").value(29.760412))
            .andExpect(jsonPath("$.longitude").value(-95.369845))
            .andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(body, "$.id")).longValue();
    }
}
