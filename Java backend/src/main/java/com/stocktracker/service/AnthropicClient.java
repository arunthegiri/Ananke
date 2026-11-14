package com.stocktracker.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Anthropic Messages API client, translating to and from the OpenAI
 * chat-completion shape that {@link CopilotService}'s tool loop is written
 * against (see {@link LLMClient}).
 *
 * Anthropic's wire format differs from OpenAI's in three ways that matter
 * here, and all three are handled at this boundary so nothing above it needs
 * to change:
 *   1. No system role in the messages array — system content is a separate
 *      top-level {@code system} string, so any {@code role:"system"} turns
 *      are pulled out and concatenated.
 *   2. Tool calls and tool results are content blocks, not separate message
 *      roles — an assistant turn with tool calls becomes {@code tool_use}
 *      blocks, and a {@code role:"tool"} result becomes a {@code tool_result}
 *      block inside a user-role message (consecutive tool results are merged
 *      into one user message, since Anthropic expects all results for one
 *      assistant turn together).
 *   3. The response is {@code content: [...]} blocks with a {@code
 *      stop_reason}, not {@code choices[0].message}/{@code finish_reason} —
 *      translated back into the latter shape so it round-trips through
 *      {@link CopilotService}'s existing tool loop unchanged.
 */
@Slf4j
@Service
public class AnthropicClient implements LLMClient {

    private static final int READ_TIMEOUT_MS    = 180_000;
    private static final int CONNECT_TIMEOUT_MS = 10_000;
    private static final String ANTHROPIC_VERSION = "2023-06-01";

    private final ObjectMapper mapper = new ObjectMapper();
    private final RestClient client;
    private final String apiKey;
    private final String model;

    public AnthropicClient(
            @Value("${anthropic.api-key:}")                         String apiKey,
            @Value("${anthropic.base-url:https://api.anthropic.com}") String baseUrl,
            @Value("${anthropic.model:claude-sonnet-4-5}")           String model) {
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.model  = model;

        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(CONNECT_TIMEOUT_MS);
        factory.setReadTimeout(READ_TIMEOUT_MS);
        this.client = RestClient.builder()
                .baseUrl(baseUrl.replaceAll("/+$", ""))
                .requestFactory(factory)
                .build();
    }

    @Override
    public boolean isConfigured() {
        return !apiKey.isEmpty();
    }

    @Override
    public String model() {
        return model;
    }

    @Override
    public JsonNode chat(List<Map<String, Object>> messages, List<Map<String, Object>> tools, int maxTokens) {
        if (!isConfigured()) {
            throw new LLMUnavailableException(
                    "ANTHROPIC_API_KEY is not set. Add it to the project .env and restart stock-tracker.");
        }

        Translated translated = toAnthropicMessages(messages);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("model", model);
        payload.put("max_tokens", maxTokens);
        if (!translated.system().isBlank()) {
            payload.put("system", translated.system());
        }
        payload.put("messages", translated.messages());
        if (tools != null && !tools.isEmpty()) {
            payload.put("tools", toAnthropicTools(tools));
        }

        ResponseEntity<String> response = client.post()
                .uri("/v1/messages")
                .contentType(MediaType.APPLICATION_JSON)
                .header("x-api-key", apiKey)
                .header("anthropic-version", ANTHROPIC_VERSION)
                .body(payload)
                .retrieve()
                .onStatus(status -> true, (req, res) -> { })
                .toEntity(String.class);

        String body = response.getBody() == null ? "" : response.getBody();

        if (!response.getStatusCode().is2xxSuccessful()) {
            String snippet = body.length() > 400 ? body.substring(0, 400) : body;
            throw new LLMUnavailableException(
                    "Anthropic request failed (" + response.getStatusCode().value() + "): " + snippet);
        }

        JsonNode anthropicResponse;
        try {
            anthropicResponse = mapper.readTree(body);
        } catch (Exception e) {
            throw new LLMUnavailableException("Anthropic returned unparseable JSON: " + e.getMessage());
        }

        return toOpenAiShape(anthropicResponse);
    }

    @Override
    public String complete(String systemPrompt, String userPrompt, int maxTokens) {
        List<Map<String, Object>> messages = new ArrayList<>();
        messages.add(Map.of("role", "system", "content", systemPrompt));
        messages.add(Map.of("role", "user", "content", userPrompt));
        JsonNode root = chat(messages, List.of(), maxTokens);
        return root.path("choices").path(0).path("message").path("content").asText("");
    }

    // ── OpenAI → Anthropic (request) ────────────────────────────────────────

    private record Translated(String system, List<Map<String, Object>> messages) { }

    @SuppressWarnings("unchecked")
    private Translated toAnthropicMessages(List<Map<String, Object>> messages) {
        StringBuilder system = new StringBuilder();
        List<Map<String, Object>> out = new ArrayList<>();
        List<Map<String, Object>> pendingToolResults = new ArrayList<>();

        for (Map<String, Object> m : messages) {
            String role = String.valueOf(m.get("role"));

            if ("system".equals(role)) {
                if (system.length() > 0) system.append("\n\n");
                system.append(String.valueOf(m.getOrDefault("content", "")));
                continue;
            }

            if ("tool".equals(role)) {
                Map<String, Object> block = new LinkedHashMap<>();
                block.put("type", "tool_result");
                block.put("tool_use_id", m.get("tool_call_id"));
                block.put("content", String.valueOf(m.getOrDefault("content", "")));
                pendingToolResults.add(block);
                continue;
            }

            // Any non-tool message flushes whatever tool results were queued —
            // they must land in the user turn that follows the tool_use turn.
            flushToolResults(out, pendingToolResults);

            if ("assistant".equals(role) && m.get("tool_calls") instanceof List<?> rawCalls) {
                List<Object> content = new ArrayList<>();
                Object text = m.get("content");
                if (text instanceof String s && !s.isBlank()) {
                    content.add(Map.of("type", "text", "text", s));
                }
                for (Object rawCall : rawCalls) {
                    Map<String, Object> call = (Map<String, Object>) rawCall;
                    Map<String, Object> fn = (Map<String, Object>) call.get("function");
                    Object input;
                    try {
                        input = mapper.readValue(String.valueOf(fn.get("arguments")), Map.class);
                    } catch (Exception e) {
                        input = Map.of();
                    }
                    Map<String, Object> block = new LinkedHashMap<>();
                    block.put("type", "tool_use");
                    block.put("id", call.get("id"));
                    block.put("name", fn.get("name"));
                    block.put("input", input);
                    content.add(block);
                }
                Map<String, Object> assistantMsg = new LinkedHashMap<>();
                assistantMsg.put("role", "assistant");
                assistantMsg.put("content", content);
                out.add(assistantMsg);
                continue;
            }

            Map<String, Object> plain = new LinkedHashMap<>();
            plain.put("role", role);
            plain.put("content", String.valueOf(m.getOrDefault("content", "")));
            out.add(plain);
        }

        flushToolResults(out, pendingToolResults);
        return new Translated(system.toString(), out);
    }

    private void flushToolResults(List<Map<String, Object>> out, List<Map<String, Object>> pending) {
        if (pending.isEmpty()) return;
        Map<String, Object> userMsg = new LinkedHashMap<>();
        userMsg.put("role", "user");
        userMsg.put("content", new ArrayList<>(pending));
        out.add(userMsg);
        pending.clear();
    }

    private List<Map<String, Object>> toAnthropicTools(List<Map<String, Object>> openAiTools) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> t : openAiTools) {
            @SuppressWarnings("unchecked")
            Map<String, Object> fn = (Map<String, Object>) t.get("function");
            Map<String, Object> tool = new LinkedHashMap<>();
            tool.put("name", fn.get("name"));
            tool.put("description", fn.get("description"));
            tool.put("input_schema", fn.get("parameters"));
            out.add(tool);
        }
        return out;
    }

    // ── Anthropic → OpenAI (response) ───────────────────────────────────────

    private JsonNode toOpenAiShape(JsonNode anthropic) {
        StringBuilder text = new StringBuilder();
        List<Map<String, Object>> toolCalls = new ArrayList<>();

        for (JsonNode block : anthropic.path("content")) {
            String type = block.path("type").asText("");
            if ("text".equals(type)) {
                text.append(block.path("text").asText(""));
            } else if ("tool_use".equals(type)) {
                Map<String, Object> function = new LinkedHashMap<>();
                function.put("name", block.path("name").asText(""));
                function.put("arguments", writeJson(block.path("input")));

                Map<String, Object> call = new LinkedHashMap<>();
                call.put("id", block.path("id").asText(""));
                call.put("type", "function");
                call.put("function", function);
                toolCalls.add(call);
            }
        }

        boolean isToolCall = "tool_use".equals(anthropic.path("stop_reason").asText("")) || !toolCalls.isEmpty();

        Map<String, Object> message = new LinkedHashMap<>();
        message.put("role", "assistant");
        message.put("content", text.toString());
        if (isToolCall) {
            message.put("tool_calls", toolCalls);
        }

        Map<String, Object> choice = new LinkedHashMap<>();
        choice.put("finish_reason", isToolCall ? "tool_calls" : "stop");
        choice.put("message", message);

        Map<String, Object> root = new LinkedHashMap<>();
        root.put("choices", List.of(choice));

        return mapper.valueToTree(root);
    }

    private String writeJson(Object o) {
        try {
            return mapper.writeValueAsString(o);
        } catch (Exception e) {
            return "{}";
        }
    }
}
