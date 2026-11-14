package com.stocktracker.agent.model;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

/**
 * Per-agent model settings API (§17.1). Behind the {@code perAgentModels} flag: while it is
 * off every route except {@code /api/agent-runtime/flags} answers 404 FEATURE_DISABLED and
 * the legacy Settings → AI provider page is the only control. The workspace is always the
 * server's ({@link Workspaces#LOCAL}); no route accepts one.
 */
@RestController
@RequestMapping("/api")
public class AgentModelController {

    private final AgentModelService service;
    private final ModelProbeService probes;
    private final ModelCallRecordRepository calls;
    private final AgentRuntimeFlags flags;

    public AgentModelController(AgentModelService service, ModelProbeService probes, ModelCallRecordRepository calls,
                                AgentRuntimeFlags flags) {
        this.service = service;
        this.probes = probes;
        this.calls = calls;
        this.flags = flags;
    }

    /** Always available, so the UI can decide whether to show the Agents section. */
    @GetMapping("/agent-runtime/flags")
    public AgentRuntimeFlags flags() {
        return flags;
    }

    @GetMapping("/agents")
    public Map<String, Object> agents(@RequestParam(required = false) String projectId) {
        gate();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("roles", service.effective(ws(), projectId));
        out.put("bindings", service.listBindings(ws()));
        return out;
    }

    @PutMapping("/agent-bindings/{roleId}")
    public AgentModelService.BindingView putBinding(@PathVariable String roleId,
                                                    @RequestBody AgentModelService.BindingRequest req) {
        gate();
        return service.putBinding(ws(), roleId, req);
    }

    @DeleteMapping("/agent-bindings/{roleId}")
    public ResponseEntity<Void> deleteBinding(@PathVariable String roleId,
                                              @RequestParam(defaultValue = "workspace") String scope,
                                              @RequestParam(required = false) String projectId,
                                              @RequestParam(required = false) Long expectedVersion) {
        gate();
        service.deleteBinding(ws(), roleId, scope, projectId, expectedVersion);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/agent-bindings/apply-default")
    public Map<String, Object> applyDefault() {
        gate();
        return Map.of("applied", service.applyDefaultToUnconfigured(ws()));
    }

    @GetMapping("/model-connections")
    public List<AgentModelService.ConnectionView> connections() {
        gate();
        return service.listConnections(ws());
    }

    @PostMapping("/model-connections")
    public ResponseEntity<AgentModelService.ConnectionView> create(@RequestBody AgentModelService.ConnectionRequest req) {
        gate();
        return ResponseEntity.status(HttpStatus.CREATED).body(service.createConnection(ws(), req));
    }

    @PatchMapping("/model-connections/{id}")
    public AgentModelService.ConnectionView update(@PathVariable String id,
                                                   @RequestBody AgentModelService.ConnectionRequest req) {
        gate();
        return service.updateConnection(ws(), id, req);
    }

    @DeleteMapping("/model-connections/{id}")
    public ResponseEntity<Void> delete(@PathVariable String id) {
        gate();
        service.deleteConnection(ws(), id);
        return ResponseEntity.noContent().build();
    }

    public record ProbeRequest(String modelId) { }

    /** Bounded compatibility test: two small calls (text, tool echo), recorded like any other. */
    @PostMapping("/model-connections/{id}/probe")
    public ModelProbeService.Report probe(@PathVariable String id, @RequestBody(required = false) ProbeRequest req) {
        gate();
        return probes.probe(ws(), id, req == null ? null : req.modelId());
    }

    @PostMapping("/model-connections/import-legacy")
    public AgentModelService.ImportResult importLegacy() {
        gate();
        return service.importLegacy(ws());
    }

    /** Recent model calls with their exact bindings (§5.7 "requested and actual models"). */
    @GetMapping("/model-calls")
    public List<ModelCallRecord> recentCalls() {
        gate();
        return calls.findTop50ByWorkspaceIdOrderByCreatedAtDesc(ws());
    }

    // ── errors (§17.1: typed codes, real statuses) ───────────────────────────

    static class FeatureDisabledException extends RuntimeException {
        FeatureDisabledException() {
            super("Per-agent model selection is not enabled (set ANANKE_FEATURE_PER_AGENT_MODELS=true).");
        }
    }

    public record Error(String code, String message, String roleId) { }

    @ExceptionHandler(FeatureDisabledException.class)
    ResponseEntity<Error> disabled(FeatureDisabledException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(new Error("FEATURE_DISABLED", e.getMessage(), null));
    }

    @ExceptionHandler(AgentModelService.ConflictException.class)
    ResponseEntity<Error> conflict(AgentModelService.ConflictException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(new Error("CONFLICT", e.getMessage(), null));
    }

    @ExceptionHandler(org.springframework.orm.ObjectOptimisticLockingFailureException.class)
    ResponseEntity<Error> lost(RuntimeException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new Error("CONFLICT", "Changed elsewhere while saving. Reload and try again.", null));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<Error> invalid(IllegalArgumentException e) {
        return ResponseEntity.unprocessableEntity().body(new Error("INVALID_REQUEST", e.getMessage(), null));
    }

    @ExceptionHandler(NoSuchElementException.class)
    ResponseEntity<Error> missing(NoSuchElementException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(new Error("NOT_FOUND", e.getMessage(), null));
    }

    @ExceptionHandler(ModelResolutionException.class)
    ResponseEntity<Error> unresolved(ModelResolutionException e) {
        return ResponseEntity.unprocessableEntity().body(new Error(e.code().name(), e.getMessage(), e.roleId()));
    }

    private void gate() {
        if (!flags.perAgentModels()) throw new FeatureDisabledException();
    }

    private static String ws() {
        return Workspaces.LOCAL;
    }
}
