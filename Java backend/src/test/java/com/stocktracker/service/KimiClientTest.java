package com.stocktracker.service;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Phase 0 baseline for the OpenAI-compatible client (KimiClient): config
 * precedence (llm.* over kimi.*), keyless local servers, and verbatim request
 * pass-through. The fake server binds 127.0.0.1, which KimiClient treats as a
 * local model server (no key required).
 *
 * Constructor order: (llmApiKey, llmBaseUrl, llmModel, kimiApiKey, kimiBaseUrl, kimiModel).
 */
class KimiClientTest {

    private static final String REMOTE = "https://api.example-llm.com/v1";

    private FakeLlmServer fake;

    @BeforeEach
    void setUp() throws Exception {
        fake = new FakeLlmServer();
    }

    @AfterEach
    void tearDown() {
        fake.close();
    }

    private static Map<String, Object> m(Object... kv) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) out.put((String) kv[i], kv[i + 1]);
        return out;
    }

    private static final String OK = """
            {"choices":[{"index":0,"finish_reason":"stop",
              "message":{"role":"assistant","content":"pong"}}],"usage":{"total_tokens":3}}
            """;

    // ── precedence ─────────────────────────────────────────────────────────────

    @Test
    void llmBlockWinsOverKimiWhenKeyed() {
        KimiClient c = new KimiClient("llm-key", fake.baseUrl(), "llm-model",
                "kimi-key", REMOTE, "kimi-model");
        assertThat(c.model()).isEqualTo("llm-model");
        assertThat(c.isConfigured()).isTrue();

        fake.reply(200, OK);
        c.chat(List.of(m("role", "user", "content", "ping")), List.of(), 5);
        assertThat(fake.last().headers().getFirst("Authorization")).isEqualTo("Bearer llm-key");
        assertThat(fake.last().body().path("model").asText()).isEqualTo("llm-model");
    }

    @Test
    void blankLlmFieldsFallBackToKimiPerField() {
        // Blank llm.base-url / llm.model fall back individually to kimi.* ...
        KimiClient c = new KimiClient("llm-key", "  ", "", "kimi-key", fake.baseUrl(), "kimi-model");
        assertThat(c.model()).isEqualTo("kimi-model");
        fake.reply(200, OK);
        c.chat(List.of(m("role", "user", "content", "ping")), List.of(), 5);
        // ... but the explicit llm key still wins.
        assertThat(fake.last().headers().getFirst("Authorization")).isEqualTo("Bearer llm-key");
    }

    @Test
    void blankLlmKeyOnRemoteUrlFallsBackToKimiTripleAsAUnit() {
        // LLM_API_KEY blank (set-but-empty in .env), LLM_BASE_URL remote, KIMI_* keyed:
        // key, URL and model all switch to the kimi.* values together.
        KimiClient c = new KimiClient("", REMOTE, "llm-model", " kimi-key ", fake.baseUrl(), "kimi-model");
        assertThat(c.model()).isEqualTo("kimi-model");
        assertThat(c.isConfigured()).isTrue();

        fake.reply(200, OK);
        c.chat(List.of(m("role", "user", "content", "ping")), List.of(), 5);
        assertThat(fake.last().headers().getFirst("Authorization")).isEqualTo("Bearer kimi-key");
        assertThat(fake.last().body().path("model").asText()).isEqualTo("kimi-model");
    }

    @Test
    void blankLlmKeyOnLocalUrlDoesNotFallBackToKimi() {
        // A deliberately keyless local server keeps its own block even when KIMI_* is set.
        KimiClient c = new KimiClient("", fake.baseUrl(), "llama3", "kimi-key", REMOTE, "kimi-model");
        assertThat(c.model()).isEqualTo("llama3");
        assertThat(c.isConfigured()).isTrue();

        fake.reply(200, OK);
        c.chat(List.of(m("role", "user", "content", "ping")), List.of(), 5);
        assertThat(fake.last().headers().getFirst("Authorization")).isNull();
    }

    // ── isConfigured ──────────────────────────────────────────────────────────

    @Test
    void remoteWithoutAnyKeyIsNotConfigured() {
        KimiClient c = new KimiClient("", REMOTE, "", "", REMOTE, "kimi-model");
        assertThat(c.isConfigured()).isFalse();
        assertThatThrownBy(() -> c.chat(List.of(m("role", "user", "content", "x")), List.of(), 5))
                .isInstanceOf(LLMClient.LLMUnavailableException.class)
                .hasMessageContaining("No API key configured");
    }

    @Test
    void localHostsNeedNoKey() {
        for (String url : List.of("http://localhost:11434/v1", "http://127.0.0.1:1234/v1",
                "http://host.docker.internal:11434/v1", "http://gpu-box.local:8000/v1")) {
            KimiClient c = new KimiClient("", url, "m", "", REMOTE, "kimi-model");
            assertThat(c.isConfigured()).as(url).isTrue();
        }
        assertThat(new KimiClient("", "http://10.0.0.5:8000/v1", "m", "", REMOTE, "k").isConfigured())
                .as("LAN IPs are not treated as local").isFalse();
        // V1 quirk frozen: URI.getHost() keeps IPv6 brackets ("[::1]"), so the
        // h.equals("::1") branch never matches and IPv6 loopback still needs a key.
        assertThat(new KimiClient("", "http://[::1]:8000/v1", "m", "", REMOTE, "k").isConfigured()).isFalse();
    }

    @Test
    void blankKeyLocalServerSendsNoAuthorizationHeader() {
        KimiClient c = new KimiClient("", fake.baseUrl() + "/", "local-model", "", REMOTE, "kimi-model");
        fake.reply(200, OK);
        c.chat(List.of(m("role", "user", "content", "ping")), List.of(), 5);
        assertThat(fake.last().headers().getFirst("Authorization")).isNull();
        // Trailing slash stripped; path appended.
        assertThat(fake.last().path()).isEqualTo("/chat/completions");
    }

    // ── pass-through ──────────────────────────────────────────────────────────

    @Test
    void requestAndResponsePassThroughVerbatim() {
        KimiClient c = new KimiClient("k", fake.baseUrl(), "gpt-test", "", REMOTE, "kimi-model");
        String toolCallResponse = """
                {"choices":[{"index":0,"finish_reason":"tool_calls",
                  "message":{"role":"assistant","content":null,
                    "tool_calls":[{"id":"call_9","type":"function",
                      "function":{"name":"list_symbols","arguments":"{}"}}]}}]}
                """;
        fake.reply(200, toolCallResponse);

        List<Map<String, Object>> messages = List.of(
                m("role", "system", "content", "sys"),
                m("role", "user", "content", "hi"),
                m("role", "assistant", "content", "",
                  "tool_calls", List.of(m("id", "c0", "type", "function",
                          "function", m("name", "list_symbols", "arguments", "{}")))),
                m("role", "tool", "tool_call_id", "c0", "content", "[]"));
        List<Map<String, Object>> tools = List.of(m("type", "function",
                "function", m("name", "list_symbols", "description", "d",
                        "parameters", m("type", "object", "properties", m()))));

        JsonNode root = c.chat(messages, tools, 321);

        JsonNode body = fake.last().body();
        assertThat(body.path("model").asText()).isEqualTo("gpt-test");
        assertThat(body.path("max_tokens").asInt()).isEqualTo(321);
        // No temperature: some Kimi models reject anything but the default.
        assertThat(body.has("temperature")).isFalse();
        assertThat(body.path("messages")).hasSize(4);
        assertThat(body.path("messages").get(0).path("role").asText()).isEqualTo("system");
        assertThat(body.path("messages").get(2).path("tool_calls").get(0).path("id").asText()).isEqualTo("c0");
        assertThat(body.path("messages").get(3).path("tool_call_id").asText()).isEqualTo("c0");
        assertThat(body.path("tools").get(0).path("function").path("parameters").path("type").asText())
                .isEqualTo("object");

        // The response is returned as-is.
        assertThat(root.path("choices").get(0).path("finish_reason").asText()).isEqualTo("tool_calls");
        assertThat(root.path("choices").get(0).path("message").path("tool_calls").get(0)
                .path("function").path("name").asText()).isEqualTo("list_symbols");
    }

    @Test
    void emptyToolsAreOmittedAndCompleteReturnsContent() {
        KimiClient c = new KimiClient("k", fake.baseUrl(), "m", "", REMOTE, "kimi-model");
        fake.reply(200, OK);
        assertThat(c.complete("sys", "user", 9)).isEqualTo("pong");
        JsonNode body = fake.last().body();
        assertThat(body.has("tools")).isFalse();
        assertThat(body.path("messages").get(0).path("role").asText()).isEqualTo("system");
        assertThat(body.path("messages").get(1).path("content").asText()).isEqualTo("user");
    }

    @Test
    void non2xxAndBadJsonBecomeLlmUnavailable() {
        KimiClient c = new KimiClient("k", fake.baseUrl(), "m", "", REMOTE, "kimi-model");
        fake.reply(500, "{\"error\":\"upstream down\"}");
        assertThatThrownBy(() -> c.complete("s", "u", 5))
                .isInstanceOf(LLMClient.LLMUnavailableException.class)
                .hasMessage("LLM request failed (500): {\"error\":\"upstream down\"}");

        fake.reply(200, "not json at all");
        assertThatThrownBy(() -> c.complete("s", "u", 5))
                .isInstanceOf(LLMClient.LLMUnavailableException.class)
                .hasMessageStartingWith("LLM provider returned unparseable JSON");
    }

    @Test
    void unauthorized401EscapesAsRestClientException() {
        // V1 quirk frozen: HttpURLConnection (SimpleClientHttpRequestFactory) in
        // streaming mode throws HttpRetryException on a 401, so a bad key does NOT
        // become LLMUnavailableException — it surfaces as a RestClientException
        // (CopilotService turns it into an SSE error event, not a 503).
        KimiClient c = new KimiClient("k", fake.baseUrl(), "m", "", REMOTE, "kimi-model");
        fake.reply(401, "{\"error\":\"bad key\"}");
        assertThatThrownBy(() -> c.complete("s", "u", 5))
                .isInstanceOf(org.springframework.web.client.RestClientException.class)
                .isNotInstanceOf(LLMClient.LLMUnavailableException.class);
    }
}
