package com.stocktracker.controller;

import com.stocktracker.service.LLMClient;
import com.stocktracker.service.LlmSettingsService;
import com.stocktracker.service.SettingsAwareLLMClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Copilot provider / model / API-key settings for the Settings dialog and the
 * Copilot model picker. API keys are write-only: responses report whether a
 * key is saved and its last four characters, never the key itself.
 */
@RestController
@RequestMapping("/api/settings/llm")
public class LlmSettingsController {

    private final LlmSettingsService settings;
    private final SettingsAwareLLMClient copilotClient;
    private final LLMClient envClient;

    public LlmSettingsController(LlmSettingsService settings,
                                 SettingsAwareLLMClient copilotClient,
                                 @Qualifier("llmClient") LLMClient envClient) {
        this.settings      = settings;
        this.copilotClient = copilotClient;
        this.envClient     = envClient;
    }

    public record ProviderView(String id, String label, String kind, boolean keyRequired,
                               String defaultBaseUrl, String baseUrl, String model,
                               List<String> suggestedModels, boolean keySet, String keyHint) { }

    public record SettingsView(String activeProvider, String effectiveSource, boolean configured,
                               String effectiveModel, String envModel, boolean envConfigured,
                               List<ProviderView> providers) { }

    public record UpdateRequest(String provider, String apiKey, String baseUrl, String model, Boolean activate) { }

    public record ProbeRequest(String provider, String apiKey, String baseUrl, String model) { }

    @GetMapping
    public SettingsView get() {
        return view();
    }

    @PutMapping
    public SettingsView update(@RequestBody UpdateRequest req) {
        if (req == null || req.provider() == null || req.provider().isBlank()) {
            throw new IllegalArgumentException("provider is required");
        }
        settings.update(req.provider().trim(), req.apiKey(), req.baseUrl(), req.model(),
                req.activate() == null || req.activate());
        return view();
    }

    /** Go back to the provider configured in .env. */
    @DeleteMapping("/active")
    public SettingsView useEnvDefault() {
        settings.clearActive();
        return view();
    }

    @PostMapping("/models")
    public LlmSettingsService.ModelList models(@RequestBody ProbeRequest req) {
        return settings.listModels(req.provider(), req.apiKey(), req.baseUrl());
    }

    @GetMapping("/models")
    public LlmSettingsService.ModelList savedModels(@RequestParam String provider) {
        return settings.listModels(provider, null, null);
    }

    /** Sends a one-word prompt with the given (unsaved) settings. */
    @PostMapping("/test")
    public Map<String, Object> test(@RequestBody ProbeRequest req) {
        Map<String, Object> out = new LinkedHashMap<>();
        try {
            String reply = settings.test(req.provider(), req.apiKey(), req.baseUrl(), req.model());
            out.put("ok", true);
            out.put("reply", reply.length() > 80 ? reply.substring(0, 80) : reply);
        } catch (IllegalArgumentException | LLMClient.LLMUnavailableException e) {
            out.put("ok", false);
            out.put("error", e.getMessage());
        } catch (Exception e) {
            out.put("ok", false);
            out.put("error", "Request failed: " + rootMessage(e));
        }
        return out;
    }

    private SettingsView view() {
        LlmSettingsService.Stored s = settings.snapshot();
        List<ProviderView> providers = new ArrayList<>();
        for (LlmSettingsService.Preset p : LlmSettingsService.PRESETS) {
            String key = settings.keyFor(p.id());
            providers.add(new ProviderView(
                    p.id(), p.label(), p.kind().name().toLowerCase(), p.keyRequired(),
                    p.defaultBaseUrl(), settings.baseUrlFor(p.id()), settings.modelFor(p.id()),
                    p.suggestedModels(), !key.isEmpty(),
                    key.length() >= 8 ? "…" + key.substring(key.length() - 4) : (key.isEmpty() ? null : "…")));
        }
        boolean configured = copilotClient.isConfigured();
        return new SettingsView(
                s.activeProvider,
                copilotClient.source(),
                configured,
                configured ? copilotClient.model() : null,
                envClient.isConfigured() ? envClient.model() : null,
                envClient.isConfigured(),
                providers);
    }

    private static String rootMessage(Throwable e) {
        Throwable t = e;
        while (t.getCause() != null && t.getCause() != t) t = t.getCause();
        String m = t.getMessage();
        return m == null ? t.getClass().getSimpleName() : (m.length() > 300 ? m.substring(0, 300) : m);
    }
}
