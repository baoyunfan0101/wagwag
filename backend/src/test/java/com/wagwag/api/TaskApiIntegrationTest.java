package com.wagwag.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.wagwag.api.pet.PetRepository;
import com.wagwag.api.task.TaskService;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
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
    @Autowired TransactionTemplate transactions;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM task_assignments");
        jdbc.update("DELETE FROM tasks WHERE id <> 1000");
        jdbc.update("UPDATE tasks SET status = 'OPEN' WHERE id = 1000");
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
        TaskService other = new TaskService(jdbc, pets, 1001);
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

    private long createTask() throws Exception {
        String body = mvc.perform(post("/api/tasks").contentType(MediaType.APPLICATION_JSON)
            .content("{\"title\":\"Walk Mochi\",\"description\":\"Park visit\","
                + "\"category\":\"DOG_WALKING\",\"latitude\":29.76,\"longitude\":-95.37}"))
            .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(body, "$.id")).longValue();
    }
}
