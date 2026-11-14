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
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Generic OpenAI-compatible chat client — the class is still named for Kimi
 * (Moonshot), its first user, but {@code chat}/{@code complete} work against
 * anything speaking the OpenAI {@code /chat/completions} shape: OpenAI
 * itself, Kimi/Moonshot, Groq, Together, DeepSeek, OpenRouter, and virtually
 * every local model server (Ollama's OpenAI-compatible mode, LM Studio,
 * vLLM, llama.cpp server, text-generation-webui, koboldcpp, …). Point {@code
 * llm.base-url} at whichever one you're running and this class doesn't care.
 * Requests carry {@code messages}/{@code tools}; responses expose {@code
 * choices[0].message} plus {@code finish_reason} — see {@link LLMClient} for
 * why every provider, including non-OpenAI-shaped ones, is normalized to
 * this same shape.
 *
 * Config is {@code llm.api-key} / {@code llm.base-url} / {@code llm.model},
 * each falling back to the original {@code kimi.*} properties (and those, in
 * turn, to the {@code KIMI_*} env vars) so an existing .env keeps working
 * untouched. A local server that doesn't check its bearer token can be
 * pointed at with {@code llm.api-key} left blank — the Authorization header
 * is only sent when a key is actually configured.
 */
@Slf4j
@Service
public class KimiClient implements LLMClient {

    private static final int READ_TIMEOUT_MS    = 180_000;
    private static final int CONNECT_TIMEOUT_MS = 10_000;

    private final ObjectMapper mapper = new ObjectMapper();
    private final RestClient client;
    private final String apiKey;
    private final String model;
    /** Cloud providers need a key; a local server usually ignores whatever it's sent. */
    private final boolean apiKeyRequired;

    public KimiClient(
            @Value("${llm.api-key:}")   String llmApiKey,
            @Value("${llm.base-url:}")  String llmBaseUrl,
            @Value("${llm.model:}")     String llmModel,
            @Value("${kimi.api-key:}")  String kimiApiKey,
            @Value("${kimi.base-url:https://api.moonshot.ai/v1}") String kimiBaseUrl,
            @Value("${kimi.model:kimi-k2-0905-preview}")          String kimiModel) {
        // A blank LLM_API_KEY in .env reaches Spring as set-but-empty, so the
        // property-level ${LLM_API_KEY:${KIMI_API_KEY}} default never fires.
        // Fall back to the legacy KIMI_* triple as a unit — key, URL and model
        // together — unless the LLM_* block is deliberately keyless (local server).
        String apiKey  = blank(llmApiKey) ? "" : llmApiKey.trim();
        String baseUrl = blank(llmBaseUrl) ? kimiBaseUrl : llmBaseUrl;
        String model   = blank(llmModel)   ? kimiModel   : llmModel;
        if (apiKey.isEmpty() && !isLocalHost(baseUrl) && !blank(kimiApiKey)) {
            apiKey  = kimiApiKey.trim();
            baseUrl = kimiBaseUrl;
            model   = kimiModel;
        }
        this.apiKey = apiKey;
        this.model  = model;
        this.apiKeyRequired = !isLocalHost(baseUrl);

        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(CONNECT_TIMEOUT_MS);
        factory.setReadTimeout(READ_TIMEOUT_MS);
        this.client = RestClient.builder()
                .baseUrl(baseUrl.replaceAll("/+$", ""))
                .requestFactory(factory)
                .build();
    }

    /**
     * Controllers check this up front so a missing key is a clean 503, not a
     * stream that dies — but only when a key is actually expected. A {@code
     * llm.base-url} pointed at localhost/127.0.0.1/host.docker.internal (Ollama,
     * LM Studio, vLLM, llama.cpp server, …) is assumed to need no key, since
     * most local servers ignore the Authorization header entirely.
     */
    @Override
    public boolean isConfigured() {
        return !apiKeyRequired || !apiKey.isEmpty();
    }

    private static boolean blank(String s) {
        return s == null || s.trim().isEmpty();
    }

    /** Best-effort "this base URL is a local model server" check — see {@link #isConfigured}. */
    private static boolean isLocalHost(String baseUrl) {
        try {
            String host = java.net.URI.create(baseUrl).getHost();
            if (host == null) return false;
            String h = host.toLowerCase();
            return h.equals("localhost") || h.equals("127.0.0.1") || h.equals("::1")
                    || h.equals("host.docker.internal") || h.endsWith(".local");
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public String model() {
        return model;
    }

    /**
     * One non-streaming completion.
     *
     * @param tools OpenAI-shaped tool definitions; omitted from the payload when empty.
     * @return the raw response root — callers read {@code choices[0]}.
     */
    @Override
    public JsonNode chat(List<Map<String, Object>> messages,
                         List<Map<String, Object>> tools,
                         int maxTokens) {
        if (!isConfigured()) {
            throw new LLMUnavailableException(
                    "No API key configured for the active LLM provider (llm.api-key / LLM_API_KEY). "
                    + "Add it to the project .env and restart stock-tracker, or point llm.base-url at "
                    + "a local model server that doesn't require one.");
        }

        Map<String, Object> payload = new HashMap<>();
        payload.put("model", model);
        payload.put("messages", messages);
        payload.put("max_tokens", maxTokens);
        // Deliberately no temperature: some Kimi models (k3 on the coding
        // endpoint) reject anything but 1, so we take each model's default.
        if (tools != null && !tools.isEmpty()) {
            payload.put("tools", tools);
        }

        RestClient.RequestBodySpec req = client.post()
                .uri("/chat/completions")
                .contentType(MediaType.APPLICATION_JSON);
        if (!apiKey.isEmpty()) {
            req = req.header("Authorization", "Bearer " + apiKey);
        }

        ResponseEntity<String> response = req
                .body(payload)
                .retrieve()
                .onStatus(status -> true, (req2, res) -> { })
                .toEntity(String.class);

        String body = response.getBody() == null ? "" : response.getBody();

        if (!response.getStatusCode().is2xxSuccessful()) {
            String snippet = body.length() > 400 ? body.substring(0, 400) : body;
            throw new LLMUnavailableException(
                    "LLM request failed (" + response.getStatusCode().value() + "): " + snippet);
        }

        try {
            return mapper.readTree(body);
        } catch (Exception e) {
            throw new LLMUnavailableException("LLM provider returned unparseable JSON: " + e.getMessage());
        }
    }

    /** Convenience for a plain prompt with no tools. */
    @Override
    public String complete(String systemPrompt, String userPrompt, int maxTokens) {
        List<Map<String, Object>> messages = new ArrayList<>();
        messages.add(Map.of("role", "system", "content", systemPrompt));
        messages.add(Map.of("role", "user", "content", userPrompt));
        JsonNode root = chat(messages, List.of(), maxTokens);
        return root.path("choices").path(0).path("message").path("content").asText("");
    }

    /**
     * @deprecated superseded by {@link LLMClient.LLMUnavailableException}. Kept only
     * so any code compiled against the old name doesn't break; new code should
     * never construct this — {@link #chat} now throws the shared type.
     */
    @Deprecated
    public static class KimiUnavailableException extends LLMUnavailableException {
        public KimiUnavailableException(String message) {
            super(message);
        }
    }
}
