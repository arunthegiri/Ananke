package com.stocktracker.service;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Settings persistence, provider activation and live model discovery — no real network. */
class LlmSettingsServiceTest {

    @TempDir Path dir;

    private HttpServer server;
    private final AtomicReference<String> body = new AtomicReference<>("{\"data\":[]}");
    private final AtomicReference<Integer> status = new AtomicReference<>(200);
    private final AtomicReference<String> lastAuth = new AtomicReference<>();
    private final AtomicReference<String> lastPath = new AtomicReference<>();

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            lastPath.set(ex.getRequestURI().getPath());
            String auth = ex.getRequestHeaders().getFirst("Authorization");
            lastAuth.set(auth != null ? auth : ex.getRequestHeaders().getFirst("x-api-key"));
            byte[] out = body.get().getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(status.get(), out.length);
            ex.getResponseBody().write(out);
            ex.close();
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private String base() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private LlmSettingsService service() {
        return new LlmSettingsService(dir.resolve("llm-settings.json").toString());
    }

    @Test
    void noActiveProviderMeansEnvFallback() {
        assertThat(service().activeClient()).isEmpty();
    }

    @Test
    void savedProviderBecomesActiveAndSurvivesRestart() throws Exception {
        LlmSettingsService s = service();
        s.update("anthropic", "sk-ant-test-key-1234", null, "claude-x", true);

        assertThat(s.activeClient()).get().isInstanceOf(AnthropicClient.class);
        assertThat(s.activeClient().get().model()).isEqualTo("claude-x");

        LlmSettingsService reloaded = service();
        assertThat(reloaded.activeProvider()).isEqualTo("anthropic");
        assertThat(reloaded.keyFor("anthropic")).isEqualTo("sk-ant-test-key-1234");
        assertThat(Files.getPosixFilePermissions(dir.resolve("llm-settings.json")).toString())
                .isEqualTo("[OWNER_READ, OWNER_WRITE]");
    }

    @Test
    void nullKeyKeepsSavedKeyAndBlankClearsIt() {
        LlmSettingsService s = service();
        s.update("openai", "sk-proj-abc", null, "gpt-x", true);
        s.update("openai", null, null, "gpt-y", true);
        assertThat(s.keyFor("openai")).isEqualTo("sk-proj-abc");
        assertThat(s.activeClient().get().model()).isEqualTo("gpt-y");

        s.update("openai", "", null, null, false);
        assertThat(s.keyFor("openai")).isEmpty();
        assertThat(s.activeClient()).isEmpty(); // key required, so no longer usable
    }

    @Test
    void clearActiveReturnsToEnv() {
        LlmSettingsService s = service();
        s.update("groq", "gsk_x", null, "llama", true);
        s.clearActive();
        assertThat(s.activeClient()).isEmpty();
    }

    @Test
    void rejectsUnknownProviderAndNonHttpBaseUrl() {
        LlmSettingsService s = service();
        assertThatThrownBy(() -> s.update("nope", "k", null, null, true))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> s.update("custom", null, "file:///etc/passwd", "m", true))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void openAiModelListIsFilteredToChatModelsAndKeepsProviderOrder() {
        body.set("{\"data\":[{\"id\":\"gpt-5\"},{\"id\":\"text-embedding-3-large\"},{\"id\":\"o4\"},"
                + "{\"id\":\"whisper-1\"},{\"id\":\"gpt-5-mini\"},{\"id\":\"dall-e-3\"},"
                + "{\"id\":\"gpt-4o-mini-tts\"},{\"id\":\"chatgpt-4o-latest\"}]}");
        LlmSettingsService.ModelList list = service().listModels("openai", "sk-proj-abc", base());

        assertThat(list.live()).isTrue();
        assertThat(list.models()).containsExactly("gpt-5", "o4", "gpt-5-mini", "chatgpt-4o-latest");
        assertThat(lastPath.get()).isEqualTo("/models");
        assertThat(lastAuth.get()).isEqualTo("Bearer sk-proj-abc");
    }

    @Test
    void anthropicListUsesItsOwnEndpointAndHeaderAndReplacesPresets() {
        body.set("{\"data\":[{\"id\":\"claude-newest\"},{\"id\":\"claude-older\"}]}");
        LlmSettingsService.ModelList list = service().listModels("anthropic", "sk-ant-k", base());

        assertThat(list.models()).containsExactly("claude-newest", "claude-older");
        assertThat(lastPath.get()).isEqualTo("/v1/models");
        assertThat(lastAuth.get()).isEqualTo("sk-ant-k");
    }

    @Test
    void badKeyFallsBackToPresetsWithAnError() {
        status.set(401);
        body.set("{\"error\":\"bad\"}");
        LlmSettingsService.ModelList list = service().listModels("anthropic", "sk-ant-bad", base());

        assertThat(list.live()).isFalse();
        assertThat(list.error()).contains("401").contains("check the API key");
        assertThat(list.models()).isNotEmpty(); // preset suggestions
    }

    @Test
    void missingKeyIsReportedWithoutANetworkCall() {
        LlmSettingsService.ModelList list = service().listModels("openai", null, base());
        assertThat(list.error()).contains("Add an API key");
        assertThat(lastPath.get()).isNull();
    }

    @Test
    void externalPackProviderIsResolvedPerCallAndSurvivesReload() {
        LlmSettingsService s = service();
        KimiClient packClient = new KimiClient("ak", base() + "/v1", "pack-3", "", base() + "/v1", "pack-3");
        java.util.concurrent.atomic.AtomicBoolean running = new java.util.concurrent.atomic.AtomicBoolean(true);
        LlmSettingsService.ExternalProviders ext = new LlmSettingsService.ExternalProviders() {
            public java.util.Optional<LLMClient> clientFor(String id) {
                return "pack:3".equals(id) && running.get() ? java.util.Optional.of(packClient) : java.util.Optional.empty();
            }
            public java.util.Optional<String> labelFor(String id) { return java.util.Optional.of("Pack: Qwen"); }
        };
        s.setExternalProviders(ext);
        s.activateExternal("pack:3");
        assertThat(s.activeClient()).containsSame(packClient);

        // A stopped Pack falls back to .env instead of breaking the copilot.
        running.set(false);
        assertThat(s.activeClient()).isEmpty();

        // The pack: id is kept across a restart (presets-only validation must not drop it).
        LlmSettingsService reloaded = service();
        assertThat(reloaded.activeProvider()).isEqualTo("pack:3");
        assertThatThrownBy(() -> s.activateExternal("openai")).isInstanceOf(IllegalArgumentException.class);
    }
}
