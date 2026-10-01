package com.wagwag.api;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

@SpringBootTest
@TestPropertySource(properties = {
    "spring.datasource.url=jdbc:h2:mem:wagwag-default;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE",
    "spring.datasource.driver-class-name=org.h2.Driver",
    "spring.datasource.username=sa",
    "spring.datasource.password="
})
class DefaultConfigurationTest {
    @Autowired JdbcTemplate jdbc;

    @Test
    void defaultConfigurationCreatesSchemaWithoutDevelopmentData() {
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM users", Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM pets", Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM flyway_schema_history WHERE version = '2'", Long.class))
            .isZero();
    }
}
