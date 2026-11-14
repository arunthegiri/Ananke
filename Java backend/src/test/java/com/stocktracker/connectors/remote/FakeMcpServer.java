package com.stocktracker.connectors.remote;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiFunction;

/**
 * A streamable-HTTP MCP server for connector tests, shaped like the real ones: {@code initialize}
 * hands out a session id (and, when {@link #authHeader} is set, rejects a wrong credential with
 * HTTP 401 and a JSON-RPC error body, as World Monitor does), {@code tools/list} returns
 * {@link #tools}, {@code tools/call} answers through {@link #onCall}. Every request is recorded.
 */
public class FakeMcpServer implements AutoCloseable {

    public record Seen(String method, String tool, JsonNode arguments, String session, Map<String, String> headers) { }

    public final ObjectMapper om = new ObjectMapper();
    public final List<Seen> seen = new CopyOnWriteArrayList<>();
    public final AtomicInteger sessions = new AtomicInteger();
    /** Next tools/call answers 404 once (expired session). */
    public final AtomicInteger expireNextCall = new AtomicInteger();
    public volatile JsonNode tools = om.createArrayNode();
    /** (tool name, arguments) → tools/call result, or an {@code {"__status":429}} node for an HTTP error. */
    public volatile BiFunction<String, JsonNode, JsonNode> onCall = (n, a) -> textResult("ok " + n);
    public volatile String authHeader;
    public volatile String authValue;
    public volatile boolean sse = true;

    private final HttpServer server;
    private final List<String> watchedHeaders;

    public FakeMcpServer(String... watchedHeaders) throws IOException {
        this.watchedHeaders = List.of(watchedHeaders);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/mcp", this::handle);
        server.start();
    }

    public String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/mcp";
    }

    @Override
    public void close() {
        server.stop(0);
    }

    public List<String> methods() {
        return seen.stream().map(Seen::method).toList();
    }

    public ObjectNode textResult(String text) {
        ObjectNode r = om.createObjectNode();
        r.putArray("content").addObject().put("type", "text").put("text", text);
        return r;
    }

    /** A result carrying both forms, as World Monitor answers. */
    public ObjectNode structuredResult(JsonNode payload) {
        ObjectNode r = textResult(payload.toString());
        r.set("structuredContent", payload);
        return r;
    }

    private void handle(HttpExchange ex) throws IOException {
        JsonNode req = om.readTree(ex.getRequestBody().readAllBytes());
        String method = req.path("method").asText();
        String session = ex.getRequestHeaders().getFirst("Mcp-Session-Id");
        Map<String, String> headers = new java.util.LinkedHashMap<>();
        for (String h : watchedHeaders) {
            String v = ex.getRequestHeaders().getFirst(h);
            if (v != null) headers.put(h, v);
        }
        seen.add(new Seen(method, req.path("params").path("name").asText(null), req.path("params").path("arguments"), session, headers));
        long id = req.path("id").asLong();

        if (authHeader != null && !authValue.equals(ex.getRequestHeaders().getFirst(authHeader))) {
            send(ex, 401, "application/json", om.writeValueAsString(Map.of("jsonrpc", "2.0", "id", id,
                    "error", Map.of("code", -32001, "message", "Invalid API key", "data", Map.of("reason", "no-account")))));
            return;
        }
        switch (method) {
            case "initialize" -> {
                ex.getResponseHeaders().add("Mcp-Session-Id", "s" + sessions.incrementAndGet());
                answer(ex, id, om.valueToTree(Map.of("protocolVersion", "2025-06-18",
                        "capabilities", Map.of("tools", Map.of()), "serverInfo", Map.of("name", "fake"))));
            }
            case "notifications/initialized" -> {
                ex.sendResponseHeaders(202, -1);
                ex.close();
            }
            case "tools/list" -> answer(ex, id, om.createObjectNode().set("tools", tools));
            case "tools/call" -> {
                if (expireNextCall.getAndSet(0) == 1) {
                    ex.sendResponseHeaders(404, -1);
                    ex.close();
                    return;
                }
                JsonNode r = onCall.apply(req.path("params").path("name").asText(), req.path("params").path("arguments"));
                if (r.has("__status")) {
                    send(ex, r.get("__status").asInt(), "application/json", r.path("body").toString());
                } else if (r.has("__rpcError")) {
                    send(ex, 200, "application/json", om.writeValueAsString(Map.of("jsonrpc", "2.0", "id", id,
                            "error", Map.of("code", -32602, "message", r.get("__rpcError").asText()))));
                } else {
                    answer(ex, id, r);
                }
            }
            default -> send(ex, 200, "application/json", om.writeValueAsString(Map.of("jsonrpc", "2.0", "id", id,
                    "error", Map.of("code", -32601, "message", "no such method"))));
        }
    }

    private void answer(HttpExchange ex, long id, JsonNode result) throws IOException {
        String body = om.writeValueAsString(Map.of("jsonrpc", "2.0", "id", id, "result", result));
        if (sse) send(ex, 200, "text/event-stream", "event: message\ndata: " + body + "\n\n");
        else send(ex, 200, "application/json", body);
    }

    private static void send(HttpExchange ex, int status, String type, String body) throws IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", type);
        ex.sendResponseHeaders(status, b.length);
        ex.getResponseBody().write(b);
        ex.close();
    }
}
