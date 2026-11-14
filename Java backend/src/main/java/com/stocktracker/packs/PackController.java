package com.stocktracker.packs;

import com.stocktracker.packs.compute.SshComputeProvider;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * /api/packs — Pack import, configuration, deployment and controls — and
 * /api/compute — compute targets. Responses never contain secrets: only
 * "is set" flags and last-four hints.
 */
@Slf4j
@RestController
@RequestMapping("/api")
public class PackController {

    private final PackService packs;
    private final SshComputeProvider ssh;
    private final ComputeTargetRepository targets;
    private final SecretVault vault;

    public PackController(PackService packs, SshComputeProvider ssh, ComputeTargetRepository targets, SecretVault vault) {
        this.packs = packs;
        this.ssh = ssh;
        this.targets = targets;
        this.vault = vault;
    }

    // ── requests / views ─────────────────────────────────────────────────────

    public record ResolveRequest(String model, String hfToken) { }
    public record ImportRequest(String model, String name, String hfToken, Boolean trustRemoteCode) { }
    public record ConfigureRequest(Long computeTargetId, LaunchConfig launch, String hfToken, Boolean trustRemoteCode) { }
    public record RegisterTargetRequest(String label, String host, Integer port, String username, String privateKey) { }
    public record ConfirmHostKeyRequest(String fingerprint) { }

    public record PackView(long id, String name, String hfRepo, String hfRevision, ModelProfile profile,
                           boolean trustRemoteCode, Map<String, Object> launch, Long computeTargetId,
                           String status, String phase, String failureCode, String failureDetail,
                           Map<String, Object> endpoint, Map<String, Object> lastHealth,
                           boolean hfTokenSet, String providerId, String createdAt, String updatedAt) { }

    public record TargetView(long id, String provider, String label, String host, int port, String username,
                             String hostKeyFingerprint, String status, Map<String, Object> lastProbe,
                             String lastProbeAt, String keyHint) { }

    public record ProviderInfo(String id, String label, boolean canProvision, boolean available, String note) { }

    // ── packs ────────────────────────────────────────────────────────────────

    @PostMapping("/packs/resolve")
    public ModelProfile resolve(@RequestBody ResolveRequest req) {
        return packs.preview(req.model(), req.hfToken());
    }

    @GetMapping("/packs")
    public List<PackView> list() {
        return packs.list().stream().map(this::view).toList();
    }

    @PostMapping("/packs")
    public PackView create(@RequestBody ImportRequest req) {
        return view(packs.importPack(req.model(), req.name(), req.hfToken(), Boolean.TRUE.equals(req.trustRemoteCode())));
    }

    @GetMapping("/packs/{id}")
    public PackView get(@PathVariable long id) {
        return view(packs.get(id));
    }

    @PutMapping("/packs/{id}")
    public PackView configure(@PathVariable long id, @RequestBody ConfigureRequest req) {
        return view(packs.configure(id, req.computeTargetId(), req.launch(), req.hfToken(), req.trustRemoteCode()));
    }

    @PostMapping("/packs/{id}/deploy")
    public PackView deploy(@PathVariable long id) {
        return view(packs.deploy(id));
    }

    @PostMapping("/packs/{id}/stop")
    public PackView stop(@PathVariable long id) {
        return view(packs.stop(id));
    }

    @DeleteMapping("/packs/{id}")
    public ResponseEntity<Void> remove(@PathVariable long id) {
        packs.remove(id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/packs/{id}/health")
    public Map<String, Object> health(@PathVariable long id) {
        return packs.healthNow(id);
    }

    @GetMapping("/packs/{id}/logs")
    public Map<String, Object> logs(@PathVariable long id) {
        return Map.of("log", packs.logs(id));
    }

    @PostMapping("/packs/{id}/use-for-copilot")
    public PackView useForCopilot(@PathVariable long id) {
        packs.useForCopilot(id);
        return view(packs.get(id));
    }

    // ── compute ──────────────────────────────────────────────────────────────

    /** Honest list: SSH works; cloud providers are listed as not available rather than faked. */
    @GetMapping("/compute/providers")
    public List<ProviderInfo> providers() {
        return List.of(
                new ProviderInfo(ssh.id(), ssh.label(), false, true, "Any Linux machine with an NVIDIA GPU you can SSH into."),
                new ProviderInfo("aws", "AWS (EC2 GPU)", true, false, "Account linking and provisioning are not built yet."),
                new ProviderInfo("gcp", "Google Cloud (GCE GPU)", true, false, "Account linking and provisioning are not built yet."));
    }

    @GetMapping("/compute/targets")
    public List<TargetView> targets() {
        return targets.findAllByOrderByCreatedAtDesc().stream().map(this::view).toList();
    }

    @PostMapping("/compute/targets")
    public TargetView register(@RequestBody RegisterTargetRequest req) {
        return view(ssh.register(req.label(), req.host(), req.port() == null ? 22 : req.port(), req.username(), req.privateKey()));
    }

    @PostMapping("/compute/targets/{id}/confirm-host-key")
    public TargetView confirm(@PathVariable long id, @RequestBody ConfirmHostKeyRequest req) {
        ComputeTarget t = targets.findById(id).orElseThrow(() -> new java.util.NoSuchElementException("Compute target not found"));
        return view(ssh.confirmHostKey(t, req.fingerprint()));
    }

    @PostMapping("/compute/targets/{id}/probe")
    public TargetView probe(@PathVariable long id) {
        return view(packs.probeTarget(id));
    }

    @DeleteMapping("/compute/targets/{id}")
    public ResponseEntity<Void> removeTarget(@PathVariable long id) {
        packs.removeTarget(id);
        return ResponseEntity.noContent().build();
    }

    // ── errors ───────────────────────────────────────────────────────────────

    @ExceptionHandler(PackException.class)
    public ResponseEntity<Map<String, Object>> packError(PackException e) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("message", e.getMessage());
        body.put("code", e.code().name());
        body.put("status", 422);
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(body);
    }

    // ── mapping ──────────────────────────────────────────────────────────────

    private PackView view(Pack p) {
        return new PackView(p.getId(), p.getName(), p.getHfRepo(), p.getHfRevision(), packs.profile(p),
                p.isTrustRemoteCode(), p.getLaunchConfig(), p.getComputeTargetId(), p.getStatus().name(), p.getPhase(),
                p.getFailureCode(), p.getFailureDetail(), p.getEndpoint(), p.getLastHealth(), packs.hasHfToken(p),
                "pack:" + p.getId(), p.getCreatedAt().toString(), p.getUpdatedAt().toString());
    }

    private TargetView view(ComputeTarget t) {
        return new TargetView(t.getId(), t.getProvider(), t.getLabel(), t.getHost(), t.getPort(), t.getUsername(),
                t.getHostKeyFingerprint(), t.getStatus(), t.getLastProbe(),
                t.getLastProbeAt() == null ? null : t.getLastProbeAt().toString(),
                vault.hintFor(t.scope(), "ssh_public_key").orElse(null));
    }
}
