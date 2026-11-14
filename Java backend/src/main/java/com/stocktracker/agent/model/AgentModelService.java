package com.stocktracker.agent.model;

import com.stocktracker.service.LlmSettingsService;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;

/**
 * Settings-side management of connections and bindings (§5.1–§5.3, §5.7, §17.1). The
 * workspace always comes from the server; the caller names a role and a connection id,
 * never a credential reference.
 */
public class AgentModelService {

    /** Optimistic-concurrency or state conflict; the controller maps it to 409. */
    public static class ConflictException extends RuntimeException {
        public ConflictException(String message) {
            super(message);
        }
    }

    private static final SecureRandom RNG = new SecureRandom();

    private final ModelConnectionRepository connections;
    private final AgentModelBindingRepository bindings;
    private final CredentialStore credentials;
    private final DeploymentDirectory deployments;
    private final AgentModelResolver resolver;
    private final LlmSettingsService legacy;

    public AgentModelService(ModelConnectionRepository connections, AgentModelBindingRepository bindings,
                             CredentialStore credentials, DeploymentDirectory deployments,
                             AgentModelResolver resolver, LlmSettingsService legacy) {
        this.connections = connections;
        this.bindings = bindings;
        this.credentials = credentials;
        this.deployments = deployments == null ? DeploymentDirectory.NONE : deployments;
        this.resolver = resolver;
        this.legacy = legacy;
    }

    // ── connections ──────────────────────────────────────────────────────────

    public record ConnectionView(String id, String label, Adapter adapter, String presetId, String baseUrl,
                                 String deploymentId, String deploymentState, TrustZone trustZone,
                                 boolean keyRequired, String keyHint, boolean enabled, long version) { }

    public record ConnectionRequest(String label, Adapter adapter, String presetId, String baseUrl,
                                    String deploymentId, String apiKey, Boolean enabled, Long expectedVersion) { }

    public List<ConnectionView> listConnections(String ws) {
        return connections.findAllByWorkspaceIdOrderByCreatedAtAsc(ws).stream().map(this::view).toList();
    }

    public ConnectionView getConnection(String ws, String id) {
        return view(requireConnection(ws, id));
    }

    @Transactional
    public ConnectionView createConnection(String ws, ConnectionRequest req) {
        if (req.adapter() == null) throw new IllegalArgumentException("Choose an adapter.");
        ModelConnection c = new ModelConnection();
        c.setId("conn-" + HexFormat.of().formatHex(randomBytes(5)));
        c.setWorkspaceId(ws);
        c.setAdapter(req.adapter());
        c.setPresetId(blankToNull(req.presetId()));

        if (req.adapter() == Adapter.PACK) {
            String dep = blankToNull(req.deploymentId());
            DeploymentDirectory.Deployment d = dep == null ? null : deployments.describe(dep).orElse(null);
            if (d == null) throw new IllegalArgumentException("Choose a Pack to connect.");
            c.setDeploymentId(d.deploymentId());
            c.setLabel(label(req.label(), d.label()));
            c.setKeyRequired(false);
        } else {
            Optional<LlmSettingsService.Preset> preset = Optional.ofNullable(c.getPresetId())
                    .flatMap(LlmSettingsService::preset);
            String base = blankToNull(req.baseUrl());
            if (base == null) base = preset.map(LlmSettingsService.Preset::defaultBaseUrl).map(AgentModelService::blankToNull).orElse(null);
            if (base == null) throw new IllegalArgumentException("A base URL is required.");
            validateBaseUrl(base);
            c.setBaseUrl(base);
            c.setLabel(label(req.label(), preset.map(LlmSettingsService.Preset::label).orElse(null)));
            boolean local = TrustZone.isLocalHost(base);
            c.setKeyRequired(preset.map(LlmSettingsService.Preset::keyRequired).orElse(!local) && !local);
        }
        c.setTrustZone(TrustZone.derive(c.getAdapter(), c.getBaseUrl()));
        c.setEnabled(req.enabled() == null || req.enabled());
        c = connections.saveAndFlush(c);
        if (req.apiKey() != null && !req.apiKey().isBlank() && c.getAdapter() != Adapter.PACK) {
            credentials.put(c.secretScope(), ModelConnection.SECRET_KIND, req.apiKey().trim());
        }
        return view(c);
    }

    /**
     * Edits a connection. Endpoint/label/enabled changes bump its version (and so invalidate
     * probes and in-flight snapshots); an API key change alone does not — it applies to the
     * next call everywhere. {@code apiKey}: null = unchanged, "" = remove.
     */
    @Transactional
    public ConnectionView updateConnection(String ws, String id, ConnectionRequest req) {
        ModelConnection c = requireConnection(ws, id);
        checkVersion(req.expectedVersion(), c.getVersion(), "connection");
        boolean dirty = false;
        if (req.label() != null && !req.label().isBlank() && !req.label().trim().equals(c.getLabel())) {
            c.setLabel(label(req.label(), null));
            dirty = true;
        }
        if (req.baseUrl() != null && c.getAdapter() != Adapter.PACK) {
            String base = blankToNull(req.baseUrl());
            if (base == null) throw new IllegalArgumentException("A base URL is required.");
            validateBaseUrl(base);
            if (!base.equals(c.getBaseUrl())) {
                c.setBaseUrl(base);
                c.setTrustZone(TrustZone.derive(c.getAdapter(), base));
                if (TrustZone.isLocalHost(base)) c.setKeyRequired(false);
                dirty = true;
            }
        }
        if (req.enabled() != null && req.enabled() != c.isEnabled()) {
            c.setEnabled(req.enabled());
            dirty = true;
        }
        if (dirty) c = connections.saveAndFlush(c);
        if (req.apiKey() != null && c.getAdapter() != Adapter.PACK) {
            if (req.apiKey().isBlank()) credentials.delete(c.secretScope(), ModelConnection.SECRET_KIND);
            else credentials.put(c.secretScope(), ModelConnection.SECRET_KIND, req.apiKey().trim());
        }
        return view(c);
    }

    @Transactional
    public void deleteConnection(String ws, String id) {
        ModelConnection c = requireConnection(ws, id);
        List<String> users = new ArrayList<>();
        for (AgentModelBinding b : bindings.findAllByWorkspaceId(ws)) {
            boolean primary = id.equals(b.getConnectionId());
            boolean fallback = b.getFallbackTargets() != null && b.getFallbackTargets().stream()
                    .anyMatch(t -> id.equals(t.get("connectionId")));
            if (primary || fallback) users.add(b.getRoleId().equals(AgentRole.DEFAULT_KEY) ? "workspace default" : b.getRoleId());
        }
        if (!users.isEmpty()) {
            throw new ConflictException("\"" + c.getLabel() + "\" is still used by: " + String.join(", ", users)
                    + ". Rebind those agents first.");
        }
        credentials.delete(c.secretScope(), ModelConnection.SECRET_KIND);
        connections.delete(c);
    }

    // ── bindings ─────────────────────────────────────────────────────────────

    public record BindingRequest(String scope, String projectId, Boolean inherit, String connectionId,
                                 String modelId, Integer maxOutputTokens, String fallbackMode,
                                 List<FallbackPolicy.Target> fallbackTargets, Long expectedVersion) { }

    public record BindingView(String roleId, String scope, String projectId, boolean inherit, String connectionId,
                              String modelId, Integer maxOutputTokens, FallbackPolicy fallback, long version) { }

    public List<BindingView> listBindings(String ws) {
        return bindings.findAllByWorkspaceId(ws).stream().map(AgentModelService::view).toList();
    }

    @Transactional
    public BindingView putBinding(String ws, String roleId, BindingRequest req) {
        boolean isDefault = AgentRole.DEFAULT_KEY.equals(roleId);
        if (!isDefault && AgentRole.find(roleId).isEmpty()) throw new NoSuchElementException("Unknown agent role: " + roleId);
        AgentModelBinding.Scope scope = scope(req.scope());
        String scopeId = scopeId(scope, req.projectId());
        if (isDefault && scope != AgentModelBinding.Scope.WORKSPACE) {
            throw new IllegalArgumentException("The default binding is workspace-wide.");
        }
        boolean inherit = Boolean.TRUE.equals(req.inherit());
        if (isDefault && inherit) throw new IllegalArgumentException("The workspace default cannot inherit.");

        AgentModelBinding b = bindings.findByWorkspaceIdAndScopeTypeAndScopeIdAndRoleId(ws, scope, scopeId, roleId)
                .orElse(null);
        if (b == null) {
            if (req.expectedVersion() != null) throw new ConflictException("This binding was removed by another change.");
            b = new AgentModelBinding();
            b.setWorkspaceId(ws);
            b.setScopeType(scope);
            b.setScopeId(scopeId);
            b.setRoleId(roleId);
        } else {
            checkVersion(req.expectedVersion(), b.getVersion(), "binding");
        }

        b.setInheritDefault(inherit);
        if (inherit) {
            b.setConnectionId(null);
            b.setModelId(null);
            b.setFallbackMode(FallbackPolicy.Mode.DISABLED);
            b.setFallbackTargets(new ArrayList<>());
        } else {
            requireConnection(ws, req.connectionId());
            b.setConnectionId(req.connectionId());
            b.setModelId(blankToNull(req.modelId()));
            FallbackPolicy.Mode mode = req.fallbackMode() == null ? FallbackPolicy.Mode.DISABLED
                    : FallbackPolicy.Mode.valueOf(req.fallbackMode().trim().toUpperCase());
            List<Map<String, String>> targets = new ArrayList<>();
            if (mode == FallbackPolicy.Mode.EXPLICIT) {
                if (req.fallbackTargets() == null || req.fallbackTargets().isEmpty()) {
                    throw new IllegalArgumentException("An explicit fallback needs at least one target.");
                }
                for (FallbackPolicy.Target t : req.fallbackTargets()) {
                    requireConnection(ws, t.connectionId());
                    Map<String, String> m = new LinkedHashMap<>();
                    m.put("connectionId", t.connectionId());
                    if (t.modelId() != null && !t.modelId().isBlank()) m.put("modelId", t.modelId().trim());
                    targets.add(m);
                }
            }
            b.setFallbackMode(mode);
            b.setFallbackTargets(targets);
        }
        if (req.maxOutputTokens() != null && req.maxOutputTokens() <= 0) {
            throw new IllegalArgumentException("Max output tokens must be positive.");
        }
        b.setMaxOutputTokens(req.maxOutputTokens());
        return view(bindings.saveAndFlush(b));
    }

    @Transactional
    public void deleteBinding(String ws, String roleId, String scopeName, String projectId, Long expectedVersion) {
        AgentModelBinding.Scope scope = scope(scopeName);
        AgentModelBinding b = bindings.findByWorkspaceIdAndScopeTypeAndScopeIdAndRoleId(
                ws, scope, scopeId(scope, projectId), roleId).orElseThrow(() ->
                new NoSuchElementException("No " + scope.name().toLowerCase() + " binding for " + roleId));
        checkVersion(expectedVersion, b.getVersion(), "binding");
        bindings.delete(b);
    }

    /**
     * "Apply default to unconfigured roles" (§5.7): adds an explicit {@code inherit} binding
     * for each role that has no workspace binding. Never overwrites an explicit choice.
     */
    @Transactional
    public List<String> applyDefaultToUnconfigured(String ws) {
        if (bindings.findByWorkspaceIdAndScopeTypeAndScopeIdAndRoleId(ws, AgentModelBinding.Scope.WORKSPACE, "",
                AgentRole.DEFAULT_KEY).isEmpty()) {
            throw new ConflictException("Set a workspace default first.");
        }
        List<String> applied = new ArrayList<>();
        for (AgentRole r : AgentRole.ALL) {
            if (bindings.findByWorkspaceIdAndScopeTypeAndScopeIdAndRoleId(ws, AgentModelBinding.Scope.WORKSPACE, "",
                    r.id()).isPresent()) continue;
            AgentModelBinding b = new AgentModelBinding();
            b.setWorkspaceId(ws);
            b.setScopeType(AgentModelBinding.Scope.WORKSPACE);
            b.setScopeId("");
            b.setRoleId(r.id());
            b.setInheritDefault(true);
            bindings.save(b);
            applied.add(r.id());
        }
        return applied;
    }

    // ── effective configuration ──────────────────────────────────────────────

    public record RoleStatus(AgentRole role, String state, ResolvedModelBinding binding, String errorCode,
                             String error) { }

    /** What each role would run on right now (§5.7 "one row per role"). Makes no model calls. */
    public List<RoleStatus> effective(String ws, String projectId) {
        AgentModelResolver.Resolution res = resolver.resolveAll(ws, projectId, AgentRole.ALL, Map.of(), TrustZone.ANY);
        List<RoleStatus> out = new ArrayList<>();
        for (AgentRole r : AgentRole.ALL) {
            ResolvedModelBinding b = res.bindings().get(r.id());
            ModelResolutionException e = res.errors().get(r.id());
            out.add(b != null ? new RoleStatus(r, "READY", b, null, null)
                    : new RoleStatus(r, "BLOCKED", null, e.code().name(), e.getMessage()));
        }
        return out;
    }

    // ── legacy import (§5.2 step 5, §5.5) ────────────────────────────────────

    public record ImportResult(ConnectionView connection, BindingView defaultBinding) { }

    /**
     * One-time compatibility import of the provider chosen on the legacy Settings page: a
     * connection (its key copied into the encrypted vault) plus the workspace default
     * binding. Refuses when a default already exists, so it cannot overwrite a choice made
     * here. The legacy file keeps its copy for now because the legacy Copilot path still
     * reads it; removing that plaintext needs {@code LlmSettingsService} to read from the
     * vault, which is a coordinated change (spec §4) tracked in the M1 notes.
     */
    @Transactional
    public ImportResult importLegacy(String ws) {
        if (bindings.findByWorkspaceIdAndScopeTypeAndScopeIdAndRoleId(ws, AgentModelBinding.Scope.WORKSPACE, "",
                AgentRole.DEFAULT_KEY).isPresent()) {
            throw new ConflictException("A workspace default is already set; nothing was imported.");
        }
        String active = legacy.activeProvider();
        if (active == null) {
            throw new IllegalArgumentException("No provider is selected on the legacy Settings page; nothing to import.");
        }
        ConnectionView conn;
        String model;
        if (active.startsWith("pack:")) {
            conn = createConnection(ws, new ConnectionRequest(null, Adapter.PACK, null, null, active, null, true, null));
            model = null;
        } else {
            LlmSettingsService.Preset p = LlmSettingsService.preset(active).orElseThrow(() ->
                    new IllegalArgumentException("Unknown legacy provider " + active));
            Adapter adapter = p.kind() == LlmSettingsService.Kind.ANTHROPIC ? Adapter.ANTHROPIC : Adapter.OPENAI_COMPATIBLE;
            String key = legacy.keyFor(active);
            conn = createConnection(ws, new ConnectionRequest(p.label() + " (imported)", adapter, p.id(),
                    legacy.baseUrlFor(active), null, key.isBlank() ? null : key, true, null));
            // Round-trip check before anything depends on the copied key.
            if (!key.isBlank() && !key.trim().equals(credentials.get("model-conn:" + conn.id(), ModelConnection.SECRET_KIND).orElse(null))) {
                throw new IllegalStateException("The imported key did not round-trip through the vault.");
            }
            model = blankToNull(legacy.modelFor(active));
        }
        BindingView def = putBinding(ws, AgentRole.DEFAULT_KEY, new BindingRequest("workspace", null, false,
                conn.id(), model, null, null, null, null));
        return new ImportResult(conn, def);
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private ModelConnection requireConnection(String ws, String id) {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("Choose a connection.");
        return connections.findByIdAndWorkspaceId(id, ws)
                .orElseThrow(() -> new NoSuchElementException("No connection " + id));
    }

    private ConnectionView view(ModelConnection c) {
        String state = null;
        if (c.getAdapter() == Adapter.PACK) {
            state = deployments.describe(c.getDeploymentId()).map(DeploymentDirectory.Deployment::state).orElse("MISSING");
        }
        String hint = c.getAdapter() == Adapter.PACK ? null
                : credentials.hint(c.secretScope(), ModelConnection.SECRET_KIND).orElse(null);
        return new ConnectionView(c.getId(), c.getLabel(), c.getAdapter(), c.getPresetId(), c.getBaseUrl(),
                c.getDeploymentId(), state, c.getTrustZone(), c.isKeyRequired(), hint, c.isEnabled(), c.getVersion());
    }

    private static BindingView view(AgentModelBinding b) {
        return new BindingView(b.getRoleId(), b.getScopeType().name().toLowerCase(),
                b.getScopeType() == AgentModelBinding.Scope.PROJECT ? b.getScopeId() : null, b.isInheritDefault(),
                b.getConnectionId(), b.getModelId(), b.getMaxOutputTokens(), b.toSpec().fallback(), b.getVersion());
    }

    private static void checkVersion(Long expected, long actual, String what) {
        if (expected != null && expected != actual) {
            throw new ConflictException("This " + what + " was changed elsewhere (version " + actual
                    + ", you had " + expected + "). Reload and try again.");
        }
    }

    private static AgentModelBinding.Scope scope(String s) {
        if (s == null || s.isBlank() || s.equalsIgnoreCase("workspace")) return AgentModelBinding.Scope.WORKSPACE;
        if (s.equalsIgnoreCase("project")) return AgentModelBinding.Scope.PROJECT;
        throw new IllegalArgumentException("Scope must be workspace or project.");
    }

    private static String scopeId(AgentModelBinding.Scope scope, String projectId) {
        if (scope == AgentModelBinding.Scope.WORKSPACE) return "";
        String p = blankToNull(projectId);
        if (p == null || p.length() > 64) throw new IllegalArgumentException("A project binding needs a project id.");
        return p;
    }

    private static String label(String requested, String fallback) {
        String l = blankToNull(requested);
        if (l == null) l = blankToNull(fallback);
        if (l == null) throw new IllegalArgumentException("Give the connection a name.");
        if (l.length() > 100) throw new IllegalArgumentException("Names are limited to 100 characters.");
        return l;
    }

    /** Only http(s) with a host; the URL is fetched server-side. Mirrors LlmSettingsService. */
    static void validateBaseUrl(String url) {
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
        if (u.getUserInfo() != null) throw new IllegalArgumentException("Put credentials in the API key field, not the URL.");
    }

    static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private static byte[] randomBytes(int n) {
        byte[] b = new byte[n];
        RNG.nextBytes(b);
        return b;
    }
}
