package com.stocktracker.service;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/**
 * Wires the {@link LLMClient} the copilot uses (single global config — see
 * AGENTS.md/CLAUDE.md; Ananke has no user/tenant model yet, so there is one
 * provider preference process-wide).
 *
 * {@code llm.provider} names the <em>preferred</em> provider, not the only one:
 * the returned {@link ResolvingLLMClient} uses it when its key is present and
 * otherwise falls back to whichever provider is configured. Both concrete
 * clients are ordinary {@code @Service} beans — constructing one just builds an
 * HTTP client, no network call — so the switch costs nothing. When Ananke
 * eventually gets real user accounts, this is the one class that changes:
 * replace the env-read here with a per-request/per-tenant resolver and nothing
 * in CopilotService or CopilotController needs to move.
 *
 * The {@code @Primary} client is {@link SettingsAwareLLMClient}: a provider and
 * model chosen in the Settings UI take precedence, and the env-configured
 * {@code llmClient} below is the fallback when nothing usable is saved there.
 */
@Configuration
public class LlmProviderConfig {

    @Bean
    @Primary
    public SettingsAwareLLMClient copilotLlmClient(
            LlmSettingsService settings,
            @Qualifier("llmClient") LLMClient envClient) {
        return new SettingsAwareLLMClient(settings, envClient);
    }

    /** The environment-configured provider ({@code .env}); the fallback for {@link #copilotLlmClient}. */
    @Bean
    public LLMClient llmClient(
            @Value("${llm.provider:openai_compatible}") String provider,
            KimiClient openAiCompatibleClient,
            AnthropicClient anthropicClient) {
        String normalized = provider == null ? "" : provider.trim().toLowerCase();
        return switch (normalized) {
            case "anthropic" -> new ResolvingLLMClient(anthropicClient, openAiCompatibleClient);
            case "openai_compatible", "openai", "kimi", "" -> new ResolvingLLMClient(openAiCompatibleClient, anthropicClient);
            default -> throw new IllegalStateException(
                    "Unknown llm.provider '" + provider + "' — expected 'openai_compatible' or 'anthropic'");
        };
    }
}
