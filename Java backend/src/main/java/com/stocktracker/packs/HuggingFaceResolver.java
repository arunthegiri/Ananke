package com.stocktracker.packs;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.stocktracker.packs.PackException.Code.*;

/**
 * Resolves a Hugging Face model URL or repo id to a pinned commit and a
 * {@link ModelProfile}. Read-only HTTPS calls to the Hub; the optional token is
 * sent only as a bearer header and never logged or echoed.
 */
@Service
public class HuggingFaceResolver {

    static final Pattern REPO_ID = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._-]{0,95}/[A-Za-z0-9][A-Za-z0-9._-]{0,95}$");
    static final Pattern SHA = Pattern.compile("^[0-9a-f]{40}$");
    private static final Pattern REVISION = Pattern.compile("^[A-Za-z0-9._/-]{1,100}$");
    private static final Pattern HF_URL = Pattern.compile(
            "^https?://(?:www\\.)?(?:huggingface\\.co|hf\\.co)/([^/?#]+/[^/?#]+)(?:/(?:tree|blob|resolve)/([^/?#]+))?.*$");

    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NORMAL).build();
    private final String hubBase;

    public HuggingFaceResolver(@Value("${ananke.packs.hf-base-url:https://huggingface.co}") String hubBase) {
        this.hubBase = hubBase.replaceAll("/+$", "");
    }

    public record RepoRef(String repo, String revision) { }

    /** Accepts "owner/name", "owner/name@rev" or a huggingface.co URL (optionally /tree/&lt;rev&gt;). */
    public static RepoRef parse(String input) {
        if (input == null || input.isBlank()) throw new PackException(INVALID_INPUT, "Paste a Hugging Face model URL or repo id.");
        String s = input.strip();
        String repo;
        String rev = null;
        Matcher m = HF_URL.matcher(s);
        if (m.matches()) {
            repo = m.group(1);
            rev = m.group(2);
        } else if (s.contains("://")) {
            throw new PackException(INVALID_INPUT, "Only huggingface.co model URLs are supported.");
        } else {
            int at = s.indexOf('@');
            repo = at < 0 ? s : s.substring(0, at);
            rev = at < 0 ? null : s.substring(at + 1);
        }
        if (repo.startsWith("datasets/") || repo.startsWith("spaces/")) {
            throw new PackException(INVALID_INPUT, "That's a dataset or Space, not a model repo.");
        }
        if (!REPO_ID.matcher(repo).matches()) throw new PackException(INVALID_INPUT, "'" + repo + "' is not a valid repo id (owner/name).");
        if (rev != null && (rev.isBlank() || !REVISION.matcher(rev).matches() || rev.contains(".."))) {
            throw new PackException(INVALID_INPUT, "Invalid revision.");
        }
        return new RepoRef(repo, rev);
    }

    public ModelProfile resolve(String input, String hfToken) {
        RepoRef ref = parse(input);
        String requested = ref.revision() == null ? "main" : ref.revision();

        JsonNode info = getJson("/api/models/" + ref.repo() + "/revision/" + enc(requested), hfToken, true);
        String sha = info.path("sha").asText("");
        if (!SHA.matcher(sha).matches()) throw new PackException(REPO_UNREACHABLE, "The Hub did not return a commit for this revision.");

        String gated = info.path("gated").isBoolean() ? (info.path("gated").asBoolean() ? "true" : "false") : info.path("gated").asText("false");
        boolean isPrivate = info.path("private").asBoolean(false);
        String task = info.path("pipeline_tag").asText(null);
        String license = info.path("cardData").path("license").asText(null);
        if (license == null) {
            for (JsonNode t : info.path("tags")) if (t.asText().startsWith("license:")) license = t.asText().substring(8);
        }
        Long params = info.path("safetensors").path("total").isNumber() ? info.path("safetensors").path("total").asLong() : null;
        boolean hasSafetensors = false;
        for (JsonNode f : info.path("siblings")) if (f.path("rfilename").asText().endsWith(".safetensors")) hasSafetensors = true;

        List<String> notes = new ArrayList<>();
        List<String> blockers = new ArrayList<>();

        // Files at the pinned commit — never "main" — so the profile matches what will be deployed.
        JsonNode config = getFileJson(ref.repo(), sha, "config.json", hfToken);
        if (config == null) {
            if (!"false".equals(gated) && (hfToken == null || hfToken.isBlank())) {
                throw new PackException(REPO_GATED, "This model is gated. Accept its terms on Hugging Face, then add a read token for it.");
            }
            blockers.add("No config.json at this revision — not a standard Transformers checkpoint.");
        }
        JsonNode tokCfg = getFileJson(ref.repo(), sha, "tokenizer_config.json", hfToken);

        List<String> archs = new ArrayList<>();
        if (config != null) for (JsonNode a : config.path("architectures")) archs.add(a.asText());
        String modelType = config == null ? null : config.path("model_type").asText(null);
        JsonNode textCfg = config == null ? null : (config.has("text_config") ? config.path("text_config") : config);
        Integer ctx = null;
        if (textCfg != null) {
            for (String k : List.of("max_position_embeddings", "max_sequence_length", "seq_length", "n_positions")) {
                if (textCfg.path(k).isInt()) { ctx = textCfg.path(k).asInt(); break; }
            }
        }
        String dtype = config == null ? null : config.path("torch_dtype").asText(null);
        String quant = null;
        Integer bits = null;
        if (config != null && config.has("quantization_config")) {
            JsonNode q = config.path("quantization_config");
            quant = q.path("quant_method").asText(null);
            if (q.path("bits").isInt()) bits = q.path("bits").asInt();
            else if (q.path("load_in_4bit").asBoolean(false)) bits = 4;
        }
        boolean remoteCode = config != null && config.has("auto_map");

        String template = tokCfg == null ? null : tokCfg.path("chat_template").isTextual()
                ? tokCfg.path("chat_template").asText()
                : tokCfg.path("chat_template").isArray() ? tokCfg.path("chat_template").toString() : null;
        boolean hasTemplate = template != null && !template.isBlank();
        boolean templateTools = hasTemplate && template.contains("tools");

        // ── verdict ────────────────────────────────────────────────────────
        String compat = "supported";
        if (task != null && !VllmCompatibility.TEXT_TASKS.contains(task)) {
            blockers.add("Task is '" + task + "'. Packs currently serve text-generation/chat models only.");
        } else if (task == null) {
            notes.add("The repo has no pipeline tag; assuming text generation from its config.");
            compat = "unverified";
        }
        if (archs.isEmpty() && config != null) blockers.add("config.json lists no architectures.");
        for (String a : archs) {
            if (!VllmCompatibility.KNOWN_ARCHITECTURES.contains(a)) {
                notes.add("Architecture " + a + " is not on Ananke's verified vLLM list; deployment may fail.");
                compat = "unverified";
            }
        }
        if (remoteCode) {
            notes.add("This repo ships custom Python code (auto_map). It only runs if you explicitly trust it.");
            if ("supported".equals(compat)) compat = "unverified";
        }
        if (quant != null && !VllmCompatibility.KNOWN_QUANT.contains(quant)) {
            blockers.add("Quantization '" + quant + "' is not one vLLM loads.");
        }
        if (!hasSafetensors) notes.add("No .safetensors weights at this revision; vLLM will fall back to .bin/pickled weights.");
        if (!hasTemplate) notes.add("No chat template in tokenizer_config.json — copilot chat formatting will be unreliable.");
        if (ctx == null) notes.add("Context length not found in config.json.");
        String arch0 = archs.isEmpty() ? null : archs.get(0);
        String parser = VllmCompatibility.toolParserFor(arch0, templateTools);
        if (parser == null) notes.add("No known tool-calling parser for this model; the copilot's tool loop needs one. Tool calling will be tested after deploy.");
        if (!blockers.isEmpty()) compat = "unsupported";

        return new ModelProfile(ref.repo(), sha, requested, task, archs, modelType, license, gated, isPrivate,
                ctx, dtype, quant, bits, params, VllmCompatibility.estimateWeightsGiB(params, quant, bits, dtype),
                hasTemplate, templateTools, remoteCode, hasSafetensors, parser, compat, notes, blockers);
    }

    // ── HTTP ─────────────────────────────────────────────────────────────────

    private JsonNode getJson(String path, String token, boolean required) {
        HttpResponse<String> res = send(hubBase + path, token);
        int s = res.statusCode();
        if (s == 200) {
            try {
                return mapper.readTree(res.body());
            } catch (Exception e) {
                throw new PackException(REPO_UNREACHABLE, "Unexpected response from the Hugging Face Hub.");
            }
        }
        if (!required) return null;
        if (s == 401 || s == 403) {
            throw new PackException(REPO_GATED, token == null || token.isBlank()
                    ? "The repo is private or gated. Add a Hugging Face read token that has access to it."
                    : "The token does not have access to this repo (or its terms haven't been accepted).");
        }
        if (s == 404) throw new PackException(REPO_NOT_FOUND, "No model repo or revision with that name on Hugging Face.");
        throw new PackException(REPO_UNREACHABLE, "Hugging Face returned HTTP " + s + ".");
    }

    private JsonNode getFileJson(String repo, String sha, String file, String token) {
        HttpResponse<String> res = send(hubBase + "/" + repo + "/resolve/" + sha + "/" + file, token);
        if (res.statusCode() != 200) return null;
        try {
            return mapper.readTree(res.body());
        } catch (Exception e) {
            return null;
        }
    }

    private HttpResponse<String> send(String url, String token) {
        try {
            HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(20)).GET();
            if (token != null && !token.isBlank()) b.header("Authorization", "Bearer " + token.strip());
            return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new PackException(REPO_UNREACHABLE, "Interrupted while contacting Hugging Face.");
        } catch (Exception e) {
            throw new PackException(REPO_UNREACHABLE, "Could not reach Hugging Face (" + e.getClass().getSimpleName() + ").");
        }
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }
}
