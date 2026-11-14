package com.stocktracker.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.LinkedHashSet;
import java.util.regex.Pattern;

/**
 * User-chosen Copilot provider, model and API keys, set from the Settings UI
 * (PRD MC 01 — bring your own AI).
 *
 * <p>Stored as a small JSON file ({@code ananke.settings.file}, a Docker volume
 * in compose) rather than in the database: it is single-user local config, it
 * must survive a database reset, and keys never travel back to the browser —
 * only "is set" plus the last four characters.
 *
 * <p>Precedence: when the user has saved an active provider that is usable
 * (has a key, or is a keyless local server), it wins. Otherwise the copilot
 * falls back to the environment-configured provider exactly as before, so a
 * fresh install with only {@code .env} keys behaves unchanged.
 *
 * <p>Keys are stored in plain text with owner-only file permissions. That is
 * acceptable for the local single-user MVP; account-scoped secret storage is
 * PRD MC 02 (Phase 4).
 */
@Slf4j
@Service
public class LlmSettingsService {

    public enum Kind { ANTHROPIC, OPENAI_COMPATIBLE }

    /** A provider the Settings UI can offer. Base URL is editable for every OpenAI-compatible preset. */
    public record Preset(String id, String label, Kind kind, String defaultBaseUrl,
                         boolean keyRequired, List<String> suggestedModels) { }

    public static final List<Preset> PRESETS = List.of(
            new Preset("anthropic", "Anthropic", Kind.ANTHROPIC, "https://api.anthropic.com", true,
                    List.of("claude-opus-5-5", "claude-sonnet-5-5", "claude-haiku-5-5")),
            new Preset("openai", "OpenAI", Kind.OPENAI_COMPATIBLE, "https://api.openai.com/v1", true, List.of()),
            new Preset("openrouter", "OpenRouter", Kind.OPENAI_COMPATIBLE, "https://openrouter.ai/api/v1", true, List.of()),
            new Preset("groq", "Groq", Kind.OPENAI_COMPATIBLE, "https://api.groq.com/openai/v1", true, List.of()),
            new Preset("together", "Together", Kind.OPENAI_COMPATIBLE, "https://api.together.xyz/v1", true, List.of()),
            new Preset("deepseek", "DeepSeek", Kind.OPENAI_COMPATIBLE, "https://api.deepseek.com/v1", true, List.of()),
            new Preset("moonshot", "Moonshot (Kimi)", Kind.OPENAI_COMPATIBLE, "https://api.moonshot.ai/v1", true,
                    List.of("kimi-k2-0905-preview")),
            new Preset("ollama", "Ollama (local)", Kind.OPENAI_COMPATIBLE, "http://host.docker.internal:11434/v1", false, List.of()),
            new Preset("lmstudio", "LM Studio (local)", Kind.OPENAI_COMPATIBLE, "http://host.docker.internal:1234/v1", false, List.of()),
            new Preset("custom", "Custom (OpenAI-compatible)", Kind.OPENAI_COMPATIBLE, "", false, List.of())
    );

    /** Per-provider saved values. Any field may be null = "use the preset default". */
    public static final class ProviderEntry {
        public String apiKey;
        public String baseUrl;
        public String model;
    }

    /** On-disk shape. */
    public static final class Stored {
        public String activeProvider;
        public Map<String, ProviderEntry> providers = new LinkedHashMap<>();
    }

    private final Path file;
    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build();

    /**
     * Providers that aren't presets — today, deployed Packs ({@code pack:<id>}),
     * registered by PackService. Resolved per call, never cached here, so a Pack
     * that stops simply falls back to the .env provider.
     */
    public interface ExternalProviders {
        Optional<LLMClient> clientFor(String providerId);
        Optional<String> labelFor(String providerId);
    }

    private volatile ExternalProviders external;

    public void setExternalProviders(ExternalProviders external) {
        this.external = external;
    }

    static boolean isExternalId(String id) {
        return id != null && id.startsWith("pack:");
    }

    private Stored stored;
    /** Built lazily from {@link #stored}; dropped whenever settings change. */
    private LLMClient cachedClient;

    public LlmSettingsService(@Value("${ananke.settings.file:./data/llm-settings.json}") String file) {
        this.file = Path.of(file);
        this.stored = load();
    }

    public static Optional<Preset> preset(String id) {
        return PRESETS.stream().filter(p -> p.id().equals(id)).findFirst();
    }

    // ── Read ─────────────────────────────────────────────────────────────────

    public synchronized Stored snapshot() {
        return mapper.convertValue(stored, Stored.class);
    }

    public synchronized String baseUrlFor(String providerId) {
        ProviderEntry e = stored.providers.get(providerId);
        String saved = e == null ? null : blankToNull(e.baseUrl);
        return saved != null ? saved : preset(providerId).map(Preset::defaultBaseUrl).orElse("");
    }

    public synchronized String keyFor(String providerId) {
        ProviderEntry e = stored.providers.get(providerId);
        return e == null || e.apiKey == null ? "" : e.apiKey;
    }

    public synchronized String modelFor(String providerId) {
        ProviderEntry e = stored.providers.get(providerId);
        String saved = e == null ? null : blankToNull(e.model);
        if (saved != null) return saved;
        return preset(providerId).flatMap(p -> p.suggestedModels().stream().findFirst()).orElse("");
    }

    /** The user-selected client, if one is saved and usable; empty means "use the .env provider". */
    public synchronized Optional<LLMClient> activeClient() {
        if (stored.activeProvider == null) return Optional.empty();
        if (isExternalId(stored.activeProvider)) {
            ExternalProviders ext = external;
            return ext == null ? Optional.empty()
                    : ext.clientFor(stored.activeProvider).filter(LLMClient::isConfigured);
        }
        if (cachedClient == null) cachedClient = build(stored.activeProvider);
        return cachedClient != null && cachedClient.isConfigured() ? Optional.of(cachedClient) : Optional.empty();
    }

    public synchronized String activeProvider() {
        return stored.activeProvider;
    }

    // ── Write ────────────────────────────────────────────────────────────────

    /**
     * Updates one provider's entry and optionally makes it active.
     *
     * @param apiKey null = leave the saved key unchanged; "" = clear it.
     */
    public synchronized void update(String providerId, String apiKey, String baseUrl, String model, boolean activate) {
        Preset p = preset(providerId).orElseThrow(() ->
                new IllegalArgumentException("Unknown provider '" + providerId + "'"));
        if (baseUrl != null && !baseUrl.isBlank()) validateBaseUrl(baseUrl);
        if (p.id().equals("custom") && activate && (baseUrl == null || baseUrl.isBlank())
                && blankToNull(baseUrlFor("custom")) == null) {
            throw new IllegalArgumentException("A custom provider needs a base URL.");
        }

        ProviderEntry e = stored.providers.computeIfAbsent(providerId, k -> new ProviderEntry());
        if (apiKey != null) e.apiKey = apiKey.trim().isEmpty() ? null : apiKey.trim();
        if (baseUrl != null) e.baseUrl = blankToNull(baseUrl);
        if (model != null) e.model = blankToNull(model);
        if (activate) stored.activeProvider = providerId;

        cachedClient = null;
        save();
    }

    /** Makes an external provider (a deployed Pack) the copilot's provider. Caller validates it. */
    public synchronized void activateExternal(String providerId) {
        if (!isExternalId(providerId)) throw new IllegalArgumentException("Not an external provider id");
        stored.activeProvider = providerId;
        cachedClient = null;
        save();
    }

    /** Display label for the active provider when it's external, e.g. "Pack: Qwen3-8B". */
    public Optional<String> externalLabel(String providerId) {
        ExternalProviders ext = external;
        return ext == null || !isExternalId(providerId) ? Optional.empty() : ext.labelFor(providerId);
    }

    /** Stop overriding .env: the copilot goes back to the environment-configured provider. */
    public synchronized void clearActive() {
        stored.activeProvider = null;
        cachedClient = null;
        save();
    }

    // ── Model discovery ──────────────────────────────────────────────────────

    /**
     * Lists models the provider reports for the saved (or supplied) key, merged
     * with the preset's suggestions. Failures degrade to the suggestions plus an
     * error string — the UI still lets the user type a model id by hand.
     */
    public ModelList listModels(String providerId, String keyOverride, String baseUrlOverride) {
        Preset p = preset(providerId).orElseThrow(() ->
                new IllegalArgumentException("Unknown provider '" + providerId + "'"));
        String key  = keyOverride != null && !keyOverride.isBlank() ? keyOverride.trim() : keyFor(providerId);
        String base = baseUrlOverride != null && !baseUrlOverride.isBlank()
                ? baseUrlOverride.trim() : baseUrlFor(providerId);

        // Provider order is kept (Anthropic and most others list newest first).
        LinkedHashSet<String> found = new LinkedHashSet<>();
        String error = null;
        if (base.isBlank()) {
            error = "Set a base URL first.";
        } else if (p.keyRequired() && key.isBlank()) {
            error = "Add an API key to load this provider's models.";
        } else {
            try {
                validateBaseUrl(base);
                HttpRequest.Builder req = HttpRequest.newBuilder()
                        .timeout(Duration.ofSeconds(15))
                        .GET();
                if (p.kind() == Kind.ANTHROPIC) {
                    req.uri(URI.create(trimSlash(base) + "/v1/models?limit=100"))
                       .header("x-api-key", key)
                       .header("anthropic-version", "2023-06-01");
                } else {
                    req.uri(URI.create(trimSlash(base) + "/models"));
                    if (!key.isBlank()) req.header("Authorization", "Bearer " + key);
                }
                HttpResponse<String> res = http.send(req.build(), HttpResponse.BodyHandlers.ofString());
                if (res.statusCode() / 100 != 2) {
                    error = "Provider returned HTTP " + res.statusCode()
                            + (res.statusCode() == 401 || res.statusCode() == 403 ? " (check the API key)" : "");
                } else {
                    JsonNode data = mapper.readTree(res.body()).path("data");
                    for (JsonNode m : data) {
                        String id = m.path("id").asText("");
                        if (!id.isBlank() && isChatModel(providerId, id)) found.add(id);
                    }
                }
            } catch (IllegalArgumentException e) {
                error = e.getMessage();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                error = "Interrupted while contacting the provider.";
            } catch (Exception e) {
                error = "Could not reach " + base + " (" + e.getClass().getSimpleName() + ")";
            }
        }

        // A live list is authoritative; the presets are only a fallback for when it can't load.
        List<String> models = found.isEmpty() ? new ArrayList<>(p.suggestedModels()) : new ArrayList<>(found);
        return new ModelList(models, !found.isEmpty(), error);
    }

    /**
     * OpenAI's /models lists every model on the account — embeddings, speech,
     * image, moderation — and none of those can drive the copilot's chat loop.
     */
    private static final Pattern OPENAI_NON_CHAT = Pattern.compile(
            "embedding|whisper|tts|dall-e|davinci|babbage|moderation|transcribe|audio|realtime|image|search|similarity|edit|computer-use",
            Pattern.CASE_INSENSITIVE);

    static boolean isChatModel(String providerId, String id) {
        if (!"openai".equals(providerId)) return true;
        if (OPENAI_NON_CHAT.matcher(id).find()) return false;
        return id.startsWith("gpt-") || id.startsWith("chatgpt-") || id.matches("^o\\d.*");
    }

    public record ModelList(List<String> models, boolean live, String error) { }

    /** Tiny round-trip with the given settings, without saving them. */
    public String test(String providerId, String keyOverride, String baseUrlOverride, String modelOverride) {
        String key   = keyOverride != null && !keyOverride.isBlank() ? keyOverride.trim() : keyFor(providerId);
        String base  = baseUrlOverride != null && !baseUrlOverride.isBlank() ? baseUrlOverride.trim() : baseUrlFor(providerId);
        String model = modelOverride != null && !modelOverride.isBlank() ? modelOverride.trim() : modelFor(providerId);
        if (model.isBlank()) throw new IllegalArgumentException("Pick a model first.");
        validateBaseUrl(base);
        LLMClient c = build(providerId, key, base, model);
        if (c == null || !c.isConfigured()) {
            throw new LLMClient.LLMUnavailableException("This provider needs an API key.");
        }
        String reply = c.complete("Reply with exactly: ok", "ping", 16);
        return reply == null ? "" : reply.trim();
    }

    // ── Internals ────────────────────────────────────────────────────────────

    private LLMClient build(String providerId) {
        return build(providerId, keyFor(providerId), baseUrlFor(providerId), modelFor(providerId));
    }

    private static LLMClient build(String providerId, String key, String base, String model) {
        Preset p = preset(providerId).orElse(null);
        if (p == null || base == null || base.isBlank() || model == null || model.isBlank()) return null;
        return switch (p.kind()) {
            case ANTHROPIC -> new AnthropicClient(key, base, model);
            // KimiClient is the generic OpenAI-compatible client (name is historical).
            // Pass the values as the LLM_* triple and leave the legacy KIMI_* key blank
            // so its env fallback never kicks in for a user-chosen provider.
            case OPENAI_COMPATIBLE -> new KimiClient(key, base, model, "", base, model);
        };
    }

    /** Only http(s); the URL is fetched server-side, so reject anything else outright. */
    private static void validateBaseUrl(String url) {
        URI u;
        try {
            u = URI.create(url.trim());
        } catch (Exception e) {
            throw new IllegalArgumentException("Base URL is not a valid URL.");
        }
        if (u.getScheme() == null || !(u.getScheme().equals("http") || u.getScheme().equals("https"))
                || u.getHost() == null) {
            throw new IllegalArgumentException("Base URL must be an http(s) URL.");
        }
    }

    private Stored load() {
        if (!Files.exists(file)) return new Stored();
        try {
            Stored s = mapper.readValue(file.toFile(), Stored.class);
            if (s.providers == null) s.providers = new LinkedHashMap<>();
            if (s.activeProvider != null && preset(s.activeProvider).isEmpty() && !isExternalId(s.activeProvider)) {
                s.activeProvider = null;
            }
            log.info("Loaded Copilot provider settings from {} (active: {})", file,
                    s.activeProvider == null ? ".env default" : s.activeProvider);
            return s;
        } catch (IOException e) {
            log.warn("Could not read {} — starting with empty Copilot settings", file, e);
            return new Stored();
        }
    }

    private void save() {
        try {
            Path dir = file.toAbsolutePath().getParent();
            if (dir != null) Files.createDirectories(dir);
            Path tmp = Files.createTempFile(dir, "llm-settings", ".tmp");
            try {
                Files.setPosixFilePermissions(tmp, PosixFilePermissions.fromString("rw-------"));
            } catch (UnsupportedOperationException ignored) {
                // non-POSIX filesystem
            }
            mapper.writerWithDefaultPrettyPrinter().writeValue(tmp.toFile(), stored);
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new IllegalStateException("Could not save Copilot settings to " + file + ": " + e.getMessage(), e);
        }
    }

    private static String blankToNull(String s) {
        return s == null || s.trim().isEmpty() ? null : s.trim();
    }

    private static String trimSlash(String s) {
        return s.replaceAll("/+$", "");
    }
}
