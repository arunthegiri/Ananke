package com.stocktracker.agent.model;

import com.stocktracker.agent.model.AgentModelBinding.Scope;
import com.stocktracker.agent.model.ModelResolutionException.Code;
import com.stocktracker.service.FakeLlmServer;
import com.stocktracker.service.KimiClient;
import com.stocktracker.service.LlmSettingsService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static com.stocktracker.agent.model.TestModels.WS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Role-scoped clients against in-process fake providers: the §5.7 acceptance scenario plus
 * the §21 rows for same-provider collisions, revoked credentials, Pack outage and fallback.
 */
class ScopedModelClientTest {

    private FakeLlmServer hosted;
    private FakeLlmServer packServer;
    private TestModels.Store store;
    private TestModels.Credentials creds;
    private TestModels.Deployments pack;
    private AgentModelResolver resolver;
    private ModelClientFactory factory;

    @BeforeEach
    void setUp() throws Exception {
        hosted = new FakeLlmServer().respondWith(body -> new FakeLlmServer.Canned(200,
                TestModels.okReply("model=" + body.path("model").asText())));
        packServer = new FakeLlmServer().respondWith(body -> new FakeLlmServer.Canned(200, TestModels.okReply("pack")));

        store = new TestModels.Store();
        creds = new TestModels.Credentials();
        store.connection("conn-hosted", Adapter.OPENAI_COMPATIBLE, hosted.baseUrl() + "/v1", true);
        creds.put("model-conn:conn-hosted", ModelConnection.SECRET_KIND, "sk-hosted-key-0001");
        store.connection("conn-pack", Adapter.PACK, "pack:7", false);

        String packBase = packServer.baseUrl() + "/v1";
        pack = new TestModels.Deployments("pack:7", "qwen-served",
                new KimiClient("pack-api-key", packBase, "qwen-served", "", packBase, "qwen-served"));
        resolver = new AgentModelResolver(store, pack, TestModels.CLOCK);
        factory = new ModelClientFactory(store, creds, pack, store, TestModels.MAPPER, TestModels.CLOCK);
    }

    @AfterEach
    void tearDown() {
        hosted.close();
        packServer.close();
    }

    private ScopedModelClient clientFor(AgentRole role) {
        ResolvedModelBinding b = resolver.resolve(WS, null, role, null, TrustZone.ANY);
        return factory.client(b, new CallContext(WS, "run-1", "task-" + role.id(), role.id()));
    }

    /**
     * Spec §5.7 acceptance: two concurrent agents on different models of the same provider,
     * a third on a Pack, while the legacy global Settings provider is changed underneath
     * them. Every call must keep its own binding.
     */
    @Test
    void concurrentRolesKeepTheirBindingsWhileLegacySettingsChange(@TempDir Path dir) throws Exception {
        store.bind(Scope.WORKSPACE, "", "planner", "conn-hosted", "planner-model");
        store.bind(Scope.WORKSPACE, "", "builder", "conn-hosted", "builder-model");
        store.bind(Scope.WORKSPACE, "", "critic", "conn-pack", null);
        store.verify("conn-hosted", "planner-model", null, Capability.TEXT, Capability.TOOL_CALLING);
        store.verify("conn-hosted", "builder-model", null, Capability.TEXT, Capability.TOOL_CALLING);

        ScopedModelClient planner = clientFor(AgentRole.PLANNER);
        ScopedModelClient builder = clientFor(AgentRole.BUILDER);
        ScopedModelClient critic = clientFor(AgentRole.CRITIC);

        LlmSettingsService legacy = new LlmSettingsService(dir.resolve("llm-settings.json").toString());
        AtomicBoolean stop = new AtomicBoolean();
        ExecutorService pool = Executors.newFixedThreadPool(4);
        CountDownLatch start = new CountDownLatch(1);
        Future<?> flipper = pool.submit(() -> {
            start.await();
            int i = 0;
            while (!stop.get()) {
                legacy.update(i++ % 2 == 0 ? "openai" : "anthropic", "legacy-key", hosted.baseUrl() + "/v1",
                        "legacy-global-model-" + i, true);
            }
            return null;
        });

        int perRole = 20;
        List<Future<List<String>>> runs = new ArrayList<>();
        for (ScopedModelClient c : List.of(planner, builder, critic)) {
            runs.add(pool.submit(() -> {
                start.await();
                List<String> replies = new ArrayList<>();
                for (int i = 0; i < perRole; i++) replies.add(c.complete("sys", "role=" + c.binding().roleId(), 50));
                return replies;
            }));
        }
        start.countDown();
        List<String> plannerReplies = runs.get(0).get(30, TimeUnit.SECONDS);
        List<String> builderReplies = runs.get(1).get(30, TimeUnit.SECONDS);
        List<String> criticReplies = runs.get(2).get(30, TimeUnit.SECONDS);
        stop.set(true);
        flipper.get(10, TimeUnit.SECONDS);
        pool.shutdownNow();

        assertThat(plannerReplies).hasSize(perRole).containsOnly("model=planner-model");
        assertThat(builderReplies).hasSize(perRole).containsOnly("model=builder-model");
        assertThat(criticReplies).hasSize(perRole).containsOnly("pack");
        assertThat(legacy.activeProvider()).isIn("openai", "anthropic"); // the legacy setting really moved

        // Every hosted request carried the model of the role that sent it.
        for (FakeLlmServer.Recorded r : hosted.snapshot()) {
            String role = r.body().path("messages").path(1).path("content").asText();
            String model = r.body().path("model").asText();
            assertThat(model).isEqualTo(role.equals("role=planner") ? "planner-model" : "builder-model");
        }
        assertThat(packServer.snapshot()).hasSize(perRole)
                .allSatisfy(r -> assertThat(r.body().path("model").asText()).isEqualTo("qwen-served"));

        // Lineage: one record per call with the exact binding and run context.
        List<ModelCallRecord> calls = store.calls();
        assertThat(calls).hasSize(3 * perRole).allSatisfy(c -> {
            assertThat(c.getOutcome()).isEqualTo(ModelCallRecord.Outcome.OK);
            assertThat(c.getRunId()).isEqualTo("run-1");
            assertThat(c.getTaskId()).isEqualTo("task-" + c.getRoleId());
            assertThat(c.getInputTokens()).isEqualTo(11);
            assertThat(c.getInputManifestSha256()).hasSize(64);
            assertThat(c.getFallbackFrom()).isNull();
        });
        assertThat(calls).filteredOn(c -> c.getRoleId().equals("critic"))
                .allSatisfy(c -> assertThat(c.getDeploymentRevision()).isEqualTo("rev-a/000000000000"));
    }

    /** §5.7 / §21: a stopped private Pack must never push the call to a hosted provider. */
    @Test
    void stoppedPackFailsClosedWithoutTouchingHostedProviders() {
        store.bind(Scope.WORKSPACE, "", "critic", "conn-pack", null);
        store.bind(Scope.WORKSPACE, "", AgentRole.DEFAULT_KEY, "conn-hosted", "hosted-default");
        ScopedModelClient critic = clientFor(AgentRole.CRITIC);

        pack.running = false;
        assertThatThrownBy(() -> critic.complete("sys", "confidential thesis", 50))
                .isInstanceOf(ModelResolutionException.class)
                .satisfies(e -> assertThat(((ModelResolutionException) e).code()).isEqualTo(Code.DEPLOYMENT_UNAVAILABLE))
                .hasMessageContaining("offline");
        assertThat(critic.isConfigured()).isFalse();
        assertThat(hosted.snapshot()).isEmpty();
        assertThat(packServer.snapshot()).isEmpty();
    }

    @Test
    void redeployedPackIsNotSilentlySwappedIntoARunningBinding() {
        store.bind(Scope.WORKSPACE, "", "critic", "conn-pack", null);
        ScopedModelClient critic = clientFor(AgentRole.CRITIC);
        pack.revision = "rev-b/111111111111";
        assertThatThrownBy(() -> critic.complete("s", "u", 10))
                .satisfies(e -> assertThat(((ModelResolutionException) e).code()).isEqualTo(Code.DEPLOYMENT_CHANGED));
        assertThat(packServer.snapshot()).isEmpty();
    }

    @Test
    void explicitFallbackIsUsedAndRecordedWithWhatItReplaced() throws Exception {
        try (FakeLlmServer broken = new FakeLlmServer()) {
            broken.respondWith(body -> new FakeLlmServer.Canned(503, "{\"error\":\"overloaded\"}"));
            store.connection("conn-broken", Adapter.OPENAI_COMPATIBLE, broken.baseUrl() + "/v1", false);
            BindingSpec spec = BindingSpec.of("conn-broken", "primary-m")
                    .withFallback(FallbackPolicy.explicit(new FallbackPolicy.Target("conn-hosted", "backup-m")));
            ResolvedModelBinding b = resolver.resolve(WS, null, AgentRole.REPORTER, spec, TrustZone.ANY);

            String reply = factory.client(b, CallContext.adHoc(WS, "reporter")).complete("s", "u", 10);

            assertThat(reply).isEqualTo("model=backup-m");
            List<ModelCallRecord> calls = store.calls();
            assertThat(calls).hasSize(2);
            assertThat(calls.get(0).getOutcome()).isEqualTo(ModelCallRecord.Outcome.FAILED);
            assertThat(calls.get(0).getModelId()).isEqualTo("primary-m");
            assertThat(calls.get(0).getErrorDetail()).contains("503");
            assertThat(calls.get(1).getOutcome()).isEqualTo(ModelCallRecord.Outcome.OK);
            assertThat(calls.get(1).getModelId()).isEqualTo("backup-m");
            assertThat(calls.get(1).getFallbackFrom()).isEqualTo("conn-broken/primary-m");
        }
    }

    @Test
    void withoutFallbackAProviderErrorFailsWithTheReasonAndIsRecorded() throws Exception {
        try (FakeLlmServer broken = new FakeLlmServer()) {
            // The provider echoes the key back in its error body; it must not reach storage or the caller.
            // 403, not 401: the JDK transport under KimiClient turns a 401 into an I/O error without
            // the status (recorded in the M0 baseline as a retry-classification gap).
            broken.respondWith(body -> new FakeLlmServer.Canned(403,
                    "{\"error\":\"invalid key sk-broken-secret-999\"}"));
            store.connection("conn-broken", Adapter.OPENAI_COMPATIBLE, broken.baseUrl() + "/v1", true);
            creds.put("model-conn:conn-broken", ModelConnection.SECRET_KIND, "sk-broken-secret-999");
            ResolvedModelBinding b = resolver.resolve(WS, null, AgentRole.REPORTER,
                    BindingSpec.of("conn-broken", "m"), TrustZone.ANY);

            assertThatThrownBy(() -> factory.client(b, CallContext.adHoc(WS, "reporter")).complete("s", "u", 10))
                    .isInstanceOf(ModelResolutionException.class)
                    .hasMessageContaining("fallback disabled")
                    .hasMessageNotContaining("sk-broken-secret-999");
            assertThat(store.calls()).singleElement().satisfies(c -> {
                assertThat(c.getOutcome()).isEqualTo(ModelCallRecord.Outcome.FAILED);
                assertThat(c.getErrorDetail()).contains("403").doesNotContain("sk-broken-secret-999");
            });
        }
    }

    /** §21: two connections with the same provider label never share a client or credential. */
    @Test
    void twoConnectionsToTheSameProviderUseTheirOwnCredentials() {
        store.connection("conn-second", Adapter.OPENAI_COMPATIBLE, hosted.baseUrl() + "/v1", true);
        creds.put("model-conn:conn-second", ModelConnection.SECRET_KIND, "sk-second-key-0002");

        for (String conn : List.of("conn-hosted", "conn-second")) {
            ResolvedModelBinding b = resolver.resolve(WS, null, AgentRole.REPORTER, BindingSpec.of(conn, "same-model"),
                    TrustZone.ANY);
            factory.client(b, CallContext.adHoc(WS, "reporter")).complete("s", "u", 10);
        }
        List<FakeLlmServer.Recorded> reqs = hosted.snapshot();
        assertThat(reqs).hasSize(2);
        assertThat(reqs.get(0).headers().getFirst("Authorization")).isEqualTo("Bearer sk-hosted-key-0001");
        assertThat(reqs.get(1).headers().getFirst("Authorization")).isEqualTo("Bearer sk-second-key-0002");
    }

    /** §21: a revoked credential stops the next call; no stale cached client bypasses it. */
    @Test
    void revokedAndRotatedCredentialsApplyToTheNextCall() {
        store.bind(Scope.WORKSPACE, "", "reporter", "conn-hosted", "m");
        ScopedModelClient reporter = clientFor(AgentRole.REPORTER);
        reporter.complete("s", "u", 10);

        creds.put("model-conn:conn-hosted", ModelConnection.SECRET_KIND, "sk-rotated-key-0003");
        reporter.complete("s", "u", 10);
        assertThat(hosted.last().headers().getFirst("Authorization")).isEqualTo("Bearer sk-rotated-key-0003");

        creds.delete("model-conn:conn-hosted", ModelConnection.SECRET_KIND);
        assertThatThrownBy(() -> reporter.complete("s", "u", 10))
                .satisfies(e -> assertThat(((ModelResolutionException) e).code()).isEqualTo(Code.CREDENTIAL_REVOKED));
        assertThat(hosted.snapshot()).hasSize(2);
    }

    @Test
    void editedConnectionRequiresARebindInsteadOfDriftingMidRun() {
        store.bind(Scope.WORKSPACE, "", "reporter", "conn-hosted", "m");
        ScopedModelClient reporter = clientFor(AgentRole.REPORTER);
        store.connections.get("conn-hosted").setVersion(2L);
        assertThatThrownBy(() -> reporter.complete("s", "u", 10))
                .satisfies(e -> assertThat(((ModelResolutionException) e).code()).isEqualTo(Code.CONNECTION_CHANGED));
    }

    @Test
    void bindingOutputLimitCapsTheRequest() {
        store.bind(Scope.WORKSPACE, "", "reporter", "conn-hosted", "m").setMaxOutputTokens(100);
        ScopedModelClient reporter = clientFor(AgentRole.REPORTER);
        reporter.chat(new ArrayList<>(List.of(Map.of("role", "user", "content", "hi"))), List.of(), 4000);
        assertThat(hosted.last().body().path("max_tokens").asInt()).isEqualTo(100);
        assertThat(reporter.model()).isEqualTo("m");
    }

    @Test
    void identicalInputsHashIdenticallyAndDifferentInputsDoNot() {
        store.bind(Scope.WORKSPACE, "", "reporter", "conn-hosted", "m");
        ScopedModelClient reporter = clientFor(AgentRole.REPORTER);
        reporter.complete("s", "u", 10);
        reporter.complete("s", "u", 10);
        reporter.complete("s", "different", 10);
        List<ModelCallRecord> calls = store.calls();
        assertThat(calls.get(0).getInputManifestSha256()).isEqualTo(calls.get(1).getInputManifestSha256());
        assertThat(calls.get(2).getInputManifestSha256()).isNotEqualTo(calls.get(0).getInputManifestSha256());
    }
}
