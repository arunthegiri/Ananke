package com.stocktracker.controller;

import com.stocktracker.service.CopilotService;
import com.stocktracker.service.LLMClient;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Phase 0 baseline: with no configured LLM provider, both copilot endpoints
 * answer a plain JSON 503 (via GlobalExceptionHandler) before any stream opens.
 */
class CopilotControllerTest {

    static final class Unconfigured implements LLMClient {
        @Override public boolean isConfigured() { return false; }
        @Override public String model() { return "none"; }
        @Override public JsonNode chat(List<Map<String, Object>> m, List<Map<String, Object>> t, int n) {
            throw new AssertionError("must not be called");
        }
        @Override public String complete(String s, String u, int n) {
            throw new AssertionError("must not be called");
        }
    }

    private final MockMvc mvc = MockMvcBuilders
            // CopilotService is never reached: requireCopilot() throws first.
            .standaloneSetup(new CopilotController((CopilotService) null, new Unconfigured()))
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();

    @Test
    void researchReturns503WhenNoProviderConfigured() throws Exception {
        mvc.perform(post("/api/copilot/research")
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.TEXT_EVENT_STREAM, MediaType.APPLICATION_JSON)
                        .content("{\"prompt\":\"build an RSI strategy\"}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.status").value(503))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString(
                        "no LLM provider has credentials")));
    }

    @Test
    void chatReturns503WhenNoProviderConfigured() throws Exception {
        mvc.perform(post("/api/copilot/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.TEXT_EVENT_STREAM, MediaType.APPLICATION_JSON)
                        .content("{\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.status").value(503));
    }
}
