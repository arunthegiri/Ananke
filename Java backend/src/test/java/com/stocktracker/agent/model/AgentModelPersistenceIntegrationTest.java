package com.stocktracker.agent.model;

import com.stocktracker.packs.SecretVault;
import com.stocktracker.service.FakeLlmServer;
import com.stocktracker.service.LlmSettingsService;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
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

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The real V10 migration, JPA mappings, vault and Spring wiring against Postgres. Flyway is off
 * because V1 needs TimescaleDB; the two migrations this feature depends on (V7 for the vault
 * table, V10 itself) are applied verbatim, so the SQL under test is the SQL that ships.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
@ImportAutoConfiguration(JacksonAutoConfiguration.class)
@Import({AgentModelConfig.class, SecretVault.class, LlmSettingsService.class})
@Sql({"classpath:db/migration/V7__packs.sql", "classpath:db/migration/V10__agent_model_routing.sql"})
@TestPropertySource(properties = {
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=none",
        "ananke.features.per-agent-models=true"
})
class AgentModelPersistenceIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    static final Path TMP;

    static {
        try {
            TMP = Files.createTempDirectory("agent-model-it");
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
        r.add("ananke.settings.file", () -> TMP.resolve("llm-settings.json").toString());
    }

    @AfterAll
    static void cleanUp() throws Exception {
        try (var files = Files.walk(TMP)) {
            files.sorted(java.util.Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        }
    }

    @Autowired AgentModelService service;
    @Autowired AgentModelResolver resolver;
    @Autowired ModelClientFactory factory;
    @Autowired ModelProbeService probes;
    @Autowired ModelCallRecordRepository calls;
    @Autowired AgentRuntimeFlags flags;
    @Autowired LlmSettingsService legacy;
    @Autowired JdbcTemplate jdbc;

    private AgentModelService.ConnectionView hosted(String key) {
        return service.createConnection(Workspaces.LOCAL, new AgentModelService.ConnectionRequest(
                "Hosted", Adapter.OPENAI_COMPATIBLE, "openai", "https://api.example.com/v1", null, key, null, null));
    }

    @Test
    void keysAreStoredEncryptedAndOnlyAHintIsReturned() {
        AgentModelService.ConnectionView c = hosted("sk-test-abcdefgh1234");
        assertThat(c.keyHint()).isEqualTo("…1234");
        assertThat(c.trustZone()).isEqualTo(TrustZone.EXTERNAL_API);
        assertThat(c.keyRequired()).isTrue();

        String ct = jdbc.queryForObject("SELECT ciphertext FROM ananke_secrets WHERE scope = ?", String.class,
                "model-conn:" + c.id());
        assertThat(ct).startsWith("v1:").doesNotContain("abcdefgh1234");
    }

    @Test
    void keyRotationDoesNotBumpTheVersionButAnEndpointEditDoes() {
        AgentModelService.ConnectionView c = hosted("sk-test-abcdefgh1234");
        long v0 = c.version();
        var rotated = service.updateConnection(Workspaces.LOCAL, c.id(), new AgentModelService.ConnectionRequest(
                null, null, null, null, null, "sk-new-key-99998888", null, v0));
        assertThat(rotated.version()).isEqualTo(v0);
        assertThat(rotated.keyHint()).isEqualTo("…8888");

        var moved = service.updateConnection(Workspaces.LOCAL, c.id(), new AgentModelService.ConnectionRequest(
                null, null, null, "http://localhost:11434/v1", null, null, null, v0));
        assertThat(moved.version()).isGreaterThan(v0);
        assertThat(moved.trustZone()).isEqualTo(TrustZone.LOCAL);

        assertThatThrownBy(() -> service.updateConnection(Workspaces.LOCAL, c.id(),
                new AgentModelService.ConnectionRequest("stale", null, null, null, null, null, null, v0)))
                .isInstanceOf(AgentModelService.ConflictException.class);
    }

    @Test
    void bindingsRoundTripThroughJsonbAndResolve() {
        var a = hosted("sk-test-abcdefgh1234");
        var b = hosted("sk-test-zzzzzzzz5678");
        var saved = service.putBinding(Workspaces.LOCAL, "reporter", new AgentModelService.BindingRequest(
                "workspace", null, false, a.id(), "writer", 2000, "explicit",
                List.of(new FallbackPolicy.Target(b.id(), "writer-backup")), null));
        assertThat(saved.fallback().targets()).containsExactly(new FallbackPolicy.Target(b.id(), "writer-backup"));

        ResolvedModelBinding r = resolver.resolve(Workspaces.LOCAL, null, AgentRole.REPORTER, null, TrustZone.ANY);
        assertThat(r.modelId()).isEqualTo("writer");
        assertThat(r.maxOutputTokens()).isEqualTo(2000);
        assertThat(r.fallbacks()).singleElement().extracting(ResolvedModelBinding::modelId).isEqualTo("writer-backup");

        assertThatThrownBy(() -> service.putBinding(Workspaces.LOCAL, "reporter", new AgentModelService.BindingRequest(
                "workspace", null, false, a.id(), "other", null, null, null, saved.version() + 5)))
                .isInstanceOf(AgentModelService.ConflictException.class);

        assertThatThrownBy(() -> service.deleteConnection(Workspaces.LOCAL, b.id()))
                .isInstanceOf(AgentModelService.ConflictException.class)
                .hasMessageContaining("reporter");
    }

    @Test
    void schemaRejectsABindingThatNeitherInheritsNorNamesAConnection() {
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO agent_model_bindings (workspace_id, scope_type, scope_id, role_id, inherit_default)
                VALUES ('local', 'WORKSPACE', '', 'critic', FALSE)"""))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void probesAndModelCallsPersistAgainstARealProvider() throws Exception {
        try (FakeLlmServer server = new FakeLlmServer()) {
            server.respondWith(body -> new FakeLlmServer.Canned(200, TestModels.okReply("ok")));
            var c = service.createConnection(Workspaces.LOCAL, new AgentModelService.ConnectionRequest(
                    "Local", Adapter.OPENAI_COMPATIBLE, "custom", server.baseUrl() + "/v1", null, null, null, null));
            assertThat(c.keyRequired()).isFalse();

            ModelProbeService.Report report = probes.probe(Workspaces.LOCAL, c.id(), "local-m");
            assertThat(report.results()).anySatisfy(x -> {
                assertThat(x.capability()).isEqualTo(Capability.TEXT);
                assertThat(x.status()).isEqualTo(Capability.Status.VERIFIED);
            });
            Integer stored = jdbc.queryForObject("SELECT COUNT(*) FROM model_capability_probes WHERE connection_id = ?",
                    Integer.class, c.id());
            assertThat(stored).isEqualTo(2);

            service.putBinding(Workspaces.LOCAL, "critic", new AgentModelService.BindingRequest(
                    "workspace", null, false, c.id(), "local-m", null, null, null, null));
            ResolvedModelBinding r = resolver.resolve(Workspaces.LOCAL, null, AgentRole.CRITIC, null, TrustZone.PRIVATE_ONLY);
            assertThat(r.capabilities()).containsEntry(Capability.TEXT, Capability.Status.VERIFIED);

            factory.client(r, new CallContext(Workspaces.LOCAL, "run-it", "task-1", "critic")).complete("s", "u", 20);
            assertThat(calls.findTop50ByWorkspaceIdOrderByCreatedAtDesc(Workspaces.LOCAL))
                    .anySatisfy(call -> {
                        assertThat(call.getRunId()).isEqualTo("run-it");
                        assertThat(call.getModelId()).isEqualTo("local-m");
                        assertThat(call.getOutputTokens()).isEqualTo(3);
                    });
        }
    }

    @Test
    void legacyImportIsOneTimeAndCopiesTheKeyIntoTheVault() {
        legacy.update("openai", "sk-legacy-key-77776666", null, "legacy-model", true);
        AgentModelService.ImportResult res = service.importLegacy(Workspaces.LOCAL);
        assertThat(res.connection().keyHint()).isEqualTo("…6666");
        assertThat(res.defaultBinding().roleId()).isEqualTo(AgentRole.DEFAULT_KEY);
        assertThat(res.defaultBinding().modelId()).isEqualTo("legacy-model");
        assertThat(resolver.resolve(Workspaces.LOCAL, null, AgentRole.REPORTER, null, TrustZone.ANY).source())
                .isEqualTo(ResolvedModelBinding.Source.WORKSPACE_DEFAULT);

        assertThatThrownBy(() -> service.importLegacy(Workspaces.LOCAL))
                .isInstanceOf(AgentModelService.ConflictException.class);
        legacy.clearActive();
    }

    @Test
    void controllerIsGatedAndReportsTypedRoleStates() throws Exception {
        MockMvc off = MockMvcBuilders.standaloneSetup(new AgentModelController(service, probes, calls,
                AgentRuntimeFlags.OFF)).build();
        off.perform(get("/api/agents")).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("FEATURE_DISABLED"));
        off.perform(get("/api/agent-runtime/flags")).andExpect(status().isOk())
                .andExpect(jsonPath("$.perAgentModels").value(false));

        MockMvc on = MockMvcBuilders.standaloneSetup(new AgentModelController(service, probes, calls, flags)).build();
        on.perform(get("/api/agents")).andExpect(status().isOk())
                .andExpect(jsonPath("$.roles[0].role.id").value("planner"))
                .andExpect(jsonPath("$.roles[0].state").value("BLOCKED"))
                .andExpect(jsonPath("$.roles[0].errorCode").value("MODEL_BINDING_REQUIRED"));
    }
}
