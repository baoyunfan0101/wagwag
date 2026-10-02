package com.wagwag.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
class PetApiSmokeTest {
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
    @Autowired DevelopmentSeed seed;

    @Test
    void developmentFixturesArePresentAndRepeatedSeedingIsSafe() {
        String mochiName = jdbc.queryForObject("SELECT name FROM pets WHERE id = 1", String.class);
        assertThat(mochiName).startsWith("Mochi");
        assertThat(jdbc.queryForObject("SELECT name FROM pets WHERE id = 1000", String.class)).isEqualTo("Biscuit");
        Long usersBefore = jdbc.queryForObject("SELECT COUNT(*) FROM users", Long.class);
        Long petsBefore = jdbc.queryForObject("SELECT COUNT(*) FROM pets", Long.class);
        seed.run(null);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM users", Long.class)).isEqualTo(usersBefore);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM pets", Long.class)).isEqualTo(petsBefore);
        assertThat(jdbc.queryForObject("SELECT name FROM pets WHERE id = 1", String.class)).isEqualTo(mochiName);
    }

    @Test
    void profileRoundTrip() throws Exception {
        mvc.perform(get("/api/pets/1"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.name").value("Mochi"));
        mvc.perform(put("/api/pets/1").contentType(MediaType.APPLICATION_JSON).content("""
            {"name":"Mochi Updated","species":"Dog","breed":"Shiba Inu",
             "gender":"MALE","birthday":"2022-05-14","bio":"Saved after restart"}
            """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.name").value("Mochi Updated"));
        assertThat(jdbc.queryForObject("SELECT bio FROM pets WHERE id = 1", String.class))
            .isEqualTo("Saved after restart");

        mvc.perform(post("/api/pets").contentType(MediaType.APPLICATION_JSON).content("""
            {"name":"Pip","species":"Cat","gender":"UNKNOWN"}
            """))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.ownerId").value(1));

        jdbc.update("INSERT INTO users (id, display_name) VALUES (50, 'Other')");
        jdbc.update("INSERT INTO pets (id, owner_id, name, species, gender) VALUES (50, 50, 'Other pet', 'Cat', 'UNKNOWN')");
        mvc.perform(put("/api/pets/50").contentType(MediaType.APPLICATION_JSON).content("""
            {"name":"Changed","species":"Cat","gender":"UNKNOWN"}
            """))
            .andExpect(status().isForbidden());
    }
}
