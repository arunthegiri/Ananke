package com.stocktracker.connectors.remote;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.stocktracker.connectors.Connector;
import com.stocktracker.connectors.ConnectorContext;
import com.stocktracker.connectors.ConnectorException;
import com.stocktracker.connectors.ConnectorException.Kind;
import com.stocktracker.connectors.ConnectorTool;
import com.stocktracker.connectors.mcp.McpHttpClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Base for connectors whose source is a hosted (or user-run) MCP server reached over streamable
 * HTTP through the shared {@link McpHttpClient} (Connectors roadmap §1).
 *
 * <p>Only an explicit allowlist ever reaches panels or agents. Each allowlisted tool is declared
 * statically ({@link UpstreamTool}: upstream name, our description, a parameter schema that is a
 * subset of the server's) because {@link Connector#tools()} runs at startup, before any
 * credentials exist. At call time the live {@code tools/list} (cached) must still offer the tool,
 * and anything the server annotates {@code readOnlyHint=false} or {@code destructiveHint=true} is
 * dropped — so an upstream change can only remove tools, never add a writing one.
 *
 * <p>Only properties declared in the static schema are forwarded, so paid-panel controls and other
 * knobs the server offers never reach it. Every HTTP request (initialize, the initialized
 * notification, list, call, re-initialise) takes one rate-limit permit. Changing credentials
 * resets the MCP session; changing the endpoint starts a new client.
 */
public abstract class RemoteMcpConnector implements Connector {

    private static final Logger log = LoggerFactory.getLogger(RemoteMcpConnector.class);

    /** Results larger than this (serialised) are returned as truncated text. */
    public static final int MAX_CHARS = 40_000;
    static final Duration LIST_TTL = Duration.ofHours(6);
    static final Duration TIMEOUT = Duration.ofSeconds(60);

    /** Top-level payload keys that carry the source's own as-of time (§0.6 point-in-time). */
    static final List<String> AS_OF_KEYS = List.of("cached_at", "as_of", "asOf", "generatedAt", "generated_at",
            "updatedAt", "updated_at", "fetchedAt", "timestamp");

    private static final Pattern HTTP_STATUS = Pattern.compile("HTTP (\\d{3})");

    /**
     * One allowlisted upstream tool.
     *
     * @param upstream   the server's tool name
     * @param parameters JSON schema offered to callers — a subset of the server's input schema;
     *                   only these properties are forwarded
     * @param defaults   values sent when the caller leaves a property out (e.g. a small {@code limit})
     * @param maxLimit   when &gt; 0, an integer {@code limit} argument is clamped to 1..maxLimit
     */
    public record UpstreamTool(String upstream, String description, Map<String, Object> parameters,
                               Duration cacheTtl, Map<String, Object> defaults, int maxLimit) {
        public UpstreamTool {
            defaults = defaults == null ? Map.of() : defaults;
        }

        @SuppressWarnings("unchecked")
        public Map<String, Object> properties() {
            Object p = parameters.get("properties");
            return p instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
        }

        @SuppressWarnings("unchecked")
        public List<String> required() {
            Object r = parameters.get("required");
            return r instanceof List<?> l ? (List<String>) l : List.of();
        }
    }

    /** The live state for one endpoint: client, auth fingerprint, cached allowlisted listing. */
    private static final class Session {
        final URI endpoint;
        final McpHttpClient client;
        String authKey;
        Map<String, JsonNode> offered;
        Instant offeredAt;

        Session(URI endpoint, McpHttpClient client, String authKey) {
            this.endpoint = endpoint;
            this.client   = client;
            this.authKey  = authKey;
        }
    }

    protected final Clock clock;
    private final Object lock = new Object();
    private Session session;
    private volatile List<ConnectorTool> tools;

    protected RemoteMcpConnector(Clock clock) {
        this.clock = clock == null ? Clock.systemUTC() : clock;
    }

    // ── what subclasses declare ──────────────────────────────────────────────

    /** The allowlist, declared in code and verified against the server's real {@code tools/list}. */
    protected abstract List<UpstreamTool> upstreamTools();

    /** Where the MCP server is (fixed for hosted servers, a user field for sidecars). */
    protected abstract URI endpoint(ConnectorContext ctx);

    /** Auth headers built from the user's secret fields; never logged or echoed. */
    protected abstract Map<String, String> authHeaders(ConnectorContext ctx);

    /** Read-only tools callable only from {@link #test} (e.g. an allowance check), never offered. */
    protected Set<String> testOnlyTools() {
        return Set.of();
    }

    /** Declared properties handled here instead of being forwarded (e.g. a row cap). */
    protected Set<String> localParams() {
        return Set.of();
    }

    /** Last chance to bound a parsed payload before it is sized (e.g. keep the newest N rows). */
    protected JsonNode shape(UpstreamTool tool, JsonNode args, JsonNode payload) {
        return payload;
    }

    // ── Connector ────────────────────────────────────────────────────────────

    /** {@code <id>_<sanitised upstream name>}, at most 64 chars of {@code [a-z0-9_]}. */
    public static String toolName(String connectorId, String upstream) {
        String s = upstream.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_]+", "_").replaceAll("_+", "_")
                .replaceAll("^_|_$", "");
        String n = connectorId + "_" + s;
        return n.length() > 64 ? n.substring(0, 64) : n;
    }

    @Override
    public List<ConnectorTool> tools() {
        List<ConnectorTool> t = tools;
        if (t != null) return t;
        List<ConnectorTool> out = new ArrayList<>();
        for (UpstreamTool u : upstreamTools()) {
            out.add(new ConnectorTool(toolName(spec().id(), u.upstream()), u.description(), u.parameters(), u.cacheTtl(),
                    (ctx, args) -> call(ctx, u, args),
                    a -> "Asking " + spec().name() + " (" + u.upstream() + ")…"));
        }
        tools = List.copyOf(out);
        return tools;
    }

    /** Allowlisted tool by upstream name (tests). */
    public UpstreamTool declared(String upstream) {
        return upstreamTools().stream().filter(u -> u.upstream().equals(upstream)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Not allowlisted: " + upstream));
    }

    // ── calls ────────────────────────────────────────────────────────────────

    /** Calls one allowlisted tool with only its declared, bounded arguments. */
    public Object call(ConnectorContext ctx, UpstreamTool tool, JsonNode args) {
        ObjectNode a = arguments(ctx.mapper(), tool, args);
        JsonNode result = callUpstream(ctx, tool.upstream(), a);
        return render(ctx.mapper(), tool, args, result);
    }

    /**
     * Raw {@code tools/call} of an allowlisted or test-only tool that the live listing still offers
     * as read-only. Errors are mapped to {@link ConnectorException}s.
     */
    protected JsonNode callUpstream(ConnectorContext ctx, String upstream, ObjectNode args) {
        if (!allowed().contains(upstream)) throw ConnectorException.badRequest("Tool " + upstream + " is not allowed.");
        Session s = session(ctx);
        McpHttpClient.Auth auth = auth(ctx);
        if (!offered(s, auth).containsKey(upstream)) {
            throw ConnectorException.badRequest(spec().name() + " does not currently offer " + upstream
                    + " as a read-only tool (it may need another plan, extension or server version).");
        }
        JsonNode result = mcp(() -> s.client.callTool(auth, upstream, args));
        if (result.path("isError").asBoolean(false)) {
            String text = McpHttpClient.text(result);
            throw classify(text.isEmpty() ? "the tool reported an error" : text, Kind.UPSTREAM);
        }
        return result;
    }

    /** Lists the server's tools (cached) and returns how many allowlisted read-only ones it offers. */
    protected int offeredCount(ConnectorContext ctx) {
        Session s = session(ctx);
        Set<String> declared = new LinkedHashSet<>();
        upstreamTools().forEach(u -> declared.add(u.upstream()));
        return (int) offered(s, auth(ctx)).keySet().stream().filter(declared::contains).count();
    }

    private Set<String> allowed() {
        Set<String> s = new LinkedHashSet<>();
        upstreamTools().forEach(u -> s.add(u.upstream()));
        s.addAll(testOnlyTools());
        return s;
    }

    /** One permit per HTTP request the MCP client sends, then the user's auth headers. */
    private McpHttpClient.Auth auth(ConnectorContext ctx) {
        Map<String, String> headers = authHeaders(ctx);
        return (json, sessionId) -> {
            ctx.acquirePermit();
            Map<String, List<String>> m = new LinkedHashMap<>();
            headers.forEach((k, v) -> m.put(k, List.of(v)));
            return m;
        };
    }

    private Session session(ConnectorContext ctx) {
        URI endpoint = endpoint(ctx);
        String key = fingerprint(authHeaders(ctx));
        synchronized (lock) {
            if (session == null || !session.endpoint.equals(endpoint)) {
                session = new Session(endpoint, new McpHttpClient(endpoint, spec().name(), ctx.http(), ctx.mapper(), TIMEOUT), key);
            } else if (!session.authKey.equals(key)) {
                session.client.reset();          // new credentials → new MCP session, fresh listing
                session.authKey = key;
                session.offered = null;
            }
            return session;
        }
    }

    private Map<String, JsonNode> offered(Session s, McpHttpClient.Auth auth) {
        synchronized (s) {
            if (s.offered != null && s.offeredAt.plus(LIST_TTL).isAfter(clock.instant())) return s.offered;
            List<JsonNode> listed = mcp(() -> s.client.listTools(auth));
            Set<String> allowed = allowed();
            Map<String, JsonNode> out = new LinkedHashMap<>();
            for (JsonNode t : listed) {
                String name = t.path("name").asText("");
                if (!allowed.contains(name) || !readOnly(t)) continue;
                out.put(name, t);
            }
            for (UpstreamTool u : upstreamTools()) {
                JsonNode live = out.get(u.upstream());
                if (live != null) {
                    List<String> drift = new ArrayList<>(verifyAgainst(u, live));
                    drift.removeIf(d -> localParams().stream().anyMatch(lp -> d.equals("unknown property " + lp)));
                    if (!drift.isEmpty()) log.warn("{} tool {} schema drift: {}", spec().id(), u.upstream(), drift);
                }
            }
            s.offered = out;
            s.offeredAt = clock.instant();
            return out;
        }
    }

    /** Not annotated as writing or destructive by the server itself. */
    public static boolean readOnly(JsonNode tool) {
        JsonNode ann = tool.path("annotations");
        if (ann.path("readOnlyHint").isBoolean() && !ann.path("readOnlyHint").asBoolean()) return false;
        return !ann.path("destructiveHint").asBoolean(false);
    }

    // ── arguments ────────────────────────────────────────────────────────────

    /** Declared properties only, defaults filled, {@code limit} clamped, required checked. */
    ObjectNode arguments(ObjectMapper mapper, UpstreamTool tool, JsonNode args) {
        ObjectNode out = mapper.createObjectNode();
        Set<String> local = localParams();
        for (String p : tool.properties().keySet()) {
            if (local.contains(p)) continue;
            JsonNode v = args == null ? null : args.get(p);
            if (v != null && !v.isNull() && !(v.isTextual() && v.asText().isBlank())) {
                out.set(p, v.isTextual() ? mapper.getNodeFactory().textNode(v.asText().strip()) : v);
            } else if (tool.defaults().containsKey(p)) {
                out.set(p, mapper.valueToTree(tool.defaults().get(p)));
            }
        }
        if (tool.maxLimit() > 0 && tool.properties().containsKey("limit")) {
            int dflt = tool.defaults().get("limit") instanceof Number n ? n.intValue() : tool.maxLimit();
            int v = out.has("limit") && (out.get("limit").isNumber() || out.get("limit").isTextual())
                    ? out.get("limit").asInt(dflt) : dflt;
            out.put("limit", Math.max(1, Math.min(tool.maxLimit(), v)));
        }
        for (String r : tool.required()) {
            if (!out.has(r)) throw ConnectorException.badRequest(r + " is required");
        }
        return out;
    }

    // ── results ──────────────────────────────────────────────────────────────

    /** Structured content (or JSON text) compactly, with the source's as-of time lifted to the top. */
    Object render(ObjectMapper mapper, UpstreamTool tool, JsonNode args, JsonNode result) {
        String text = McpHttpClient.text(result);
        JsonNode payload = result.get("structuredContent");
        if (payload == null || payload.isNull()) payload = parseJson(mapper, text);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("tool", tool.upstream());
        if (payload != null) {
            payload = shape(tool, args, payload);
            if (payload.isObject()) {
                for (String k : AS_OF_KEYS) {
                    JsonNode v = payload.get(k);
                    if (v != null && (v.isTextual() || v.isNumber())) {
                        out.put("as_of", v.asText());
                        out.put("as_of_field", k);
                        break;
                    }
                }
                if (payload.path("stale").isBoolean()) out.put("stale", payload.get("stale").asBoolean());
            }
            String json = payload.toString();
            if (json.length() <= MAX_CHARS) {
                out.put("truncated", false);
                out.put("content", mapper.convertValue(payload, Object.class));
                return out;
            }
            text = json;
        }
        boolean truncated = text.length() > MAX_CHARS;
        out.put("truncated", truncated);
        out.put("content", truncated ? text.substring(0, MAX_CHARS) : text);
        return out;
    }

    private static JsonNode parseJson(ObjectMapper mapper, String text) {
        String t = text == null ? "" : text.strip();
        if (!(t.startsWith("{") || t.startsWith("["))) return null;
        try {
            return mapper.readTree(t);
        } catch (Exception e) {
            return null;
        }
    }

    // ── errors ───────────────────────────────────────────────────────────────

    private <T> T mcp(Supplier<T> call) {
        try {
            return call.get();
        } catch (McpHttpClient.McpException e) {
            throw map(e.getMessage() == null ? "" : e.getMessage());
        }
    }

    /** McpHttpClient messages → kinds: HTTP status first, then the JSON-RPC error text. */
    ConnectorException map(String message) {
        String name = spec().name();
        String detail = snippet(message);
        Matcher m = HTTP_STATUS.matcher(message);
        if (m.find()) {
            int status = Integer.parseInt(m.group(1));
            if (status == 401 || status == 403) {
                return new ConnectorException(Kind.AUTH, name + " rejected the credentials (HTTP " + status
                        + "). Check them in Settings → Connectors. " + detail);
            }
            if (status == 429) return new ConnectorException(Kind.RATE_LIMITED, name + " rate limit or daily allowance reached (HTTP 429). " + detail);
            if (status == 404) return ConnectorException.upstream(name + ": MCP endpoint not found (HTTP 404). Check the server URL.");
            return ConnectorException.upstream(name + " failed: " + detail);
        }
        if (message.contains(" unreachable")) return ConnectorException.upstream(name + " is unreachable. " + detail);
        return classify(message, Kind.UPSTREAM);
    }

    /** Error text from a JSON-RPC error or an {@code isError} result → the kind a caller can act on. */
    ConnectorException classify(String text, Kind dflt) {
        String l = text.toLowerCase(Locale.ROOT);
        String msg = spec().name() + ": " + snippet(text);
        if (l.contains("invalid api key") || l.contains("unauthorized") || l.contains("sign-in required")
                || l.contains("invalid_token") || l.contains("authentication")) {
            return new ConnectorException(Kind.AUTH, msg);
        }
        if (l.contains("quota") || l.contains("allowance") || l.contains("rate limit") || l.contains("too many requests")
                || l.contains("exhausted")) {
            return new ConnectorException(Kind.RATE_LIMITED, msg);
        }
        if (l.contains("upgrade") || l.contains("paid plan") || l.contains("subscription") || l.contains("invalid params")
                || l.contains("unknown tool") || l.contains("validation") || l.contains("not found")) {
            return ConnectorException.badRequest(msg);
        }
        return new ConnectorException(dflt, msg);
    }

    static String snippet(String s) {
        if (s == null) return "";
        String t = s.replaceAll("\\s+", " ").strip();
        return t.length() > 300 ? t.substring(0, 300) + "…" : t;
    }

    private static String fingerprint(Map<String, String> headers) {
        try {
            MessageDigest d = MessageDigest.getInstance("SHA-256");
            for (Map.Entry<String, String> e : new TreeMap<>(headers).entrySet()) {
                d.update((e.getKey().toLowerCase(Locale.ROOT) + "\u0000" + e.getValue() + "\u0000").getBytes(StandardCharsets.UTF_8));
            }
            return HexFormat.of().formatHex(d.digest());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    // ── schema verification (tests, live checks, drift warnings) ─────────────

    /**
     * Differences between a declared tool and the server's definition: every declared property
     * must exist upstream with the same type, declared enums must be a subset of the server's,
     * everything the server requires must be declared required, and the server must not mark
     * the tool as writing. Empty = consistent.
     */
    public static List<String> verifyAgainst(UpstreamTool t, JsonNode upstream) {
        List<String> out = new ArrayList<>();
        if (!readOnly(upstream)) out.add("not read-only upstream");
        JsonNode props = upstream.path("inputSchema").path("properties");
        for (Map.Entry<String, Object> e : t.properties().entrySet()) {
            JsonNode up = props.get(e.getKey());
            if (up == null) {
                out.add("unknown property " + e.getKey());
                continue;
            }
            if (!(e.getValue() instanceof Map<?, ?> decl)) continue;
            Object type = decl.get("type");
            if (type != null && up.has("type") && !typeMatches(String.valueOf(type), up.get("type"))) {
                out.add(e.getKey() + " type " + type + " vs " + up.get("type"));
            }
            checkEnum(out, e.getKey(), decl.get("enum"), up.get("enum"));
            if (decl.get("items") instanceof Map<?, ?> items) {
                checkEnum(out, e.getKey() + "[]", items.get("enum"), up.path("items").get("enum"));
            }
        }
        for (JsonNode r : upstream.path("inputSchema").path("required")) {
            if (!t.required().contains(r.asText())) out.add("upstream requires " + r.asText());
        }
        return out;
    }

    private static boolean typeMatches(String declared, JsonNode upstream) {
        Set<String> up = new LinkedHashSet<>();
        if (upstream.isArray()) upstream.forEach(x -> up.add(x.asText()));
        else up.add(upstream.asText());
        if (up.contains(declared)) return true;
        return declared.equals("integer") && up.contains("number");   // a narrower declaration is fine
    }

    private static void checkEnum(List<String> out, String name, Object declared, JsonNode upstream) {
        if (!(declared instanceof List<?> values)) return;
        if (upstream == null || !upstream.isArray()) {
            out.add(name + " enum not upstream");
            return;
        }
        Set<String> up = new LinkedHashSet<>();
        for (Iterator<JsonNode> it = upstream.elements(); it.hasNext(); ) up.add(it.next().asText());
        for (Object v : values) if (!up.contains(String.valueOf(v))) out.add(name + " enum value " + v + " not upstream");
    }
}
