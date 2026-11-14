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
 * Phase 0 baseline: freezes AnthropicClient's OpenAI ⇄ Anthropic translation
 * against a fake Messages API (JDK HttpServer, no network).
 */
class AnthropicClientTest {

    private FakeLlmServer fake;
    private AnthropicClient client;

    @BeforeEach
    void setUp() throws Exception {
        fake = new FakeLlmServer();
        // Trailing slashes are stripped from the base URL.
        client = new AnthropicClient("  sk-ant-test  ", fake.baseUrl() + "/", "claude-test-model");
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

    private static final String TEXT_RESPONSE = """
            {"id":"msg_1","type":"message","role":"assistant",
             "content":[{"type":"text","text":"Hello "},{"type":"text","text":"world"}],
             "stop_reason":"end_turn"}
            """;

    @Test
    void configuredOnlyWithKey() {
        assertThat(client.isConfigured()).isTrue();
        assertThat(client.model()).isEqualTo("claude-test-model");
        assertThat(new AnthropicClient("   ", fake.baseUrl(), "m").isConfigured()).isFalse();
        assertThat(new AnthropicClient(null, fake.baseUrl(), "m").isConfigured()).isFalse();
    }

    @Test
    void unconfiguredChatThrowsWithoutCallingNetwork() {
        AnthropicClient noKey = new AnthropicClient("", fake.baseUrl(), "m");
        assertThatThrownBy(() -> noKey.chat(List.of(m("role", "user", "content", "hi")), List.of(), 10))
                .isInstanceOf(LLMClient.LLMUnavailableException.class)
                .hasMessageContaining("ANTHROPIC_API_KEY is not set");
        assertThat(fake.requests).isEmpty();
    }

    @Test
    void requestTranslation_systemToolCallsToolResultsAndTools() {
        fake.reply(200, TEXT_RESPONSE);

        List<Map<String, Object>> messages = List.of(
                m("role", "system", "content", "SYS ONE"),
                m("role", "system", "content", "SYS TWO"),
                m("role", "user", "content", "build me a strategy"),
                m("role", "assistant", "content", "Let me check.",
                  "tool_calls", List.of(
                          m("id", "call_1", "type", "function",
                            "function", m("name", "get_price_data",
                                          "arguments", "{\"symbol\":\"NVDA\",\"from\":\"2024-01-01\"}")),
                          m("id", "call_2", "type", "function",
                            "function", m("name", "list_symbols", "arguments", "not json")))),
                m("role", "tool", "tool_call_id", "call_1", "content", "{\"bar_count\":10}"),
                m("role", "tool", "tool_call_id", "call_2", "content", "[]"),
                m("role", "user", "content", "go on"));

        List<Map<String, Object>> tools = List.of(
                m("type", "function", "function", m(
                        "name", "get_price_data",
                        "description", "Summarise prices",
                        "parameters", m("type", "object", "properties", m("symbol", m("type", "string"))))));

        client.chat(messages, tools, 1234);

        FakeLlmServer.Recorded req = fake.last();
        assertThat(req.path()).isEqualTo("/v1/messages");
        assertThat(req.headers().getFirst("x-api-key")).isEqualTo("sk-ant-test");
        assertThat(req.headers().getFirst("anthropic-version")).isEqualTo("2023-06-01");
        assertThat(req.headers().getFirst("Authorization")).isNull();

        JsonNode body = req.body();
        assertThat(body.path("model").asText()).isEqualTo("claude-test-model");
        assertThat(body.path("max_tokens").asInt()).isEqualTo(1234);
        // 1. system turns → top-level system string, joined by a blank line.
        assertThat(body.path("system").asText()).isEqualTo("SYS ONE\n\nSYS TWO");

        JsonNode msgs = body.path("messages");
        assertThat(msgs).hasSize(4);
        assertThat(msgs.get(0).path("role").asText()).isEqualTo("user");
        assertThat(msgs.get(0).path("content").asText()).isEqualTo("build me a strategy");

        // 2. assistant tool_calls → text block + tool_use blocks with parsed input.
        JsonNode asst = msgs.get(1);
        assertThat(asst.path("role").asText()).isEqualTo("assistant");
        assertThat(asst.path("content")).hasSize(3);
        assertThat(asst.path("content").get(0).path("type").asText()).isEqualTo("text");
        assertThat(asst.path("content").get(0).path("text").asText()).isEqualTo("Let me check.");
        JsonNode use1 = asst.path("content").get(1);
        assertThat(use1.path("type").asText()).isEqualTo("tool_use");
        assertThat(use1.path("id").asText()).isEqualTo("call_1");
        assertThat(use1.path("name").asText()).isEqualTo("get_price_data");
        assertThat(use1.path("input").path("symbol").asText()).isEqualTo("NVDA");
        assertThat(use1.path("input").path("from").asText()).isEqualTo("2024-01-01");
        // Unparseable arguments degrade to an empty input object.
        JsonNode use2 = asst.path("content").get(2);
        assertThat(use2.path("id").asText()).isEqualTo("call_2");
        assertThat(use2.path("input").isObject()).isTrue();
        assertThat(use2.path("input")).isEmpty();

        // 3. consecutive tool results → ONE user turn of tool_result blocks.
        JsonNode results = msgs.get(2);
        assertThat(results.path("role").asText()).isEqualTo("user");
        assertThat(results.path("content")).hasSize(2);
        assertThat(results.path("content").get(0).path("type").asText()).isEqualTo("tool_result");
        assertThat(results.path("content").get(0).path("tool_use_id").asText()).isEqualTo("call_1");
        assertThat(results.path("content").get(0).path("content").asText()).isEqualTo("{\"bar_count\":10}");
        assertThat(results.path("content").get(1).path("tool_use_id").asText()).isEqualTo("call_2");

        assertThat(msgs.get(3).path("content").asText()).isEqualTo("go on");

        // 4. tools → {name, description, input_schema}.
        JsonNode tool = body.path("tools").get(0);
        assertThat(tool.path("name").asText()).isEqualTo("get_price_data");
        assertThat(tool.path("description").asText()).isEqualTo("Summarise prices");
        assertThat(tool.path("input_schema").path("type").asText()).isEqualTo("object");
        assertThat(tool.has("parameters")).isFalse();
        assertThat(tool.has("type")).isFalse();
    }

    @Test
    void trailingToolResultsAreFlushedAndEmptyToolsOmitted() {
        fake.reply(200, TEXT_RESPONSE);
        client.chat(List.of(
                m("role", "user", "content", "q"),
                m("role", "assistant", "content", "",
                  "tool_calls", List.of(m("id", "c1", "type", "function",
                          "function", m("name", "list_symbols", "arguments", "{}")))),
                m("role", "tool", "tool_call_id", "c1", "content", "[]")), List.of(), 10);

        JsonNode body = fake.last().body();
        assertThat(body.has("system")).isFalse();
        assertThat(body.has("tools")).isFalse();
        JsonNode msgs = body.path("messages");
        assertThat(msgs).hasSize(3);
        // Blank assistant text is dropped — only the tool_use block remains.
        assertThat(msgs.get(1).path("content")).hasSize(1);
        assertThat(msgs.get(1).path("content").get(0).path("type").asText()).isEqualTo("tool_use");
        assertThat(msgs.get(2).path("role").asText()).isEqualTo("user");
        assertThat(msgs.get(2).path("content").get(0).path("type").asText()).isEqualTo("tool_result");
    }

    @Test
    void responseTranslation_textEndTurnBecomesStop() {
        fake.reply(200, TEXT_RESPONSE);
        JsonNode root = client.chat(List.of(m("role", "user", "content", "hi")), List.of(), 10);

        JsonNode choice = root.path("choices").get(0);
        assertThat(root.path("choices")).hasSize(1);
        assertThat(choice.path("finish_reason").asText()).isEqualTo("stop");
        assertThat(choice.path("message").path("role").asText()).isEqualTo("assistant");
        assertThat(choice.path("message").path("content").asText()).isEqualTo("Hello world");
        assertThat(choice.path("message").has("tool_calls")).isFalse();
    }

    @Test
    void responseTranslation_toolUseBecomesToolCalls() {
        fake.reply(200, """
                {"content":[
                   {"type":"text","text":"Checking data."},
                   {"type":"tool_use","id":"toolu_1","name":"get_price_data",
                    "input":{"symbol":"NVDA","from":"2024-01-01","to":"2024-02-01"}}],
                 "stop_reason":"tool_use"}
                """);
        JsonNode root = client.chat(List.of(m("role", "user", "content", "hi")), List.of(), 10);

        JsonNode choice = root.path("choices").get(0);
        assertThat(choice.path("finish_reason").asText()).isEqualTo("tool_calls");
        JsonNode msg = choice.path("message");
        assertThat(msg.path("content").asText()).isEqualTo("Checking data.");
        JsonNode call = msg.path("tool_calls").get(0);
        assertThat(call.path("id").asText()).isEqualTo("toolu_1");
        assertThat(call.path("type").asText()).isEqualTo("function");
        assertThat(call.path("function").path("name").asText()).isEqualTo("get_price_data");
        // arguments is a JSON *string*, as in OpenAI's shape.
        assertThat(call.path("function").path("arguments").isTextual()).isTrue();
        assertThat(call.path("function").path("arguments").asText())
                .isEqualTo("{\"symbol\":\"NVDA\",\"from\":\"2024-01-01\",\"to\":\"2024-02-01\"}");
    }

    @Test
    void responseTranslation_otherStopReasonsCollapseToStop() {
        // V1 limitation frozen here: max_tokens is reported as "stop", not "length".
        fake.reply(200, "{\"content\":[{\"type\":\"text\",\"text\":\"trunc\"}],\"stop_reason\":\"max_tokens\"}");
        JsonNode root = client.chat(List.of(m("role", "user", "content", "hi")), List.of(), 10);
        assertThat(root.path("choices").get(0).path("finish_reason").asText()).isEqualTo("stop");
    }

    @Test
    void completeSendsSystemAndUserAndReturnsText() {
        fake.reply(200, TEXT_RESPONSE);
        String out = client.complete("be terse", "explain", 77);

        assertThat(out).isEqualTo("Hello world");
        JsonNode body = fake.last().body();
        assertThat(body.path("system").asText()).isEqualTo("be terse");
        assertThat(body.path("messages")).hasSize(1);
        assertThat(body.path("messages").get(0).path("role").asText()).isEqualTo("user");
        assertThat(body.path("max_tokens").asInt()).isEqualTo(77);
        assertThat(body.has("tools")).isFalse();
    }

    @Test
    void non2xxBecomesLlmUnavailable() {
        fake.reply(429, "{\"type\":\"error\",\"error\":{\"type\":\"rate_limit_error\"}}");
        assertThatThrownBy(() -> client.chat(List.of(m("role", "user", "content", "hi")), List.of(), 10))
                .isInstanceOf(LLMClient.LLMUnavailableException.class)
                .hasMessageStartingWith("Anthropic request failed (429): ")
                .hasMessageContaining("rate_limit_error");
    }

    @Test
    void unparseableBodyBecomesLlmUnavailable() {
        fake.reply(200, "<html>not json");
        assertThatThrownBy(() -> client.chat(List.of(m("role", "user", "content", "hi")), List.of(), 10))
                .isInstanceOf(LLMClient.LLMUnavailableException.class)
                .hasMessageStartingWith("Anthropic returned unparseable JSON");
    }
}
