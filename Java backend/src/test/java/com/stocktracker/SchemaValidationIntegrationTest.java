package com.stocktracker;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Production parity for the schema: every Flyway migration runs on the same TimescaleDB image
 * docker-compose uses, then Hibernate validates every entity against the result exactly as
 * {@code spring.jpa.hibernate.ddl-auto=validate} does at startup. A migration whose column
 * types drift from its entity fails here instead of at backend boot.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
@TestPropertySource(properties = {
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=validate"
})
class SchemaValidationIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> TIMESCALE = new PostgreSQLContainer<>(
            DockerImageName.parse("timescale/timescaledb:latest-pg16").asCompatibleSubstituteFor("postgres"));

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", TIMESCALE::getJdbcUrl);
        r.add("spring.datasource.username", TIMESCALE::getUsername);
        r.add("spring.datasource.password", TIMESCALE::getPassword);
    }

    @Autowired JdbcTemplate jdbc;

    @Test
    void allMigrationsApplyAndEveryEntityValidates() {
        Integer latest = jdbc.queryForObject(
                "SELECT MAX(CAST(version AS INTEGER)) FROM flyway_schema_history WHERE success", Integer.class);
        assertThat(latest).isGreaterThanOrEqualTo(10);
    }
}
