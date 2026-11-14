package com.stocktracker.service;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;
import java.util.Map;

/**
 * The {@link LLMClient} the copilot actually talks to: the provider/model the
 * user picked in Settings when that choice is usable, otherwise the
 * environment-configured provider ({@link ResolvingLLMClient}). Resolved on
 * every call, so a change in Settings applies to the next request with no
 * restart.
 */
public final class SettingsAwareLLMClient implements LLMClient {

    private final LlmSettingsService settings;
    private final LLMClient envClient;

    public SettingsAwareLLMClient(LlmSettingsService settings, LLMClient envClient) {
        this.settings  = settings;
        this.envClient = envClient;
    }

    private LLMClient active() {
        return settings.activeClient().orElse(envClient);
    }

    /** "settings" when the user's choice is in effect, "env" otherwise. */
    public String source() {
        return settings.activeClient().isPresent() ? "settings" : "env";
    }

    @Override
    public boolean isConfigured() {
        return active().isConfigured();
    }

    @Override
    public String model() {
        return active().model();
    }

    @Override
    public JsonNode chat(List<Map<String, Object>> messages, List<Map<String, Object>> tools, int maxTokens) {
        return active().chat(messages, tools, maxTokens);
    }

    @Override
    public String complete(String systemPrompt, String userPrompt, int maxTokens) {
        return active().complete(systemPrompt, userPrompt, maxTokens);
    }
}
