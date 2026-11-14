package com.stocktracker.connectors;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.Set;

/**
 * The one path every connector call takes, for panels and agents alike (Connectors roadmap §1):
 * linked? enabled? → cache → call (each upstream request rate-limited) → record health. Also owns linking: fields are
 * tested live before they are saved, so a wrong key is rejected at the form, not at first use.
 */
@Slf4j
public class ConnectorService {

    /** What a tool call returns; {@code observedAt} is when Ananke fetched it (§0.6 point-in-time). Named {@code payload}, not {@code data}: the web client unwraps {@code {data}} envelopes. */
    public record Result(String connector, String source, String tool, String attribution,
                         Instant observedAt, boolean cached, Object payload) { }

    public enum Status { READY, NOT_LINKED, DISABLED, ERROR }

    private final Map<String, Connector> connectors;
    private final ConnectorStore store;
    private final RateLimiter limiter;
    private final ResponseCache cache;
    private final HttpClient http;
    private final ObjectMapper mapper;
    private final Clock clock;

    public ConnectorService(List<Connector> connectors, ConnectorStore store, RateLimiter limiter, ResponseCache cache,
                            HttpClient http, ObjectMapper mapper, Clock clock) {
        Map<String, Connector> byId = new LinkedHashMap<>();
        Set<String> toolNames = new java.util.HashSet<>();
        for (Connector c : connectors) {
            String id = c.spec().id();
            if (byId.putIfAbsent(id, c) != null) throw new IllegalStateException("Duplicate connector id: " + id);
            for (ConnectorTool t : c.tools()) {
                if (!t.name().startsWith(id + "_")) {
                    throw new IllegalStateException("Connector " + id + " tool " + t.name() + " must start with " + id + "_");
                }
                if (!toolNames.add(t.name())) throw new IllegalStateException("Duplicate connector tool: " + t.name());
            }
        }
        this.connectors = byId;
        this.store   = store;
        this.limiter = limiter;
        this.cache   = cache;
        this.http    = http;
        this.mapper  = mapper;
        this.clock   = clock;
    }

    // ── catalogue + state ────────────────────────────────────────────────────

    public List<Connector> all() {
        return List.copyOf(connectors.values());
    }

    public Connector get(String id) {
        Connector c = connectors.get(id);
        if (c == null) throw new NoSuchElementException("Unknown connector: " + id);
        return c;
    }

    public ConnectorStore.Account account(String id) {
        return store.account(id).orElse(ConnectorStore.Account.fresh(id));
    }

    public boolean isLinked(Connector c) {
        String id = c.spec().id();
        return c.spec().fields().stream().filter(f -> !f.optional()).allMatch(f -> store.field(id, f.key()).isPresent());
    }

    public Status status(Connector c) {
        ConnectorStore.Account a = account(c.spec().id());
        if (!isLinked(c)) return Status.NOT_LINKED;
        if (!a.enabled()) return Status.DISABLED;
        if (a.lastError() != null && (a.lastCallAt() == null || a.lastErrorAt().isAfter(a.lastCallAt()))) return Status.ERROR;
        if (Boolean.FALSE.equals(a.lastTestOk()) && a.lastCallAt() == null) return Status.ERROR;
        return Status.READY;
    }

    /** Linked and switched on — the only connectors agents are offered. */
    public List<Connector> usable() {
        return connectors.values().stream().filter(c -> account(c.spec().id()).enabled() && isLinked(c)).toList();
    }

    public Optional<String> hint(String id, ConnectorSpec.Field f) {
        if (!f.secret()) return store.field(id, f.key());
        return store.hint(id, f.key());
    }

    // ── linking ──────────────────────────────────────────────────────────────

    /**
     * Saves fields after a live test. Blank values keep what is stored (so the user can change
     * one key without re-entering the rest). Rejected credentials are not saved; an upstream
     * outage or rate limit still saves them, with the failed test recorded.
     */
    public String link(String id, Map<String, String> values) {
        Connector c = get(id);
        ConnectorSpec spec = c.spec();
        Map<String, String> incoming = values == null ? Map.of() : values;
        for (String k : incoming.keySet()) {
            if (spec.fields().stream().noneMatch(f -> f.key().equals(k))) {
                throw new IllegalArgumentException(spec.name() + " has no field '" + k + "'.");
            }
        }
        Map<String, String> merged = new HashMap<>();
        for (ConnectorSpec.Field f : spec.fields()) {
            String v = incoming.get(f.key());
            if (v != null && !v.isBlank()) merged.put(f.key(), v.strip());
            else store.field(id, f.key()).ifPresent(old -> merged.put(f.key(), old));
            if (!f.optional() && !merged.containsKey(f.key())) {
                throw new IllegalArgumentException(f.label() + " is required to link " + spec.name() + ".");
            }
        }

        Instant now = clock.instant();
        String message;
        boolean ok;
        try {
            message = c.test(context(spec, k -> Optional.ofNullable(merged.get(k))));
            ok = true;
        } catch (ConnectorException e) {
            if (e.kind() == ConnectorException.Kind.AUTH || e.kind() == ConnectorException.Kind.BAD_REQUEST
                    || e.kind() == ConnectorException.Kind.NOT_LINKED) {
                throw new IllegalArgumentException(e.getMessage());
            }
            message = e.getMessage();
            ok = false;
        } catch (Exception e) {
            log.warn("Connector {} test failed: {}", id, e.toString());
            message = spec.name() + " test failed: " + e.getClass().getSimpleName();
            ok = false;
        }

        for (Map.Entry<String, String> e : merged.entrySet()) store.putField(id, e.getKey(), e.getValue());
        cache.evictConnector(id);
        store.save(account(id).withLinked(now).withEnabled(true).withTest(now, ok, message).withError(null, null));
        return message;
    }

    public void unlink(String id) {
        get(id);
        store.deleteFields(id);
        store.delete(id);
        cache.evictConnector(id);
    }

    public void setEnabled(String id, boolean enabled) {
        get(id);
        store.save(account(id).withEnabled(enabled));
    }

    /** Re-tests stored fields; records and returns the outcome (never throws for upstream failures). */
    public ConnectorStore.Account test(String id) {
        Connector c = get(id);
        if (!isLinked(c)) throw new IllegalStateException(c.spec().name() + " is not linked yet.");
        Instant now = clock.instant();
        String message;
        boolean ok;
        try {
            message = c.test(context(c.spec(), k -> store.field(id, k)));
            ok = true;
        } catch (ConnectorException e) {
            message = e.getMessage();
            ok = false;
        } catch (Exception e) {
            log.warn("Connector {} test failed: {}", id, e.toString());
            message = c.spec().name() + " test failed: " + e.getClass().getSimpleName();
            ok = false;
        }
        ConnectorStore.Account a = account(id).withTest(now, ok, message);
        store.save(ok ? a.withError(null, null) : a);
        return a;
    }

    // ── calls ────────────────────────────────────────────────────────────────

    public Result call(String connectorId, String toolName, JsonNode args) {
        Connector c = get(connectorId);
        ConnectorSpec spec = c.spec();
        ConnectorTool tool = c.tools().stream().filter(t -> t.name().equals(toolName)).findFirst()
                .orElseThrow(() -> new NoSuchElementException(spec.name() + " has no tool " + toolName));
        if (!isLinked(c)) {
            throw new ConnectorException(ConnectorException.Kind.NOT_LINKED,
                    spec.name() + " is not linked. Add its key in Settings → Connectors.");
        }
        if (!account(connectorId).enabled()) {
            throw new ConnectorException(ConnectorException.Kind.DISABLED, spec.name() + " is switched off in Settings → Connectors.");
        }
        JsonNode a = args == null || !args.isObject() ? mapper.createObjectNode() : args;
        String key = connectorId + "\u0000" + toolName + "\u0000" + a;
        Optional<ResponseCache.Entry> hit = cache.get(key);
        if (hit.isPresent()) {
            return new Result(connectorId, spec.name(), toolName, spec.attribution(), hit.get().observedAt(), true, hit.get().data());
        }

        Instant now = clock.instant();
        try {
            Object data = tool.handler().call(context(spec, k -> store.field(connectorId, k)), a);
            cache.put(key, data, now, tool.cacheTtl());
            store.save(account(connectorId).withCall(now));
            return new Result(connectorId, spec.name(), toolName, spec.attribution(), now, false, data);
        } catch (ConnectorException e) {
            if (e.kind() != ConnectorException.Kind.BAD_REQUEST && e.kind() != ConnectorException.Kind.RATE_LIMITED) {
                store.save(account(connectorId).withError(now, e.getMessage()));
            }
            throw e;
        } catch (Exception e) {
            log.warn("Connector {} tool {} failed", connectorId, toolName, e);
            String msg = spec.name() + " call failed: " + e.getClass().getSimpleName()
                    + (e.getMessage() == null ? "" : " — " + ConnectorContext.snippet(e.getMessage()));
            store.save(account(connectorId).withError(now, msg));
            throw ConnectorException.upstream(msg);
        }
    }

    public List<ConnectorTool> tools(String connectorId) {
        return new ArrayList<>(get(connectorId).tools());
    }

    private ConnectorContext context(ConnectorSpec spec, java.util.function.Function<String, Optional<String>> fields) {
        boolean keyed = !spec.keyedLimits().isEmpty()
                && spec.fields().stream().anyMatch(f -> f.secret() && fields.apply(f.key()).filter(v -> !v.isBlank()).isPresent());
        return new ConnectorContext(spec, fields, http, mapper, () -> limiter.acquire(spec, keyed));
    }
}
