package com.stocktracker.agent.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.security.SecureRandom;
import java.time.Clock;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Live conformance probes for one (connection, model) at its current configuration (§5.6).
 *
 * <p>The tool-calling probe asks the model to call a one-argument echo tool with a random
 * nonce and passes only if it returns exactly that call with exactly that nonce, so a model
 * that answers in prose, invents a tool, or mangles the arguments does not pass. Passing
 * says the serving stack can carry a tool call; it says nothing about research competence.
 *
 * <p>Probes go through {@link ScopedModelClient}, so they are recorded as model calls (role
 * {@code probe}) and hit the same connection/credential/deployment checks as real work.
 */
public class ModelProbeService {

    public static final String PROBE_VERSION = "tool-echo-v1";
    static final String PROBE_ROLE = "probe";
    static final String ECHO_TOOL = "ananke_probe_echo";
    private static final SecureRandom RNG = new SecureRandom();

    private final AgentModelStore store;
    private final DeploymentDirectory deployments;
    private final ModelClientFactory factory;
    private final ObjectMapper mapper;
    private final Clock clock;

    public ModelProbeService(AgentModelStore store, DeploymentDirectory deployments, ModelClientFactory factory,
                             ObjectMapper mapper, Clock clock) {
        this.store = store;
        this.deployments = deployments == null ? DeploymentDirectory.NONE : deployments;
        this.factory = factory;
        this.mapper = mapper;
        this.clock = clock;
    }

    public record Result(Capability capability, Capability.Status status, String detail) { }

    public record Report(String connectionId, long connectionVersion, String modelId, String deploymentRevision,
                         String probeVersion, List<Result> results) { }

    public Report probe(String workspaceId, String connectionId, String requestedModel) {
        ModelConnection conn = store.connection(workspaceId, connectionId)
                .orElseThrow(() -> new java.util.NoSuchElementException("No connection " + connectionId));
        String modelId = requestedModel == null || requestedModel.isBlank() ? null : requestedModel.trim();
        String deploymentId = null;
        String revision = null;
        if (conn.getAdapter() == Adapter.PACK) {
            deploymentId = conn.getDeploymentId();
            DeploymentDirectory.Deployment d = deployments.describe(deploymentId)
                    .filter(DeploymentDirectory.Deployment::available)
                    .orElseThrow(() -> new ModelResolutionException(ModelResolutionException.Code.DEPLOYMENT_UNAVAILABLE,
                            PROBE_ROLE, "Pack \"" + conn.getLabel() + "\" is not running, so it cannot be probed."));
            revision = d.revision();
            modelId = d.servedModel();
        }
        if (modelId == null) throw new IllegalArgumentException("Pick a model to test.");

        ResolvedModelBinding target = new ResolvedModelBinding(PROBE_ROLE, ResolvedModelBinding.Source.PROBE,
                conn.getId(), conn.getVersion(), conn.getAdapter(), conn.getTrustZone(), modelId, deploymentId,
                revision, null, Map.of(), List.of(), null, clock.instant());
        ScopedModelClient client = factory.client(target, CallContext.adHoc(workspaceId, PROBE_ROLE));

        Result text = probeText(client);
        Result tools = text.status() == Capability.Status.VERIFIED
                ? probeToolCall(client)
                : new Result(Capability.TOOL_CALLING, Capability.Status.UNVERIFIED, "Skipped: the text probe failed.");

        for (Result r : List.of(text, tools)) {
            ModelCapabilityProbe p = new ModelCapabilityProbe();
            p.setWorkspaceId(workspaceId);
            p.setConnectionId(conn.getId());
            p.setConnectionVersion(conn.getVersion());
            p.setModelId(modelId);
            p.setDeploymentRevision(revision == null ? "" : revision);
            p.setCapability(r.capability());
            p.setStatus(r.status());
            p.setProbeVersion(PROBE_VERSION);
            p.setDetail(r.detail());
            p.setProbedAt(clock.instant());
            store.saveProbe(p);
        }
        return new Report(conn.getId(), conn.getVersion(), modelId, revision, PROBE_VERSION, List.of(text, tools));
    }

    private Result probeText(ScopedModelClient client) {
        try {
            String reply = client.complete("Reply with exactly: ok", "ping", 16);
            return reply == null || reply.isBlank()
                    ? new Result(Capability.TEXT, Capability.Status.UNVERIFIED, "The model returned no text.")
                    : new Result(Capability.TEXT, Capability.Status.VERIFIED, "Replied.");
        } catch (RuntimeException e) {
            return new Result(Capability.TEXT, Capability.Status.UNVERIFIED, Redaction.scrub(e.getMessage()));
        }
    }

    Result probeToolCall(ScopedModelClient client) {
        String nonce = "ananke-" + HexFormat.of().formatHex(randomBytes());
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("value", Map.of("type", "string", "description", "The exact value to echo."));
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("type", "object");
        params.put("properties", props);
        params.put("required", List.of("value"));
        Map<String, Object> fn = new LinkedHashMap<>();
        fn.put("name", ECHO_TOOL);
        fn.put("description", "Echoes a value back. Used to test tool calling.");
        fn.put("parameters", params);
        List<Map<String, Object>> tools = List.of(Map.of("type", "function", "function", fn));
        List<Map<String, Object>> messages = new java.util.ArrayList<>(List.of(
                Map.of("role", "system", "content", "You are a tool-calling conformance test. Respond only with a tool call."),
                Map.of("role", "user", "content", "Call " + ECHO_TOOL + " with value \"" + nonce + "\".")));
        try {
            JsonNode root = client.chat(messages, tools, 256);
            JsonNode calls = root.path("choices").path(0).path("message").path("tool_calls");
            if (!calls.isArray() || calls.isEmpty()) {
                return new Result(Capability.TOOL_CALLING, Capability.Status.UNVERIFIED,
                        "The model answered without a tool call (finish_reason="
                        + root.path("choices").path(0).path("finish_reason").asText("?") + ").");
            }
            JsonNode call = calls.get(0).path("function");
            if (!ECHO_TOOL.equals(call.path("name").asText())) {
                return new Result(Capability.TOOL_CALLING, Capability.Status.UNVERIFIED,
                        "The model called an unexpected tool.");
            }
            JsonNode args = mapper.readTree(call.path("arguments").asText("{}"));
            if (!nonce.equals(args.path("value").asText())) {
                return new Result(Capability.TOOL_CALLING, Capability.Status.UNVERIFIED,
                        "The tool call's arguments did not match the request.");
            }
            return new Result(Capability.TOOL_CALLING, Capability.Status.VERIFIED,
                    "Returned a well-formed " + ECHO_TOOL + " call.");
        } catch (Exception e) {
            return new Result(Capability.TOOL_CALLING, Capability.Status.UNVERIFIED,
                    Redaction.scrub("Tool-call probe failed: " + e.getMessage()));
        }
    }

    private static byte[] randomBytes() {
        byte[] b = new byte[6];
        RNG.nextBytes(b);
        return b;
    }
}
