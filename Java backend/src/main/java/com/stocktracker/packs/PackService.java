package com.stocktracker.packs;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stocktracker.packs.compute.SshComputeProvider;
import com.stocktracker.packs.runtime.RemoteRuntime;
import com.stocktracker.packs.runtime.SshCredentials;
import com.stocktracker.packs.runtime.SshRemoteRuntime;
import com.stocktracker.service.KimiClient;
import com.stocktracker.service.LLMClient;
import com.stocktracker.service.LlmSettingsService;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;

import static com.stocktracker.packs.PackException.Code.*;

/**
 * Orchestrates a Pack's life: import (pin + profile) → configure (target +
 * launch settings) → deploy (probe, bootstrap, tunnel, health, tool probe) →
 * register with the copilot's provider layer. Each concern lives in its own
 * component — {@link HuggingFaceResolver}, {@link SshComputeProvider},
 * {@link RemoteRuntime}, {@link LlmSettingsService} — and this class only
 * sequences them and records state.
 */
@Slf4j
@Service
public class PackService implements LlmSettingsService.ExternalProviders {

    static final String HF_TOKEN = "hf_token";
    static final String API_KEY = "vllm_api_key";
    private static final int LOG_CAP = 64 * 1024;
    private static final Pattern HF_TOKEN_SHAPE = Pattern.compile("hf_[A-Za-z0-9]{20,}");
    private static final Pattern KEY_BLOCK = Pattern.compile("-----BEGIN[^-]*PRIVATE KEY-----[\\s\\S]*?-----END[^-]*PRIVATE KEY-----");

    private final PackRepository packs;
    private final ComputeTargetRepository targets;
    private final HuggingFaceResolver hf;
    private final SshComputeProvider ssh;
    private final SshRemoteRuntime runtime;
    private final SecretVault vault;
    private final LlmSettingsService settings;
    private final String vllmVersion;
    private final Duration healthTimeout;

    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final ExecutorService deployer = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "pack-deploy");
        t.setDaemon(true);
        return t;
    });
    private final Map<Long, SshRemoteRuntime.Tunnel> tunnels = new ConcurrentHashMap<>();
    private final SecureRandom rng = new SecureRandom();

    public PackService(PackRepository packs, ComputeTargetRepository targets, HuggingFaceResolver hf,
                       SshComputeProvider ssh, SshRemoteRuntime runtime, SecretVault vault, LlmSettingsService settings,
                       @Value("${ananke.packs.vllm-version:0.11.0}") String vllmVersion,
                       @Value("${ananke.packs.health-timeout-minutes:30}") int healthTimeoutMinutes) {
        this.packs = packs;
        this.targets = targets;
        this.hf = hf;
        this.ssh = ssh;
        this.runtime = runtime;
        this.vault = vault;
        this.settings = settings;
        this.vllmVersion = vllmVersion;
        this.healthTimeout = Duration.ofMinutes(healthTimeoutMinutes);
    }

    @PostConstruct
    void init() {
        settings.setExternalProviders(this);
        // A backend restart mid-deploy leaves the row stuck; say so instead of spinning forever.
        for (Pack p : packs.findByStatus(Pack.Status.DEPLOYING)) {
            fail(p, SSH_FAILED, "The backend restarted during deployment. Redeploy to continue.");
        }
    }

    @PreDestroy
    void shutdown() {
        deployer.shutdownNow();
        tunnels.values().forEach(t -> t.process().destroy());
    }

    // ── import ───────────────────────────────────────────────────────────────

    public ModelProfile preview(String input, String hfToken) {
        return hf.resolve(input, hfToken);
    }

    public Pack importPack(String input, String name, String hfToken, boolean trustRemoteCode) {
        ModelProfile profile = hf.resolve(input, hfToken);
        Pack p = new Pack();
        p.setName(name == null || name.isBlank() ? profile.repo().substring(profile.repo().indexOf('/') + 1) : trim(name, 100));
        p.setHfRepo(profile.repo());
        p.setHfRevision(profile.revision());
        p.setProfile(mapper.convertValue(profile, Map.class));
        p.setTrustRemoteCode(trustRemoteCode && profile.requiresRemoteCode());
        p.setLaunchConfig(LaunchConfig.defaults(profile).toMap());
        p = packs.save(p);
        if (hfToken != null && !hfToken.isBlank()) vault.put(p.scope(), HF_TOKEN, hfToken.strip());
        appendLog(p, "Imported " + profile.repo() + " pinned at " + profile.revision()
                + " (requested '" + profile.requestedRevision() + "'). Compatibility: " + profile.compatibility() + ".");
        return packs.save(p);
    }

    public ModelProfile profile(Pack p) {
        return mapper.convertValue(p.getProfile(), ModelProfile.class);
    }

    // ── configure ────────────────────────────────────────────────────────────

    public Pack configure(long id, Long targetId, LaunchConfig cfg, String hfToken, Boolean trustRemoteCode) {
        Pack p = get(id);
        if (p.getStatus() == Pack.Status.DEPLOYING) throw new IllegalStateException("Wait for the current deployment to finish.");
        if (targetId != null) {
            targets.findById(targetId).orElseThrow(() -> new NoSuchElementException("Compute target " + targetId + " not found"));
            p.setComputeTargetId(targetId);
        }
        if (cfg != null) p.setLaunchConfig(cfg.validated().toMap());
        if (hfToken != null) {
            if (hfToken.isBlank()) vault.delete(p.scope(), HF_TOKEN);
            else vault.put(p.scope(), HF_TOKEN, hfToken.strip());
        }
        if (trustRemoteCode != null) {
            p.setTrustRemoteCode(trustRemoteCode && profile(p).requiresRemoteCode());
            appendLog(p, trustRemoteCode ? "User explicitly trusted this repo's custom code." : "Custom code trust revoked.");
        }
        return packs.save(p);
    }

    // ── deploy ───────────────────────────────────────────────────────────────

    public Pack deploy(long id) {
        Pack p = get(id);
        if (p.getStatus() == Pack.Status.DEPLOYING) throw new IllegalStateException("Already deploying.");
        ModelProfile prof = profile(p);
        if ("unsupported".equals(prof.compatibility())) {
            throw new PackException(INCOMPATIBLE_MODEL, String.join(" ", prof.blockers()));
        }
        if (prof.requiresRemoteCode() && !p.isTrustRemoteCode()) {
            throw new PackException(REMOTE_CODE_UNTRUSTED, "This model needs its repo's custom Python code. Review it and explicitly trust it first.");
        }
        if (p.getComputeTargetId() == null) throw new PackException(INVALID_INPUT, "Choose a compute target first.");
        LaunchConfig.fromMap(p.getLaunchConfig()).validated();

        p.setStatus(Pack.Status.DEPLOYING);
        p.setPhase("CHECKING_TARGET");
        p.setFailureCode(null);
        p.setFailureDetail(null);
        appendLog(p, "Deployment started.");
        Pack saved = packs.save(p);
        deployer.submit(() -> runDeploy(saved.getId()));
        return saved;
    }

    void runDeploy(long id) {
        Pack p = get(id);
        try {
            ComputeTarget t = targets.findById(p.getComputeTargetId()).orElseThrow(() ->
                    new PackException(INVALID_INPUT, "The compute target was removed."));
            SshCredentials c = ssh.credentials(t);
            ModelProfile prof = profile(p);
            LaunchConfig cfg = LaunchConfig.fromMap(p.getLaunchConfig());

            // 1. Machine check
            RemoteRuntime.Probe probe = runtime.probe(c);
            recordProbe(t, probe);
            appendLog(p, "Machine: " + probe.os() + ", python " + probe.python() + ", " + probe.gpus().size() + " GPU(s), "
                    + probe.diskFreeGiB() + " GiB free.");
            checkMachine(probe, prof, cfg);

            // 2. Install + download + launch (one constrained remote step)
            String apiKey = newApiKey();
            vault.put(p.scope(), API_KEY, apiKey);
            String parser = cfg.enableToolCalling() && prof.suggestedToolParser() != null ? prof.suggestedToolParser() : "none";
            String quant = prof.quantization() == null ? "none" : prof.quantization();
            RemoteRuntime.DeploySpec spec = new RemoteRuntime.DeploySpec(p.getId(), p.getHfRepo(), p.getHfRevision(),
                    cfg.remotePort(), servedName(p), cfg.dtype(), cfg.maxModelLen(), cfg.gpuUtilArg(), cfg.tensorParallelSize(),
                    quant, parser, p.isTrustRemoteCode(), vllmVersion, vault.get(p.scope(), HF_TOKEN).orElse(null), apiKey);
            setPhase(p, "INSTALLING");
            appendLog(p, "Installing vLLM " + vllmVersion + " (first run only), downloading " + p.getHfRepo() + "@"
                    + p.getHfRevision().substring(0, 12) + ", launching" + ("none".equals(parser) ? "" : " with tool parser " + parser) + "…");
            String transcript = runtime.deploy(c, spec);
            for (String line : transcript.split("\n")) if (line.startsWith("ANANKE_PHASE=")) appendLog(p, "  remote: " + line.substring(13));

            // 3. Tunnel + health
            setPhase(p, "HEALTH_CHECK");
            Map<String, Object> endpoint = new LinkedHashMap<>();
            endpoint.put("remoteHost", t.getHost());
            endpoint.put("remotePort", cfg.remotePort());
            endpoint.put("servedModel", servedName(p));
            endpoint.put("transport", "ssh-tunnel");
            endpoint.put("toolParser", parser);
            p.setEndpoint(endpoint);
            p = packs.save(p);
            waitHealthy(p, c, apiKey);

            // 4. Capabilities
            setPhase(p, "VERIFYING_TOOLS");
            boolean tools = !"none".equals(parser) && probeToolCalling(p, apiKey);
            p = get(id);
            p.getEndpoint().put("toolCalling", tools ? "verified" : "none".equals(parser) ? "not_enabled" : "failed");
            p.getEndpoint().put("chat", prof.hasChatTemplate() ? "chat_template" : "no_template");
            appendLog(p, tools ? "Tool calling verified with a live probe." : "Tool calling is NOT available; the copilot needs it, so this Pack can't drive the copilot yet.");
            p.setStatus(Pack.Status.RUNNING);
            p.setPhase(null);
            appendLog(p, "Running. Registered as copilot provider pack:" + p.getId() + ".");
            packs.save(p);
        } catch (PackException e) {
            fail(get(id), e.code(), e.getMessage());
        } catch (Exception e) {
            log.warn("Pack {} deploy failed: {}", id, e.getClass().getSimpleName());
            fail(get(id), SSH_FAILED, "Unexpected error: " + e.getClass().getSimpleName());
        }
    }

    static void checkMachine(RemoteRuntime.Probe probe, ModelProfile prof, LaunchConfig cfg) {
        if (probe.gpus().isEmpty()) throw new PackException(NO_GPU, "No NVIDIA GPU found (nvidia-smi missing or reports no devices).");
        if (probe.python() == null) throw new PackException(MISSING_PREREQ, "python3 is not installed on the machine.");
        if (!probe.venv()) throw new PackException(MISSING_PREREQ, "python3-venv is not installed (apt install python3-venv).");
        if (cfg.tensorParallelSize() > probe.gpus().size()) {
            throw new PackException(INSUFFICIENT_VRAM, "Tensor parallel size " + cfg.tensorParallelSize() + " needs that many GPUs; the machine has " + probe.gpus().size() + ".");
        }
        if (prof.estimatedWeightsGiB() != null) {
            double usable = probe.gpus().stream().map(RemoteRuntime.Gpu::memoryMiB).sorted(Comparator.reverseOrder())
                    .limit(cfg.tensorParallelSize()).mapToDouble(m -> m / 1024.0).sum() * cfg.gpuMemoryUtilization();
            if (prof.estimatedWeightsGiB() > usable) {
                throw new PackException(INSUFFICIENT_VRAM, String.format(Locale.ROOT,
                        "The model needs roughly %.1f GiB for weights; %d GPU(s) at %.0f%% utilization give %.1f GiB.",
                        prof.estimatedWeightsGiB(), cfg.tensorParallelSize(), cfg.gpuMemoryUtilization() * 100, usable));
            }
        }
        if (probe.diskFreeGiB() != null && prof.estimatedWeightsGiB() != null && probe.diskFreeGiB() < prof.estimatedWeightsGiB() + 15) {
            throw new PackException(MISSING_PREREQ, "Not enough disk: ~" + Math.round(prof.estimatedWeightsGiB() + 15) + " GiB needed, " + probe.diskFreeGiB() + " GiB free.");
        }
    }

    private void waitHealthy(Pack p, SshCredentials c, String apiKey) {
        long deadline = System.currentTimeMillis() + healthTimeout.toMillis();
        int polls = 0;
        while (System.currentTimeMillis() < deadline) {
            Map<String, Object> h = checkHealth(p, apiKey);
            if (Boolean.TRUE.equals(h.get("ok"))) {
                p.setLastHealth(h);
                appendLog(p, "Health check passed: /v1/models lists " + servedName(p) + ".");
                packs.save(p);
                return;
            }
            if (++polls % 3 == 0 && !runtime.isRunning(c, p.getId())) {
                String tail = tail(redact(p, runtime.logs(c, p.getId())), 25);
                throw new PackException(VLLM_START_FAILED, "vLLM exited while loading the model. Last log lines:\n" + tail);
            }
            sleep(10_000);
        }
        throw new PackException(HEALTH_TIMEOUT, "vLLM did not become healthy within " + healthTimeout.toMinutes() + " minutes. Check the logs.");
    }

    /** One GET /v1/models through the tunnel. */
    Map<String, Object> checkHealth(Pack p, String apiKey) {
        Map<String, Object> h = new LinkedHashMap<>();
        h.put("at", Instant.now().toString());
        try {
            SshRemoteRuntime.Tunnel t = tunnel(p);
            HttpResponse<String> res = http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + t.localPort() + "/v1/models"))
                    .header("Authorization", "Bearer " + apiKey).timeout(Duration.ofSeconds(10)).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            boolean listed = res.statusCode() == 200 && res.body().contains("\"" + servedName(p) + "\"");
            h.put("ok", listed);
            h.put("detail", listed ? "healthy" : "HTTP " + res.statusCode());
        } catch (PackException e) {
            h.put("ok", false);
            h.put("detail", e.getMessage());
        } catch (Exception e) {
            h.put("ok", false);
            h.put("detail", "not reachable yet (" + e.getClass().getSimpleName() + ")");
        }
        return h;
    }

    private boolean probeToolCalling(Pack p, String apiKey) {
        try {
            LLMClient c = client(p, apiKey);
            List<Map<String, Object>> tools = List.of(Map.of("type", "function", "function", Map.of(
                    "name", "get_symbol_price",
                    "description", "Get the latest price for a stock symbol.",
                    "parameters", Map.of("type", "object",
                            "properties", Map.of("symbol", Map.of("type", "string")), "required", List.of("symbol")))));
            JsonNode res = c.chat(List.of(Map.of("role", "user", "content", "What is the latest price of AAPL? Use the tool.")), tools, 256);
            JsonNode calls = res.path("choices").path(0).path("message").path("tool_calls");
            return calls.isArray() && calls.size() > 0
                    && "get_symbol_price".equals(calls.get(0).path("function").path("name").asText());
        } catch (Exception e) {
            appendLog(p, "Tool-calling probe failed: " + e.getClass().getSimpleName());
            return false;
        }
    }

    // ── controls ─────────────────────────────────────────────────────────────

    public Pack stop(long id) {
        Pack p = get(id);
        withRemote(p, (c) -> runtime.stop(c, p.getId()));
        closeTunnel(id);
        if (("pack:" + id).equals(settings.activeProvider())) settings.clearActive();
        p.setStatus(Pack.Status.STOPPED);
        p.setPhase(null);
        appendLog(p, "Stopped.");
        return packs.save(p);
    }

    public void remove(long id) {
        Pack p = get(id);
        if (p.getComputeTargetId() != null) {
            try {
                withRemote(p, (c) -> runtime.remove(c, p.getId()));
            } catch (PackException e) {
                // Still let the user delete the Pack; tell them what was left behind.
                log.info("Pack {} removed locally; remote cleanup failed: {}", id, e.code());
            }
        }
        closeTunnel(id);
        if (("pack:" + id).equals(settings.activeProvider())) settings.clearActive();
        vault.deleteScope(p.scope());
        packs.delete(p);
    }

    public Map<String, Object> healthNow(long id) {
        Pack p = get(id);
        if (p.getStatus() != Pack.Status.RUNNING) throw new PackException(NOT_DEPLOYED, "The Pack is not running.");
        Map<String, Object> h = checkHealth(p, vault.get(p.scope(), API_KEY).orElse(""));
        p.setLastHealth(h);
        packs.save(p);
        return h;
    }

    public String logs(long id) {
        Pack p = get(id);
        StringBuilder sb = new StringBuilder(p.getDeployLog());
        if (p.getComputeTargetId() != null && p.getStatus() != Pack.Status.IMPORTED) {
            try {
                AtomicString out = new AtomicString();
                withRemote(p, (c) -> out.v = runtime.logs(c, p.getId()));
                sb.append("\n\n").append(redact(p, out.v));
            } catch (PackException e) {
                sb.append("\n\n(remote logs unavailable: ").append(e.getMessage()).append(")");
            }
        }
        return sb.toString();
    }

    public void useForCopilot(long id) {
        Pack p = get(id);
        if (p.getStatus() != Pack.Status.RUNNING) throw new PackException(NOT_DEPLOYED, "Deploy the Pack first.");
        Object tools = p.getEndpoint() == null ? null : p.getEndpoint().get("toolCalling");
        if (!"verified".equals(tools)) {
            throw new PackException(INCOMPATIBLE_MODEL, "The copilot drives a tool loop, and tool calling was not verified for this model.");
        }
        settings.activateExternal("pack:" + id);
    }

    // ── compute targets ──────────────────────────────────────────────────────

    public ComputeTarget probeTarget(long targetId) {
        ComputeTarget t = targets.findById(targetId).orElseThrow(() -> new NoSuchElementException("Compute target not found"));
        try {
            RemoteRuntime.Probe probe = runtime.probe(ssh.credentials(t));
            recordProbe(t, probe);
            t.setStatus(probe.gpus().isEmpty() ? "NO_GPU" : "READY");
        } catch (PackException e) {
            t.setStatus("ERROR");
            t.setLastProbe(Map.of("error", e.code().name(), "detail", e.getMessage()));
            t.setLastProbeAt(Instant.now());
            targets.save(t);
            throw e;
        }
        return targets.save(t);
    }

    public void removeTarget(long targetId) {
        if (packs.existsByComputeTargetId(targetId)) throw new IllegalStateException("Remove or reassign the Packs using this machine first.");
        ComputeTarget t = targets.findById(targetId).orElseThrow(() -> new NoSuchElementException("Compute target not found"));
        vault.deleteScope(t.scope());
        targets.delete(t);
    }

    private void recordProbe(ComputeTarget t, RemoteRuntime.Probe probe) {
        t.setLastProbe(new LinkedHashMap<>(probe.toMap()));
        t.setLastProbeAt(Instant.now());
        if (!"UNVERIFIED".equals(t.getStatus())) t.setStatus(probe.gpus().isEmpty() ? "NO_GPU" : "READY");
        targets.save(t);
    }

    // ── copilot provider layer ───────────────────────────────────────────────

    @Override
    public Optional<LLMClient> clientFor(String providerId) {
        if (providerId == null || !providerId.startsWith("pack:")) return Optional.empty();
        try {
            long id = Long.parseLong(providerId.substring(5));
            Pack p = packs.findById(id).orElse(null);
            if (p == null || p.getStatus() != Pack.Status.RUNNING) return Optional.empty();
            return vault.get(p.scope(), API_KEY).map(k -> client(p, k));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    @Override
    public Optional<String> labelFor(String providerId) {
        if (providerId == null || !providerId.startsWith("pack:")) return Optional.empty();
        try {
            return packs.findById(Long.parseLong(providerId.substring(5))).map(p -> "Pack: " + p.getName());
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    private LLMClient client(Pack p, String apiKey) {
        String base = "http://127.0.0.1:" + tunnel(p).localPort() + "/v1";
        // KimiClient is the generic OpenAI-compatible client; vLLM speaks that shape.
        return new KimiClient(apiKey, base, servedName(p), "", base, servedName(p));
    }

    // ── internals ────────────────────────────────────────────────────────────

    private synchronized SshRemoteRuntime.Tunnel tunnel(Pack p) {
        SshRemoteRuntime.Tunnel t = tunnels.get(p.getId());
        if (t != null && t.process().isAlive()) return t;
        ComputeTarget target = targets.findById(p.getComputeTargetId()).orElseThrow(() ->
                new PackException(TUNNEL_FAILED, "The compute target was removed."));
        int port = LaunchConfig.fromMap(p.getLaunchConfig()).remotePort();
        t = runtime.openTunnel(ssh.credentials(target), port);
        tunnels.put(p.getId(), t);
        return t;
    }

    private void closeTunnel(long id) {
        SshRemoteRuntime.Tunnel t = tunnels.remove(id);
        if (t != null) t.process().destroy();
    }

    private interface Remote { void run(SshCredentials c); }
    private static final class AtomicString { String v = ""; }

    private void withRemote(Pack p, Remote r) {
        if (p.getComputeTargetId() == null) return;
        ComputeTarget t = targets.findById(p.getComputeTargetId()).orElseThrow(() ->
                new PackException(INVALID_INPUT, "The compute target was removed."));
        r.run(ssh.credentials(t));
    }

    public Pack get(long id) {
        return packs.findById(id).orElseThrow(() -> new NoSuchElementException("Pack " + id + " not found"));
    }

    public List<Pack> list() {
        return packs.findAllByOrderByCreatedAtDesc();
    }

    public boolean hasHfToken(Pack p) {
        return vault.has(p.scope(), HF_TOKEN);
    }

    static String servedName(Pack p) {
        return "pack-" + p.getId();
    }

    private void setPhase(Pack p, String phase) {
        Pack fresh = get(p.getId());
        fresh.setPhase(phase);
        packs.save(fresh);
        p.setPhase(phase);
    }

    private void fail(Pack p, PackException.Code code, String detail) {
        p.setStatus(Pack.Status.FAILED);
        p.setFailureCode(code.name());
        p.setFailureDetail(redact(p, detail));
        appendLog(p, "FAILED [" + code + "] " + redact(p, detail));
        p.setPhase(null);
        packs.save(p);
    }

    private void appendLog(Pack p, String line) {
        String next = p.getDeployLog() + Instant.now().toString().substring(0, 19).replace('T', ' ') + "  " + redact(p, line) + "\n";
        if (next.length() > LOG_CAP) next = next.substring(next.length() - LOG_CAP);
        p.setDeployLog(next);
    }

    /** Strips every secret this Pack knows about, plus anything token- or key-shaped. */
    String redact(Pack p, String text) {
        if (text == null) return "";
        String out = text;
        if (p.getId() != null) {
            for (String kind : List.of(HF_TOKEN, API_KEY)) {
                try {
                    Optional<String> s = vault.get(p.scope(), kind);
                    if (s.isPresent() && !s.get().isBlank()) out = out.replace(s.get(), "‹redacted›");
                } catch (Exception ignored) { }
            }
        }
        out = HF_TOKEN_SHAPE.matcher(out).replaceAll("hf_‹redacted›");
        return KEY_BLOCK.matcher(out).replaceAll("‹private key redacted›");
    }

    private String newApiKey() {
        byte[] b = new byte[32];
        rng.nextBytes(b);
        return "ak-" + HexFormat.of().formatHex(b);
    }

    private static String tail(String s, int lines) {
        String[] all = s.split("\n");
        return String.join("\n", Arrays.copyOfRange(all, Math.max(0, all.length - lines), all.length));
    }

    private static String trim(String s, int max) {
        String t = s.strip();
        return t.length() <= max ? t : t.substring(0, max);
    }

    private static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new PackException(HEALTH_TIMEOUT, "Interrupted."); }
    }
}
