package com.stocktracker.connectors.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Remote MCP client over streamable HTTP (JSON-RPC over POST; JSON or SSE answers; session
 * header), shared by the AWS MCP knowledge tools and hosted-MCP data connectors (Connectors
 * roadmap §1). Lifted out of {@code AwsMcpKnowledge}.
 *
 * <p>Authentication is per request through {@link Auth}, because SigV4 signs the body: a static
 * API-key header is just an {@code Auth} that ignores its arguments. This class only moves
 * JSON-RPC; deciding which tools may be listed or called is the caller's job.
 */
public class McpHttpClient {

    public static final String PROTOCOL = "2025-06-18";

    /** Extra headers for one request, given its exact body and the current session id (may be null). */
    @FunctionalInterface
    public interface Auth {
        Map<String, List<String>> headers(String json, String session);

        Auth NONE = (json, session) -> Map.of();

        static Auth header(String name, String value) {
            return (json, session) -> Map.of(name, List.of(value));
        }
    }

    public static class McpException extends RuntimeException {
        public McpException(String message) {
            super(message);
        }
    }

    private final URI endpoint;
    private final String label;
    private final HttpClient http;
    private final ObjectMapper mapper;
    private final Duration timeout;
    private final AtomicLong ids = new AtomicLong();
    private volatile String sessionId;

    /** @param label how errors name the server, e.g. {@code "AWS MCP"} */
    public McpHttpClient(URI endpoint, String label, HttpClient http, ObjectMapper mapper, Duration timeout) {
        this.endpoint = endpoint;
        this.label    = label;
        this.http     = http;
        this.mapper   = mapper;
        this.timeout  = timeout;
    }

    /** {@code tools/list} → the raw tool objects. */
    public List<JsonNode> listTools(Auth auth) {
        List<JsonNode> out = new ArrayList<>();
        request(auth, "tools/list", mapper.createObjectNode()).path("tools").forEach(out::add);
        return out;
    }

    /** {@code tools/call} → the raw result ({@code content[]}, {@code isError}, maybe {@code structuredContent}). */
    public JsonNode callTool(Auth auth, String name, JsonNode args) {
        ObjectNode params = mapper.createObjectNode();
        params.put("name", name);
        params.set("arguments", args == null || !args.isObject() ? mapper.createObjectNode() : args);
        return request(auth, "tools/call", params);
    }

    /** Concatenated {@code text} parts of a {@code tools/call} result. */
    public static String text(JsonNode result) {
        StringBuilder sb = new StringBuilder();
        for (JsonNode c : result.path("content")) {
            if ("text".equals(c.path("type").asText())) sb.append(c.path("text").asText()).append('\n');
        }
        return sb.toString().strip();
    }

    /** Drops the session, e.g. after the credentials changed. */
    public void reset() {
        sessionId = null;
    }

    /** Any JSON-RPC method; initialises a session first and re-initialises once if it expired. */
    public JsonNode request(Auth auth, String method, ObjectNode params) {
        if (sessionId == null) initialize(auth);
        try {
            return send(auth, method, params);
        } catch (SessionExpired e) {
            sessionId = null;
            initialize(auth);
            return send(auth, method, params);
        }
    }

    private static final class SessionExpired extends RuntimeException { }

    private synchronized void initialize(Auth auth) {
        if (sessionId != null) return;
        ObjectNode p = mapper.createObjectNode();
        p.put("protocolVersion", PROTOCOL);
        p.set("capabilities", mapper.createObjectNode());
        p.set("clientInfo", mapper.createObjectNode().put("name", "ananke").put("version", "1"));
        HttpResponse<String> r = post(auth, envelope("initialize", p, true), null);
        if (r.statusCode() / 100 != 2) throw new McpException(label + " initialize failed: HTTP " + r.statusCode() + " " + snippet(r.body()));
        result(r, -1);
        sessionId = r.headers().firstValue("Mcp-Session-Id").orElse("");
        post(auth, envelope("notifications/initialized", null, false), sessionId);
    }

    private JsonNode send(Auth auth, String method, ObjectNode params) {
        ObjectNode env = envelope(method, params, true);
        HttpResponse<String> r = post(auth, env, sessionId);
        if (r.statusCode() == 404 && sessionId != null && !sessionId.isEmpty()) throw new SessionExpired();
        if (r.statusCode() / 100 != 2) throw new McpException(label + " " + method + " failed: HTTP " + r.statusCode() + " " + snippet(r.body()));
        return result(r, env.path("id").asLong());
    }

    private ObjectNode envelope(String method, ObjectNode params, boolean withId) {
        ObjectNode n = mapper.createObjectNode();
        n.put("jsonrpc", "2.0");
        if (withId) n.put("id", ids.incrementAndGet());
        n.put("method", method);
        if (params != null) n.set("params", params);
        return n;
    }

    /** JSON or SSE body → the JSON-RPC result for {@code id} (any id when {@code id < 0}). */
    JsonNode result(HttpResponse<String> r, long id) {
        String ct = r.headers().firstValue("Content-Type").orElse("");
        List<JsonNode> messages = new ArrayList<>();
        try {
            if (ct.startsWith("text/event-stream")) {
                for (String line : r.body().split("\r?\n")) {
                    if (line.startsWith("data:")) messages.add(mapper.readTree(line.substring(5).strip()));
                }
            } else {
                messages.add(mapper.readTree(r.body()));
            }
        } catch (Exception e) {
            throw new McpException(label + " answered something that is not JSON-RPC.");
        }
        for (JsonNode m : messages) {
            if (id >= 0 && m.path("id").asLong(-2) != id) continue;
            if (m.has("error")) throw new McpException(label + " error: " + m.path("error").path("message").asText());
            if (m.has("result")) return m.path("result");
        }
        throw new McpException(label + " returned no result.");
    }

    private HttpResponse<String> post(Auth auth, ObjectNode body, String session) {
        String json = body.toString();
        HttpRequest.Builder b = HttpRequest.newBuilder(endpoint)
                .timeout(timeout)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream")
                .header("MCP-Protocol-Version", PROTOCOL)
                .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8));
        if (session != null && !session.isEmpty()) b.header("Mcp-Session-Id", session);
        if (auth != null) {
            for (Map.Entry<String, List<String>> h : auth.headers(json, session).entrySet()) {
                String k = h.getKey().toLowerCase();
                if (k.equals("host") || k.equals("content-length")) continue;   // set by the HTTP client
                for (String v : h.getValue()) b.header(h.getKey(), v);
            }
        }
        try {
            return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            throw new McpException(label + " unreachable: " + e.getMessage());
        }
    }

    static String snippet(String s) {
        if (s == null) return "";
        String t = s.strip();
        return t.length() > 200 ? t.substring(0, 200) + "…" : t;
    }
}
