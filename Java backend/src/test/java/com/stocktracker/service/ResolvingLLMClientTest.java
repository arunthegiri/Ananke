package com.stocktracker.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Phase 0 baseline: preferred → fallback → none resolution, evaluated per call. */
class ResolvingLLMClientTest {

    /** Hand-rolled stub so the test pins behaviour, not a mocking library. */
    static final class StubClient implements LLMClient {
        final String name;
        boolean configured;
        int chats;
        int completes;

        StubClient(String name, boolean configured) {
            this.name = name;
            this.configured = configured;
        }

        @Override public boolean isConfigured() { return configured; }
        @Override public String model() { return name + "-model"; }

        @Override
        public JsonNode chat(List<Map<String, Object>> messages, List<Map<String, Object>> tools, int maxTokens) {
            chats++;
            return JsonNodeFactory.instance.objectNode().put("from", name);
        }

        @Override
        public String complete(String systemPrompt, String userPrompt, int maxTokens) {
            completes++;
            return name;
        }
    }

    @Test
    void usesPreferredWhenConfigured() {
        StubClient pref = new StubClient("pref", true);
        StubClient fb = new StubClient("fb", true);
        ResolvingLLMClient r = new ResolvingLLMClient(pref, fb);

        assertThat(r.isConfigured()).isTrue();
        assertThat(r.model()).isEqualTo("pref-model");
        assertThat(r.chat(List.of(), List.of(), 1).path("from").asText()).isEqualTo("pref");
        assertThat(r.complete("s", "u", 1)).isEqualTo("pref");
        assertThat(fb.chats + fb.completes).isZero();
    }

    @Test
    void fallsBackWhenPreferredHasNoCredentials() {
        StubClient pref = new StubClient("pref", false);
        StubClient fb = new StubClient("fb", true);
        ResolvingLLMClient r = new ResolvingLLMClient(pref, fb);

        assertThat(r.isConfigured()).isTrue();
        assertThat(r.model()).isEqualTo("fb-model");
        assertThat(r.complete("s", "u", 1)).isEqualTo("fb");
        assertThat(r.chat(List.of(), List.of(), 1).path("from").asText()).isEqualTo("fb");
        assertThat(pref.chats + pref.completes).isZero();
    }

    @Test
    void noneConfiguredThrowsLlmUnavailable() {
        ResolvingLLMClient r = new ResolvingLLMClient(new StubClient("a", false), new StubClient("b", false));

        assertThat(r.isConfigured()).isFalse();
        assertThat(r.model()).isEqualTo("none");
        assertThatThrownBy(() -> r.chat(List.of(), List.of(), 1))
                .isInstanceOf(LLMClient.LLMUnavailableException.class)
                .hasMessageContaining("no LLM provider has credentials");
        assertThatThrownBy(() -> r.complete("s", "u", 1))
                .isInstanceOf(LLMClient.LLMUnavailableException.class);
    }

    @Test
    void resolutionIsReEvaluatedOnEveryCall() {
        StubClient pref = new StubClient("pref", false);
        StubClient fb = new StubClient("fb", true);
        ResolvingLLMClient r = new ResolvingLLMClient(pref, fb);

        assertThat(r.complete("s", "u", 1)).isEqualTo("fb");
        pref.configured = true;
        assertThat(r.complete("s", "u", 1)).isEqualTo("pref");
    }
}
