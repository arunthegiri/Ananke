package com.stocktracker.agent.model;

import com.stocktracker.agent.model.ModelResolutionException.Code;
import com.stocktracker.agent.model.ResolvedModelBinding.Source;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Resolves each agent role to an immutable {@link ResolvedModelBinding} (spec §5.2, §5.4
 * steps 2–4, §5.6). Pure decision logic over {@link AgentModelStore} and
 * {@link DeploymentDirectory}: it makes no model calls and never consults the legacy
 * process-global provider in {@code LlmSettingsService}.
 *
 * <p>Precedence, applying every check at whichever level wins:
 * <ol>
 *   <li>explicit role override for this run;</li>
 *   <li>project role binding;</li>
 *   <li>workspace role binding;</li>
 *   <li>workspace default ({@code "*"}), when the role permits inheritance or a higher level
 *       explicitly says {@code inherit};</li>
 *   <li>otherwise {@link Code#MODEL_BINDING_REQUIRED} — never a silently chosen hosted model.</li>
 * </ol>
 * The legacy selected provider becomes a workspace default only through the explicit
 * one-time import ({@code LegacySettingsImporter}), not at resolve time.
 */
public class AgentModelResolver {

    /** Probes older than this read as UNVERIFIED (§5.6 "expiry/invalidation rules"). */
    public static final Duration PROBE_TTL = Duration.ofDays(30);

    private final AgentModelStore store;
    private final DeploymentDirectory deployments;
    private final Clock clock;

    public AgentModelResolver(AgentModelStore store, DeploymentDirectory deployments, Clock clock) {
        this.store = store;
        this.deployments = deployments == null ? DeploymentDirectory.NONE : deployments;
        this.clock = clock;
    }

    /** Outcome of resolving several roles at once: every role either binds or has an error. */
    public record Resolution(Map<String, ResolvedModelBinding> bindings,
                             Map<String, ModelResolutionException> errors) {
        public boolean ok() {
            return errors.isEmpty();
        }
    }

    /**
     * Resolves every role, collecting all failures instead of stopping at the first, so a
     * run-creation error (or the settings page) can list each blocked role at once.
     */
    public Resolution resolveAll(String workspaceId, String projectId, Collection<AgentRole> roles,
                                 Map<String, BindingSpec> overrides, Set<TrustZone> permitted) {
        Map<String, ResolvedModelBinding> ok = new LinkedHashMap<>();
        Map<String, ModelResolutionException> errors = new LinkedHashMap<>();
        for (AgentRole role : roles) {
            try {
                ok.put(role.id(), resolve(workspaceId, projectId, role,
                        overrides == null ? null : overrides.get(role.id()), permitted));
            } catch (ModelResolutionException e) {
                errors.put(role.id(), e);
            }
        }
        return new Resolution(Map.copyOf(ok), Map.copyOf(errors));
    }

    public ResolvedModelBinding resolve(String workspaceId, String projectId, AgentRole role,
                                        BindingSpec override, Set<TrustZone> permitted) {
        Objects.requireNonNull(workspaceId, "workspaceId");
        Set<TrustZone> zones = permitted == null ? TrustZone.ANY : permitted;
        Picked picked = pick(workspaceId, projectId, role, override);
        if (picked == null) {
            throw new ModelResolutionException(Code.MODEL_BINDING_REQUIRED, role.id(),
                    "No model is selected for the " + role.label() + " agent. Choose one in Settings → Agents"
                    + (role.inheritsDefault() ? " or set a workspace default." : "."));
        }
        ResolvedModelBinding primary = materialize(workspaceId, role, picked.spec(), picked.source(), zones);

        FallbackPolicy fb = picked.spec().fallback() == null ? FallbackPolicy.DISABLED : picked.spec().fallback();
        if (!fb.enabled()) return primary;

        List<ResolvedModelBinding> fallbacks = new ArrayList<>();
        for (FallbackPolicy.Target t : fb.targets()) {
            if (Objects.equals(t.connectionId(), primary.connectionId())
                    && (t.modelId() == null || Objects.equals(t.modelId(), primary.modelId()))) {
                throw new ModelResolutionException(Code.INVALID_BINDING, role.id(),
                        "A fallback for the " + role.label() + " agent is the same model as its primary.");
            }
            BindingSpec spec = new BindingSpec(false, t.connectionId(), t.modelId(),
                    picked.spec().maxOutputTokens(), FallbackPolicy.DISABLED, null);
            try {
                // A fallback must satisfy exactly the same data policy and capabilities as the
                // primary — it is checked here, before the run starts, not discovered mid-call.
                fallbacks.add(materialize(workspaceId, role, spec, Source.FALLBACK, zones));
            } catch (ModelResolutionException e) {
                throw new ModelResolutionException(e.code(), role.id(),
                        "Fallback " + t.connectionId() + " for the " + role.label() + " agent is not usable: "
                        + e.getMessage());
            }
        }
        return withFallbacks(primary, fallbacks);
    }

    // ── precedence ───────────────────────────────────────────────────────────

    private record Picked(BindingSpec spec, Source source) { }

    private Picked pick(String ws, String projectId, AgentRole role, BindingSpec override) {
        boolean deferToDefault = false;

        if (override != null) {
            if (!override.inheritDefault()) return new Picked(override, Source.RUN_OVERRIDE);
            deferToDefault = true;
        }
        if (!deferToDefault && projectId != null && !projectId.isBlank()) {
            var b = store.binding(ws, AgentModelBinding.Scope.PROJECT, projectId, role.id());
            if (b.isPresent()) {
                if (!b.get().isInheritDefault()) return new Picked(b.get().toSpec(), Source.PROJECT);
                deferToDefault = true;
            }
        }
        if (!deferToDefault) {
            var b = store.binding(ws, AgentModelBinding.Scope.WORKSPACE, "", role.id());
            if (b.isPresent()) {
                if (!b.get().isInheritDefault()) return new Picked(b.get().toSpec(), Source.WORKSPACE_ROLE);
                deferToDefault = true;
            }
        }
        // Implicit inheritance only for roles that allow it; an explicit "inherit" is the
        // user's own choice and still has to pass the capability gate below.
        if (deferToDefault || role.inheritsDefault()) {
            var d = store.binding(ws, AgentModelBinding.Scope.WORKSPACE, "", AgentRole.DEFAULT_KEY);
            if (d.isPresent() && !d.get().isInheritDefault()) {
                return new Picked(d.get().toSpec(), Source.WORKSPACE_DEFAULT);
            }
        }
        return null;
    }

    // ── validation ───────────────────────────────────────────────────────────

    private ResolvedModelBinding materialize(String ws, AgentRole role, BindingSpec spec, Source source,
                                             Set<TrustZone> zones) {
        if (spec.connectionId() == null || spec.connectionId().isBlank()) {
            throw new ModelResolutionException(Code.INVALID_BINDING, role.id(),
                    "The " + role.label() + " agent's binding names no connection.");
        }
        if (spec.maxOutputTokens() != null && spec.maxOutputTokens() <= 0) {
            throw new ModelResolutionException(Code.INVALID_BINDING, role.id(),
                    "Max output tokens must be positive.");
        }

        ModelConnection conn = store.connection(ws, spec.connectionId()).orElseThrow(() ->
                new ModelResolutionException(Code.CONNECTION_NOT_FOUND, role.id(),
                        "Connection " + spec.connectionId() + " no longer exists. Pick another model for the "
                        + role.label() + " agent."));
        if (!conn.isEnabled()) {
            throw new ModelResolutionException(Code.CONNECTION_DISABLED, role.id(),
                    "Connection \"" + conn.getLabel() + "\" is disabled.");
        }
        if (!zones.contains(conn.getTrustZone())) {
            throw new ModelResolutionException(Code.TRUST_ZONE_NOT_PERMITTED, role.id(),
                    "This research is restricted to " + zones + " models; \"" + conn.getLabel() + "\" is "
                    + conn.getTrustZone() + ".");
        }

        String modelId = blankToNull(spec.modelId());
        String deploymentId = null;
        String revision = null;
        if (conn.getAdapter() == Adapter.PACK) {
            deploymentId = conn.getDeploymentId();
            DeploymentDirectory.Deployment d = deployments.describe(deploymentId)
                    .filter(DeploymentDirectory.Deployment::available)
                    .orElseThrow(() -> new ModelResolutionException(Code.DEPLOYMENT_UNAVAILABLE, role.id(),
                            "Selected Pack \"" + conn.getLabel() + "\" is not running. Start it from Packs, or "
                            + "pick another model for the " + role.label() + " agent."));
            revision = d.revision();
            if (modelId == null) modelId = d.servedModel();
            else if (!modelId.equals(d.servedModel())) {
                throw new ModelResolutionException(Code.INVALID_BINDING, role.id(),
                        "Pack \"" + conn.getLabel() + "\" serves " + d.servedModel() + ", not " + modelId + ".");
            }
        }
        if (modelId == null) {
            throw new ModelResolutionException(Code.INVALID_BINDING, role.id(),
                    "Pick a model on \"" + conn.getLabel() + "\" for the " + role.label() + " agent.");
        }

        Map<Capability, Capability.Status> caps = store.capabilities(ws, conn.getId(), conn.getVersion(), modelId,
                revision == null ? "" : revision, clock.instant().minus(PROBE_TTL));
        for (Capability need : role.requires()) {
            Capability.Status have = caps.getOrDefault(need, Capability.Status.UNVERIFIED);
            if (!satisfies(need, have)) {
                throw new ModelResolutionException(Code.CAPABILITY_NOT_VERIFIED, role.id(),
                        modelId + " on \"" + conn.getLabel() + "\" has not passed the " + describe(need)
                        + " probe, which the " + role.label() + " agent needs. Run Test on the connection.");
            }
        }

        return new ResolvedModelBinding(role.id(), source, conn.getId(), conn.getVersion(), conn.getAdapter(),
                conn.getTrustZone(), modelId, deploymentId, revision, spec.maxOutputTokens(), caps, List.of(),
                spec.bindingVersion(), clock.instant());
    }

    /**
     * Plain text is the one capability a model may be bound for without a probe — any chat
     * model produces text, and the first real call proves it. Everything a role relies on
     * beyond that (tools, schemas, images) needs a passed probe for this exact configuration.
     */
    static boolean satisfies(Capability need, Capability.Status have) {
        if (need == Capability.TEXT) return have != Capability.Status.UNSUPPORTED;
        return have == Capability.Status.VERIFIED;
    }

    private static String describe(Capability c) {
        return switch (c) {
            case TOOL_CALLING -> "tool-calling";
            case STRUCTURED_OUTPUT -> "structured-output";
            default -> c.name().toLowerCase();
        };
    }

    private static ResolvedModelBinding withFallbacks(ResolvedModelBinding b, List<ResolvedModelBinding> fbs) {
        return new ResolvedModelBinding(b.roleId(), b.source(), b.connectionId(), b.connectionVersion(), b.adapter(),
                b.trustZone(), b.modelId(), b.deploymentId(), b.deploymentRevision(), b.maxOutputTokens(),
                b.capabilities(), fbs, b.bindingVersion(), b.resolvedAt());
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
