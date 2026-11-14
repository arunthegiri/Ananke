package com.stocktracker.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Phase 0 baseline: llm.provider string → which concrete client is preferred.
 * Real clients are built with dummy keys and unroutable remote URLs; nothing
 * here makes a network call (selection is observed through model()).
 */
class LlmProviderConfigTest {

    private static final String REMOTE = "https://api.example-llm.com/v1";

    private final LlmProviderConfig config = new LlmProviderConfig();

    private static KimiClient openAi(String key) {
        return new KimiClient(key, REMOTE, "oa-model", "", REMOTE, "kimi-model");
    }

    private static AnthropicClient anthropic(String key) {
        return new AnthropicClient(key, "https://anthropic.invalid", "claude-model");
    }

    @Test
    void anthropicPrefersAnthropic() {
        LLMClient c = config.llmClient("anthropic", openAi("k"), anthropic("k"));
        assertThat(c).isInstanceOf(ResolvingLLMClient.class);
        assertThat(c.model()).isEqualTo("claude-model");
    }

    @Test
    void openAiCompatibleAliasesPreferOpenAiCompatible() {
        for (String p : new String[] {"openai_compatible", "openai", "kimi", "", "  OpenAI_Compatible  ", null}) {
            LLMClient c = config.llmClient(p, openAi("k"), anthropic("k"));
            assertThat(c.model()).as("provider=%s", p).isEqualTo("oa-model");
        }
    }

    @Test
    void preferredWithoutKeyFallsBackToOther() {
        assertThat(config.llmClient("anthropic", openAi("k"), anthropic("")).model()).isEqualTo("oa-model");
        assertThat(config.llmClient("openai_compatible", openAi(""), anthropic("k")).model()).isEqualTo("claude-model");
    }

    @Test
    void noKeysAnywhereIsUnconfigured() {
        LLMClient c = config.llmClient("anthropic", openAi(""), anthropic(""));
        assertThat(c.isConfigured()).isFalse();
        assertThatThrownBy(() -> c.complete("s", "u", 1))
                .isInstanceOf(LLMClient.LLMUnavailableException.class);
    }

    @Test
    void unknownProviderThrowsAtStartup() {
        assertThatThrownBy(() -> config.llmClient("gemini", openAi("k"), anthropic("k")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Unknown llm.provider 'gemini'");
    }
}
