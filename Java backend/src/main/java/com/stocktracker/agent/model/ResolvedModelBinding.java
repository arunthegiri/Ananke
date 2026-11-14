package com.stocktracker.agent.model;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * The immutable snapshot a run records for one role (§5.2): exactly which connection
 * version, model and deployment revision, with which limits, capability record and
 * fallbacks. Credentials are <em>not</em> in it — they are resolved on every call so
 * revocation works — and nothing global is consulted after it is built, so changing the
 * legacy Settings provider cannot move a running role (§5.7 acceptance).
 *
 * @param fallbacks pre-resolved, pre-validated fallback snapshots (never nested further).
 */
public record ResolvedModelBinding(
        String roleId,
        Source source,
        String connectionId,
        long connectionVersion,
        Adapter adapter,
        TrustZone trustZone,
        String modelId,
        String deploymentId,
        String deploymentRevision,
        Integer maxOutputTokens,
        Map<Capability, Capability.Status> capabilities,
        List<ResolvedModelBinding> fallbacks,
        Long bindingVersion,
        Instant resolvedAt) {

    /** Which precedence level supplied the binding (§5.2). */
    public enum Source { RUN_OVERRIDE, PROJECT, WORKSPACE_ROLE, WORKSPACE_DEFAULT, FALLBACK, PROBE }

    public ResolvedModelBinding {
        capabilities = Map.copyOf(capabilities);
        fallbacks = List.copyOf(fallbacks);
    }

    /** "connectionId/modelId@revision" — used in lineage and fallback records. */
    public String label() {
        return connectionId + "/" + modelId + (deploymentRevision == null ? "" : "@" + deploymentRevision);
    }

    /** Caps a caller's requested output budget at this binding's limit, when it has one. */
    public int clampOutputTokens(int requested) {
        return maxOutputTokens == null ? requested : Math.min(requested, maxOutputTokens);
    }
}
