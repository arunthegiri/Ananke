package com.stocktracker.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stocktracker.service.LLMClient;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The OpenAI-shaped tool loop every Ananke agent runs over {@link LLMClient} (§9.2):
 * call the model, and while it answers {@code finish_reason:"tool_calls"}, run each call
 * through the {@link AgentToolbox} and feed the results back as {@code role:"tool"} turns.
 * Provider-agnostic by construction — it never uses a vendor's native skills/tools feature.
 */
public final class AgentToolLoop {

    /** Called before each tool runs (progress UI, transcripts). */
    @FunctionalInterface
    public interface Listener {
        void onToolCall(String toolName, JsonNode args, String statusText);

        Listener NONE = (n, a, s) -> { };
    }

    private AgentToolLoop() { }

    /** Drives tool calls until the model answers, returning that final text ("" if it never does). */
    public static String run(LLMClient llm, ObjectMapper mapper, List<Map<String, Object>> messages,
                             AgentToolbox tools, int maxIterations, int maxTokens, Listener listener) {
        for (int i = 0; i < maxIterations; i++) {
            JsonNode root    = llm.chat(messages, tools.definitions(), maxTokens);
            JsonNode choice  = root.path("choices").path(0);
            JsonNode message = choice.path("message");

            if (!"tool_calls".equals(choice.path("finish_reason").asText())) {
                return message.path("content").asText("");
            }

            // Echo the assistant turn back verbatim so tool_call_ids line up.
            @SuppressWarnings("unchecked")
            Map<String, Object> assistant = mapper.convertValue(message, Map.class);
            messages.add(assistant);

            for (JsonNode call : message.path("tool_calls")) {
                String toolName = call.path("function").path("name").asText();
                String callId   = call.path("id").asText();
                Object result;
                try {
                    JsonNode args = mapper.readTree(call.path("function").path("arguments").asText("{}"));
                    listener.onToolCall(toolName, args, tools.statusText(toolName, args));
                    result = tools.dispatch(toolName, args);
                } catch (Exception e) {
                    result = Map.of("error", rootMessage(e));
                }

                Map<String, Object> toolMsg = new LinkedHashMap<>();
                toolMsg.put("role", "tool");
                toolMsg.put("tool_call_id", callId);
                toolMsg.put("content", writeJson(mapper, result));
                messages.add(toolMsg);
            }
        }
        return "";
    }

    private static String writeJson(ObjectMapper mapper, Object o) {
        try {
            return mapper.writeValueAsString(o);
        } catch (Exception e) {
            return "{\"error\":\"serialisation failed\"}";
        }
    }

    static String rootMessage(Throwable e) {
        Throwable t = e;
        while (t.getCause() != null && t.getCause() != t) t = t.getCause();
        String m = t.getMessage();
        return m == null || m.isBlank() ? t.getClass().getSimpleName() : m;
    }
}
