package com.stocktracker.connectors;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.stocktracker.controller.GlobalExceptionHandler;
import com.stocktracker.packs.SecretVault;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The real V13 migration, JdbcConnectorStore, SecretVault and /api/connectors against Postgres.
 * Flyway is off because V1 needs TimescaleDB; V7 (the vault table) and V13 are applied verbatim.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
@Import(SecretVault.class)
@Sql({"classpath:db/migration/V7__packs.sql", "classpath:db/migration/V13__connectors.sql"})
@TestPropertySource(properties = {"spring.flyway.enabled=false", "spring.jpa.hibernate.ddl-auto=none"})
class ConnectorPersistenceIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    static final Path TMP;

    static {
        try {
            TMP = Files.createTempDirectory("connectors-it");
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        r.add("spring.datasource.username", POSTGRES::getUsername);
        r.add("spring.datasource.password", POSTGRES::getPassword);
        r.add("ananke.secret-key-file", () -> TMP.resolve("secret.key").toString());
    }

    @AfterAll
    static void cleanUp() throws Exception {
        try (var files = Files.walk(TMP)) {
            files.sorted(java.util.Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        }
    }

    static final String KEY = "live-key-secret-9876";

    @Autowired JdbcTemplate jdbc;
    @Autowired SecretVault vault;
    final ObjectMapper om = new ObjectMapper().registerModule(new JavaTimeModule());
    MockMvc mvc;

    @BeforeEach
    void setUp() {
        Connector news = new Connector() {
            final ConnectorSpec spec = new ConnectorSpec("newsy", "Newsy", ConnectorSpec.Category.NEWS,
                    ConnectorSpec.Licence.BYO_PERSONAL, "Headlines.", "Data: Newsy", "https://example.com",
                    List.of(ConnectorSpec.Field.secret("api_key", "API key", null),
                            ConnectorSpec.Field.text("region", "Region", null, "us")),
                    List.of());

            @Override public ConnectorSpec spec() { return spec; }

            @Override
            public List<ConnectorTool> tools() {
                return List.of(new ConnectorTool("newsy_headlines", "Top headlines.", Map.of("type", "object"),
                        Duration.ofMinutes(5), (ctx, a) -> Map.of("region", ctx.require("region"), "items", List.of("A", "B")), null));
            }

            @Override
            public String test(ConnectorContext ctx) {
                if (!ctx.require("api_key").startsWith("live-")) {
                    throw new ConnectorException(ConnectorException.Kind.AUTH, "Newsy rejected the key.");
                }
                return "Linked for region " + ctx.require("region");
            }
        };
        ConnectorService service = new ConnectorService(List.of(news), new JdbcConnectorStore(jdbc, vault),
                RateLimiter.system(), new ResponseCache(Clock.systemUTC(), 100), HttpClient.newHttpClient(), om, Clock.systemUTC());
        mvc = MockMvcBuilders.standaloneSetup(new ConnectorController(service))
                .setControllerAdvice(new GlobalExceptionHandler())
                .setMessageConverters(new MappingJackson2HttpMessageConverter(om))
                .build();
    }

    @Test
    void linkTestToggleCallUnlink_roundTripThroughPostgres() throws Exception {
        mvc.perform(get("/api/connectors"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].status").value("NOT_LINKED"))
                .andExpect(jsonPath("$[0].licence").value("BYO_PERSONAL"));

        // A rejected key is a 400 and nothing is stored.
        mvc.perform(put("/api/connectors/newsy/credentials").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"values\":{\"api_key\":\"bad-key-000000\",\"region\":\"us\"}}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Newsy rejected the key."));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ananke_secrets WHERE scope = 'connector:newsy'", Integer.class)).isZero();

        // Linked: the secret is encrypted at rest and only a hint comes back; the non-secret field echoes.
        String body = mvc.perform(put("/api/connectors/newsy/credentials").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"values\":{\"api_key\":\"" + KEY + "\",\"region\":\"us\"}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("READY"))
                .andExpect(jsonPath("$.lastTestMessage").value("Linked for region us"))
                .andExpect(jsonPath("$.fields[0].hint").value("…9876"))
                .andExpect(jsonPath("$.fields[1].hint").value("us"))
                .andReturn().getResponse().getContentAsString();
        assertThat(body).doesNotContain(KEY);
        List<String> stored = jdbc.queryForList("SELECT ciphertext FROM ananke_secrets WHERE scope = 'connector:newsy'", String.class);
        assertThat(stored).hasSize(2).noneMatch(ct -> ct.contains(KEY) || ct.contains("\"us\""));

        // Re-link with a blank key keeps it; the upsert updates the same row.
        mvc.perform(put("/api/connectors/newsy/credentials").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"values\":{\"api_key\":\"\",\"region\":\"eu\"}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fields[1].hint").value("eu"));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM connector_accounts WHERE connector_id = 'newsy'", Integer.class)).isEqualTo(1);

        mvc.perform(post("/api/connectors/newsy/tools/newsy_headlines").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.payload.region").value("eu"))
                .andExpect(jsonPath("$.attribution").value("Data: Newsy"))
                .andExpect(jsonPath("$.cached").value(false));
        assertThat(jdbc.queryForObject("SELECT last_call_at IS NOT NULL FROM connector_accounts WHERE connector_id = 'newsy'", Boolean.class)).isTrue();

        // Switched off: calls are a 409, and the switch survives a fresh read.
        mvc.perform(put("/api/connectors/newsy").contentType(MediaType.APPLICATION_JSON).content("{\"enabled\":false}"))
                .andExpect(jsonPath("$.status").value("DISABLED"));
        mvc.perform(post("/api/connectors/newsy/tools/newsy_headlines").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isConflict());
        mvc.perform(get("/api/connectors/newsy")).andExpect(jsonPath("$.enabled").value(false));

        mvc.perform(post("/api/connectors/newsy/tools/nope").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isNotFound());

        // Unlink wipes both the vault scope and the state row.
        mvc.perform(delete("/api/connectors/newsy/credentials"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("NOT_LINKED"))
                .andExpect(jsonPath("$.enabled").value(false));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ananke_secrets WHERE scope = 'connector:newsy'", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM connector_accounts", Integer.class)).isZero();
    }
}
