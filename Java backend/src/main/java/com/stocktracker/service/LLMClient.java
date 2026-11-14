package com.stocktracker.service;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;
import java.util.Map;

/**
 * Provider-agnostic seam for the copilot's LLM calls (build doc M4 follow-up:
 * bring-your-own-key + local-model support).
 *
 * {@link CopilotService}'s tool loop is written once against the OpenAI
 * chat-completions wire shape — {@code choices[0].message}, {@code
 * finish_reason}, {@code tool_calls[].function.{name,arguments}} — because
 * that shape is what {@link OpenAiCompatibleClient}'s underlying APIs already
 * speak natively. Every implementation of this interface, regardless of the
 * provider actually being called, must return {@link #chat} results in that
 * same shape. A provider whose wire format differs (Anthropic's content
 * blocks, for instance) translates in both directions at its own boundary —
 * see {@link AnthropicClient} — so the tool loop, the SSE framing and the
 * frontend never need to know which provider answered.
 *
 * Which implementation is active is decided once, process-wide, by {@link
 * LlmProviderConfig} reading {@code llm.provider}. There is no per-request or
 * per-user resolution yet — Ananke has no account/auth model to key it on —
 * but every external call goes through this interface rather than a concrete
 * client, so adding a per-tenant resolver later is a new {@code @Bean}, not a
 * rewrite of {@link CopilotService}.
 */
public interface LLMClient {

    /** Controllers check this up front so a missing/unset provider is a clean 503. */
    boolean isConfigured();

    /** The model name this provider will call — surfaced for logging/UI, not used for routing. */
    String model();

    /**
     * One non-streaming completion, always returned in OpenAI chat-completion
     * shape regardless of the underlying provider.
     *
     * @param messages OpenAI-shaped chat messages ({@code role}/{@code content},
     *                  plus {@code tool_calls} on assistant turns and {@code
     *                  tool_call_id} on tool-result turns).
     * @param tools     OpenAI-shaped tool definitions ({@code {type:"function",
     *                  function:{name, description, parameters}}}); omitted from
     *                  the call when empty.
     * @return a JsonNode shaped like an OpenAI response — callers read
     *         {@code choices[0].message} and {@code choices[0].finish_reason}.
     */
    JsonNode chat(List<Map<String, Object>> messages, List<Map<String, Object>> tools, int maxTokens);

    /** Convenience for a plain prompt with no tools (used for the post-backtest explanation). */
    String complete(String systemPrompt, String userPrompt, int maxTokens);

    /** Signals "the configured AI provider is unusable right now" — surfaced to callers as 503. */
    class LLMUnavailableException extends RuntimeException {
        public LLMUnavailableException(String message) {
            super(message);
        }
    }
}
