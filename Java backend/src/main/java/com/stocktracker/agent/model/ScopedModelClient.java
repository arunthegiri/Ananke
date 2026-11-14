package com.stocktracker.agent.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.stocktracker.agent.model.ModelResolutionException.Code;
import com.stocktracker.service.LLMClient;

import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * An {@link LLMClient} fixed to one {@link ResolvedModelBinding} and one {@link CallContext}
 * (§5.5 "immutable resolved client wrapper"). {@link #model()} is the snapshot's model, not
 * a mutable global selection, so it cannot change while a call is in flight. Existing code
 * written against {@code LLMClient} — {@code AgentToolLoop} included — runs on it unchanged.
 *
 * <p>Every attempted call is recorded (§1.1 outcome 1) before its result is returned. On
 * failure it tries only the binding's explicit, pre-validated fallbacks, recording each
 * with the model it replaced (§5.4 steps 9–10); with fallback disabled it fails with the
 * actionable reason.
 *
 * <p>Retries are deliberately not here: retry policy belongs to the task (§8.4), which
 * arrives with the durable runtime in M2.
 */
public final class ScopedModelClient implements LLMClient {

    private final ResolvedModelBinding binding;
    private final CallContext ctx;
    private final ModelClientFactory factory;
    private final ModelCallRecorder recorder;
    private final ObjectMapper canonical;
    private final Clock clock;

    ScopedModelClient(ResolvedModelBinding binding, CallContext ctx, ModelClientFactory factory,
                      ModelCallRecorder recorder, ObjectMapper mapper, Clock clock) {
        this.binding = binding;
        this.ctx = ctx;
        this.factory = factory;
        this.recorder = recorder;
        this.canonical = mapper.copy().configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);
        this.clock = clock;
    }

    public ResolvedModelBinding binding() {
        return binding;
    }

    @Override
    public boolean isConfigured() {
        try {
            factory.open(ctx.workspaceId(), binding);
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    @Override
    public String model() {
        return binding.modelId();
    }

    @Override
    public JsonNode chat(List<Map<String, Object>> messages, List<Map<String, Object>> tools, int maxTokens) {
        List<ResolvedModelBinding> chain = new ArrayList<>(1 + binding.fallbacks().size());
        chain.add(binding);
        chain.addAll(binding.fallbacks());

        List<String> failures = new ArrayList<>();
        ModelResolutionException firstResolutionError = null;
        String failedLabel = null;

        for (ResolvedModelBinding b : chain) {
            int limit = b.clampOutputTokens(maxTokens);
            ModelClientFactory.Opened opened;
            try {
                opened = factory.open(ctx.workspaceId(), b);
            } catch (ModelResolutionException e) {
                if (firstResolutionError == null) firstResolutionError = e;
                failures.add(b.label() + ": " + e.getMessage());
                failedLabel = failedLabel == null ? b.label() : failedLabel;
                continue;
            }

            String manifest = manifestHash(b, messages, tools, limit);
            long t0 = clock.millis();
            try {
                JsonNode root = opened.client().chat(messages, tools, limit);
                recorder.record(record(b, manifest, clock.millis() - t0, failedLabel, root, null));
                return root;
            } catch (RuntimeException e) {
                String detail = Redaction.scrub(rootMessage(e), opened.secret());
                recorder.record(record(b, manifest, clock.millis() - t0, failedLabel, null, detail));
                failures.add(b.label() + ": " + detail);
                failedLabel = failedLabel == null ? b.label() : failedLabel;
            }
        }

        if (chain.size() == 1 && firstResolutionError != null) throw firstResolutionError;
        throw new ModelResolutionException(Code.MODEL_CALL_FAILED, binding.roleId(),
                "The " + binding.roleId() + " agent's model call failed"
                + (binding.fallbacks().isEmpty() ? " (fallback disabled)" : "") + ": " + String.join("; ", failures));
    }

    @Override
    public String complete(String systemPrompt, String userPrompt, int maxTokens) {
        List<Map<String, Object>> messages = new ArrayList<>();
        messages.add(Map.of("role", "system", "content", systemPrompt));
        messages.add(Map.of("role", "user", "content", userPrompt));
        return chat(messages, List.of(), maxTokens).path("choices").path(0).path("message").path("content").asText("");
    }

    // ── lineage ──────────────────────────────────────────────────────────────

    private ModelCallRecord record(ResolvedModelBinding b, String manifest, long latencyMs, String fallbackFrom,
                                   JsonNode root, String error) {
        ModelCallRecord r = new ModelCallRecord();
        r.setWorkspaceId(ctx.workspaceId());
        r.setRunId(ctx.runId());
        r.setTaskId(ctx.taskId());
        r.setRoleId(ctx.roleId() == null ? b.roleId() : ctx.roleId());
        r.setConnectionId(b.connectionId());
        r.setConnectionVersion(b.connectionVersion());
        r.setModelId(b.modelId());
        r.setDeploymentRevision(b.deploymentRevision());
        r.setFallbackFrom(fallbackFrom);
        r.setInputManifestSha256(manifest);
        r.setLatencyMs(latencyMs);
        r.setCreatedAt(clock.instant());
        if (root != null) {
            r.setOutcome(ModelCallRecord.Outcome.OK);
            JsonNode usage = root.path("usage");
            r.setInputTokens(intOrNull(usage.path("prompt_tokens")));
            r.setOutputTokens(intOrNull(usage.path("completion_tokens")));
            String id = root.path("id").asText("");
            r.setProviderRequestId(id.isBlank() ? null : truncate(id, 200));
            String finish = root.path("choices").path(0).path("finish_reason").asText("");
            r.setFinishReason(finish.isBlank() ? null : truncate(finish, 40));
        } else {
            r.setOutcome(ModelCallRecord.Outcome.FAILED);
            r.setErrorDetail(error);
        }
        return r;
    }

    /**
     * Hash of exactly what was sent: model, output limit, messages and tool definitions,
     * serialised with sorted keys. The full context manifest (which skills, memories and
     * artifacts went in) is a run-level record that arrives with M2; this hash is what ties
     * a call to it.
     */
    private String manifestHash(ResolvedModelBinding b, List<Map<String, Object>> messages,
                                List<Map<String, Object>> tools, int maxTokens) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("model", b.modelId());
        m.put("maxTokens", maxTokens);
        m.put("messages", messages);
        m.put("tools", tools == null ? List.of() : tools);
        try {
            return ModelClientFactory.sha256(canonical.writeValueAsString(m));
        } catch (Exception e) {
            return ModelClientFactory.sha256(String.valueOf(m));
        }
    }

    private static Integer intOrNull(JsonNode n) {
        return n.isIntegralNumber() ? n.asInt() : null;
    }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max);
    }

    private static String rootMessage(Throwable e) {
        Throwable t = e;
        while (t.getCause() != null && t.getCause() != t) t = t.getCause();
        String m = t.getMessage();
        return m == null || m.isBlank() ? t.getClass().getSimpleName() : m;
    }
}
