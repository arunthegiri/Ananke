package com.stocktracker.service;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The {@link LLMClient} the rest of the backend talks to. Resolves the active
 * provider on every call: the one named by {@code llm.provider} when it has
 * credentials, otherwise any provider that does. Keys are read once at
 * startup, so adding or removing one takes effect on the next container
 * restart — no image rebuild, no code change, and a blank key for the preferred
 * provider degrades to the other rather than taking the copilot down.
 */
@Slf4j
public final class ResolvingLLMClient implements LLMClient {

    private final LLMClient preferred;
    private final LLMClient fallback;
    private final AtomicReference<LLMClient> lastLogged = new AtomicReference<>();

    ResolvingLLMClient(LLMClient preferred, LLMClient fallback) {
        this.preferred = preferred;
        this.fallback  = fallback;
    }

    private Optional<LLMClient> active() {
        LLMClient pick = preferred.isConfigured() ? preferred
                       : fallback.isConfigured()  ? fallback
                       : null;
        if (pick != null && lastLogged.getAndSet(pick) != pick) {
            log.info("Copilot LLM provider: {} ({}){}", pick.getClass().getSimpleName(), pick.model(),
                    pick == preferred ? "" : " — preferred provider has no credentials, using fallback");
        }
        return Optional.ofNullable(pick);
    }

    private LLMClient require() {
        return active().orElseThrow(() -> new LLMUnavailableException(
                "Copilot is unavailable: no LLM provider has credentials. Set ANTHROPIC_API_KEY "
                + "(anthropic) or LLM_API_KEY / KIMI_API_KEY (openai_compatible) in the project .env "
                + "and restart stock-tracker."));
    }

    @Override
    public boolean isConfigured() {
        return active().isPresent();
    }

    @Override
    public String model() {
        return active().map(LLMClient::model).orElse("none");
    }

    @Override
    public JsonNode chat(List<Map<String, Object>> messages, List<Map<String, Object>> tools, int maxTokens) {
        return require().chat(messages, tools, maxTokens);
    }

    @Override
    public String complete(String systemPrompt, String userPrompt, int maxTokens) {
        return require().complete(systemPrompt, userPrompt, maxTokens);
    }
}
