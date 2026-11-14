package com.stocktracker.agent.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.stocktracker.agent.model.AgentModelBinding.Scope;
import com.stocktracker.service.FakeLlmServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.stocktracker.agent.model.TestModels.WS;
import static org.assertj.core.api.Assertions.assertThat;

/** §5.6: a tool-capable role needs a live, configuration-specific tool-call probe. */
class ModelProbeServiceTest {

    private static final Pattern NONCE = Pattern.compile("value \\\\?\"(ananke-[0-9a-f]+)\\\\?\"");

    private FakeLlmServer server;
    private TestModels.Store store;
    private ModelProbeService probes;
    private AgentModelResolver resolver;

    @BeforeEach
    void setUp() throws Exception {
        server = new FakeLlmServer();
        store = new TestModels.Store();
        store.connection("conn", Adapter.OPENAI_COMPATIBLE, server.baseUrl() + "/v1", false);
        ModelClientFactory factory = new ModelClientFactory(store, new TestModels.Credentials(), DeploymentDirectory.NONE,
                store, TestModels.MAPPER, TestModels.CLOCK);
        probes = new ModelProbeService(store, DeploymentDirectory.NONE, factory, TestModels.MAPPER, TestModels.CLOCK);
        resolver = new AgentModelResolver(store, DeploymentDirectory.NONE, TestModels.CLOCK);
    }

    @AfterEach
    void tearDown() {
        server.close();
    }

    /** Text requests get "ok"; tool requests get whatever {@code toolReply} builds from the nonce. */
    private void serve(Function<String, String> toolReply) {
        server.respondWith(body -> {
            if (!body.has("tools")) return new FakeLlmServer.Canned(200, TestModels.okReply("ok"));
            String user = body.path("messages").path(1).path("content").asText();
            Matcher m = NONCE.matcher(user);
            return new FakeLlmServer.Canned(200, toolReply.apply(m.find() ? m.group(1) : "none"));
        });
    }

    private static String toolCall(String name, String argsJson) {
        String escaped = argsJson.replace("\"", "\\\"");
        return "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":null,\"tool_calls\":[{\"id\":\"c1\","
                + "\"type\":\"function\",\"function\":{\"name\":\"" + name + "\",\"arguments\":\"" + escaped + "\"}}]},"
                + "\"finish_reason\":\"tool_calls\"}]}";
    }

    private Capability.Status toolStatus(ModelProbeService.Report r) {
        return r.results().stream().filter(x -> x.capability() == Capability.TOOL_CALLING).findFirst().orElseThrow().status();
    }

    @Test
    void exactEchoVerifiesToolCallingAndUnblocksTheBuilder() {
        serve(nonce -> toolCall(ModelProbeService.ECHO_TOOL, "{\"value\":\"" + nonce + "\"}"));
        store.bind(Scope.WORKSPACE, "", "builder", "conn", "coder");
        assertThat(store.calls()).isEmpty();

        ModelProbeService.Report report = probes.probe(WS, "conn", "coder");

        assertThat(report.results()).allSatisfy(r -> assertThat(r.status()).isEqualTo(Capability.Status.VERIFIED));
        assertThat(resolver.resolve(WS, null, AgentRole.BUILDER, null, TrustZone.ANY).capabilities())
                .containsEntry(Capability.TOOL_CALLING, Capability.Status.VERIFIED);
        // Probes are model calls too, and are recorded as such.
        assertThat(store.calls()).hasSize(2).allSatisfy(c -> assertThat(c.getRoleId()).isEqualTo("probe"));
        JsonNode toolReq = server.snapshot().get(1).body();
        assertThat(toolReq.path("tools").path(0).path("function").path("name").asText())
                .isEqualTo(ModelProbeService.ECHO_TOOL);
    }

    @Test
    void proseReplyWrongToolOrWrongArgumentsDoNotVerify() {
        serve(nonce -> TestModels.okReply("I would call the tool"));
        assertThat(toolStatus(probes.probe(WS, "conn", "m"))).isEqualTo(Capability.Status.UNVERIFIED);

        serve(nonce -> toolCall("something_else", "{\"value\":\"" + nonce + "\"}"));
        assertThat(toolStatus(probes.probe(WS, "conn", "m"))).isEqualTo(Capability.Status.UNVERIFIED);

        serve(nonce -> toolCall(ModelProbeService.ECHO_TOOL, "{\"value\":\"ananke-guessed\"}"));
        assertThat(toolStatus(probes.probe(WS, "conn", "m"))).isEqualTo(Capability.Status.UNVERIFIED);

        serve(nonce -> toolCall(ModelProbeService.ECHO_TOOL, "not json"));
        assertThat(toolStatus(probes.probe(WS, "conn", "m"))).isEqualTo(Capability.Status.UNVERIFIED);
    }

    @Test
    void failedTextProbeSkipsTheToolProbe() {
        server.respondWith(body -> new FakeLlmServer.Canned(500, "{\"error\":\"down\"}"));
        ModelProbeService.Report r = probes.probe(WS, "conn", "m");
        assertThat(r.results()).allSatisfy(x -> assertThat(x.status()).isEqualTo(Capability.Status.UNVERIFIED));
        assertThat(server.snapshot()).hasSize(1);
    }

    @Test
    void probeIsTiedToTheConnectionVersionItRanAgainst() {
        serve(nonce -> toolCall(ModelProbeService.ECHO_TOOL, "{\"value\":\"" + nonce + "\"}"));
        probes.probe(WS, "conn", "coder");
        assertThat(store.probes).allSatisfy(p -> {
            assertThat(p.getConnectionVersion()).isEqualTo(1L);
            assertThat(p.getProbeVersion()).isEqualTo(ModelProbeService.PROBE_VERSION);
        });
        store.connections.get("conn").setVersion(2L);
        store.bind(Scope.WORKSPACE, "", "builder", "conn", "coder");
        assertThat(resolver.resolveAll(WS, null, java.util.List.of(AgentRole.BUILDER), null, TrustZone.ANY).errors())
                .containsKey("builder");
    }
}
