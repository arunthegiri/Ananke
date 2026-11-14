package com.stocktracker.agent.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stocktracker.agent.model.ModelResolutionException.Code;
import com.stocktracker.service.AnthropicClient;
import com.stocktracker.service.KimiClient;
import com.stocktracker.service.LLMClient;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Builds per-binding clients (§5.4 steps 2 and 6). {@link #client} returns a
 * {@link ScopedModelClient} fixed to one snapshot; {@link #open} is what that client calls on
 * every request to re-check the connection and fetch the credential.
 *
 * <p>Underlying HTTP clients are cached by workspace, connection id <em>and version</em>, model,
 * deployment revision and a hash of the current credential — never by a provider label — so
 * two connections to the same provider cannot share a client, and a rotated key gets a new
 * one. There is no "active provider" anywhere in this class.
 */
public class ModelClientFactory {

    private static final int CACHE_SIZE = 64;

    private final AgentModelStore store;
    private final CredentialStore credentials;
    private final DeploymentDirectory deployments;
    private final ModelCallRecorder recorder;
    private final ObjectMapper mapper;
    private final Clock clock;

    private final Map<String, LLMClient> cache = new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, LLMClient> e) {
            return size() > CACHE_SIZE;
        }
    };

    public ModelClientFactory(AgentModelStore store, CredentialStore credentials, DeploymentDirectory deployments,
                              ModelCallRecorder recorder, ObjectMapper mapper, Clock clock) {
        this.store = store;
        this.credentials = credentials;
        this.deployments = deployments == null ? DeploymentDirectory.NONE : deployments;
        this.recorder = recorder;
        this.mapper = mapper;
        this.clock = clock;
    }

    /** A client bound to {@code binding} for one caller; safe to share across threads. */
    public ScopedModelClient client(ResolvedModelBinding binding, CallContext ctx) {
        return new ScopedModelClient(binding, ctx, this, recorder, mapper, clock);
    }

    /** What {@link #open} hands back: the live client plus the secret, for error scrubbing. */
    record Opened(LLMClient client, String secret) { }

    /**
     * Re-validates the snapshot against current state and returns a client for one call.
     * Fails closed: a disabled or edited connection, revoked credential, or stopped/changed
     * deployment is an error, never a reason to pick something else.
     */
    Opened open(String workspaceId, ResolvedModelBinding b) {
        ModelConnection conn = store.connection(workspaceId, b.connectionId()).orElseThrow(() ->
                new ModelResolutionException(Code.CONNECTION_NOT_FOUND, b.roleId(),
                        "Connection " + b.connectionId() + " was removed."));
        if (!conn.isEnabled()) {
            throw new ModelResolutionException(Code.CONNECTION_DISABLED, b.roleId(),
                    "Connection \"" + conn.getLabel() + "\" was disabled.");
        }
        if (conn.getVersion() != b.connectionVersion()) {
            throw new ModelResolutionException(Code.CONNECTION_CHANGED, b.roleId(),
                    "Connection \"" + conn.getLabel() + "\" was edited after this work started; rebind the "
                    + b.roleId() + " agent to continue with the new settings.");
        }

        if (conn.getAdapter() == Adapter.PACK) {
            DeploymentDirectory.Deployment d = deployments.describe(b.deploymentId())
                    .filter(DeploymentDirectory.Deployment::available)
                    .orElseThrow(() -> new ModelResolutionException(Code.DEPLOYMENT_UNAVAILABLE, b.roleId(),
                            "Selected Pack \"" + conn.getLabel() + "\" is offline."));
            if (!d.revision().equals(b.deploymentRevision())) {
                throw new ModelResolutionException(Code.DEPLOYMENT_CHANGED, b.roleId(),
                        "Pack \"" + conn.getLabel() + "\" was redeployed with a different model or configuration.");
            }
            LLMClient c = deployments.clientFor(b.deploymentId()).orElseThrow(() ->
                    new ModelResolutionException(Code.DEPLOYMENT_UNAVAILABLE, b.roleId(),
                            "Selected Pack \"" + conn.getLabel() + "\" is offline."));
            return new Opened(c, null);
        }

        String key = credentials.get(conn.secretScope(), ModelConnection.SECRET_KIND).orElse("");
        if (conn.isKeyRequired() && key.isBlank()) {
            throw new ModelResolutionException(Code.CREDENTIAL_REVOKED, b.roleId(),
                    "Connection \"" + conn.getLabel() + "\" has no API key. Add one in Settings → Agents.");
        }
        String cacheKey = String.join("\u0000", workspaceId, conn.getId(), Long.toString(conn.getVersion()),
                b.modelId(), String.valueOf(b.deploymentRevision()), sha256(key));
        LLMClient c;
        synchronized (cache) {
            c = cache.computeIfAbsent(cacheKey, k -> build(conn, b.modelId(), key));
        }
        return new Opened(c, key);
    }

    /** Builds the underlying adapter client for an arbitrary (connection, model) — also used by probes. */
    static LLMClient build(ModelConnection conn, String modelId, String key) {
        return switch (conn.getAdapter()) {
            case ANTHROPIC -> new AnthropicClient(key, conn.getBaseUrl(), modelId);
            // KimiClient is the generic OpenAI-compatible client; the empty legacy KIMI_* key keeps
            // its env fallback from ever replacing this connection's credential.
            case OPENAI_COMPATIBLE -> new KimiClient(key, conn.getBaseUrl(), modelId, "", conn.getBaseUrl(), modelId);
            case PACK -> throw new IllegalStateException("Pack clients come from the deployment directory");
        };
    }

    static String sha256(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
