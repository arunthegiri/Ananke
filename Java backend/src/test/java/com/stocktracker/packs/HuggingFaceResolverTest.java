package com.stocktracker.packs;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Fake Hugging Face Hub — no network. */
class HuggingFaceResolverTest {

    static final String SHA = "0123456789abcdef0123456789abcdef01234567";

    private HttpServer server;
    private final Map<String, String> routes = new ConcurrentHashMap<>();
    private final Map<String, Integer> statuses = new ConcurrentHashMap<>();
    private final List<String> paths = new CopyOnWriteArrayList<>();
    private final List<String> auths = new CopyOnWriteArrayList<>();

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            String path = ex.getRequestURI().getPath();
            paths.add(path);
            String a = ex.getRequestHeaders().getFirst("Authorization");
            if (a != null) auths.add(a);
            String body = routes.get(path);
            int status = statuses.getOrDefault(path, body == null ? 404 : 200);
            byte[] out = (body == null ? "{}" : body).getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(status, out.length);
            ex.getResponseBody().write(out);
            ex.close();
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private HuggingFaceResolver resolver() {
        return new HuggingFaceResolver("http://127.0.0.1:" + server.getAddress().getPort());
    }

    private void qwen(String extraConfig) {
        routes.put("/api/models/Qwen/Qwen3-8B/revision/main", """
                {"sha":"%s","pipeline_tag":"text-generation","gated":false,"private":false,
                 "cardData":{"license":"apache-2.0"},"safetensors":{"total":8000000000},
                 "siblings":[{"rfilename":"model-00001.safetensors"}]}""".formatted(SHA));
        routes.put("/Qwen/Qwen3-8B/resolve/" + SHA + "/config.json",
                "{\"architectures\":[\"Qwen3ForCausalLM\"],\"model_type\":\"qwen3\",\"max_position_embeddings\":40960,\"torch_dtype\":\"bfloat16\"" + extraConfig + "}");
        routes.put("/Qwen/Qwen3-8B/resolve/" + SHA + "/tokenizer_config.json",
                "{\"chat_template\":\"{% if tools %}...{% endif %}\"}");
    }

    @Test
    void parsesUrlsAndIds() {
        assertThat(HuggingFaceResolver.parse("https://huggingface.co/Qwen/Qwen3-8B").repo()).isEqualTo("Qwen/Qwen3-8B");
        assertThat(HuggingFaceResolver.parse("https://huggingface.co/Qwen/Qwen3-8B/tree/v1.0").revision()).isEqualTo("v1.0");
        assertThat(HuggingFaceResolver.parse("meta-llama/Llama-3.1-8B-Instruct@abc").revision()).isEqualTo("abc");
        assertThatThrownBy(() -> HuggingFaceResolver.parse("https://evil.example/Qwen/Qwen3-8B")).isInstanceOf(PackException.class);
        assertThatThrownBy(() -> HuggingFaceResolver.parse("Qwen/Qwen3-8B; rm -rf /")).isInstanceOf(PackException.class);
        assertThatThrownBy(() -> HuggingFaceResolver.parse("https://huggingface.co/datasets/foo/bar")).isInstanceOf(PackException.class);
        assertThatThrownBy(() -> HuggingFaceResolver.parse("a/b@../../etc")).isInstanceOf(PackException.class);
    }

    @Test
    void pinsTheCommitAndReadsFilesAtThatCommit() {
        qwen("");
        ModelProfile p = resolver().resolve("https://huggingface.co/Qwen/Qwen3-8B", null);
        assertThat(p.revision()).isEqualTo(SHA);
        assertThat(p.compatibility()).isEqualTo("supported");
        assertThat(p.contextLength()).isEqualTo(40960);
        assertThat(p.license()).isEqualTo("apache-2.0");
        assertThat(p.suggestedToolParser()).isEqualTo("hermes");
        assertThat(p.estimatedWeightsGiB()).isGreaterThan(15.0);
        assertThat(paths).contains("/Qwen/Qwen3-8B/resolve/" + SHA + "/config.json");
        assertThat(paths).noneMatch(x -> x.contains("/resolve/main/"));
    }

    @Test
    void customCodeIsFlaggedNotTrusted() {
        qwen(",\"auto_map\":{\"AutoModelForCausalLM\":\"modeling.X\"}");
        ModelProfile p = resolver().resolve("Qwen/Qwen3-8B", null);
        assertThat(p.requiresRemoteCode()).isTrue();
        assertThat(p.compatibility()).isEqualTo("unverified");
    }

    @Test
    void unknownArchitectureIsUnverifiedNotSupported() {
        qwen("");
        routes.put("/Qwen/Qwen3-8B/resolve/" + SHA + "/config.json", "{\"architectures\":[\"BrandNewForCausalLM\"]}");
        assertThat(resolver().resolve("Qwen/Qwen3-8B", null).compatibility()).isEqualTo("unverified");
    }

    @Test
    void nonTextTaskIsUnsupported() {
        qwen("");
        routes.put("/api/models/Qwen/Qwen3-8B/revision/main",
                "{\"sha\":\"" + SHA + "\",\"pipeline_tag\":\"feature-extraction\",\"siblings\":[]}");
        ModelProfile p = resolver().resolve("Qwen/Qwen3-8B", null);
        assertThat(p.compatibility()).isEqualTo("unsupported");
        assertThat(p.blockers()).anyMatch(b -> b.contains("feature-extraction"));
    }

    @Test
    void gatedRepoWithoutTokenExplainsWhatToDo() {
        statuses.put("/api/models/meta-llama/Llama-3.1-8B/revision/main", 401);
        routes.put("/api/models/meta-llama/Llama-3.1-8B/revision/main", "{}");
        assertThatThrownBy(() -> resolver().resolve("meta-llama/Llama-3.1-8B", null))
                .isInstanceOf(PackException.class)
                .satisfies(e -> assertThat(((PackException) e).code()).isEqualTo(PackException.Code.REPO_GATED));
    }

    @Test
    void missingRepoIsNotFound() {
        assertThatThrownBy(() -> resolver().resolve("nobody/nothing", null))
                .satisfies(e -> assertThat(((PackException) e).code()).isEqualTo(PackException.Code.REPO_NOT_FOUND));
    }

    @Test
    void tokenIsSentAsBearerOnly() {
        qwen("");
        resolver().resolve("Qwen/Qwen3-8B", "hf_tokenvalue000000000000000");
        assertThat(auths).allMatch(a -> a.equals("Bearer hf_tokenvalue000000000000000"));
        assertThat(paths).noneMatch(p -> p.contains("hf_tokenvalue"));
    }
}
