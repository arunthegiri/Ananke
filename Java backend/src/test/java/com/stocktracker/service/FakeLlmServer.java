package com.stocktracker.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * Tiny in-process HTTP server standing in for an LLM provider (no network).
 * Records every request (path, headers, parsed JSON body) and replies with
 * queued canned responses.
 */
public final class FakeLlmServer implements AutoCloseable {

    public record Recorded(String path, Headers headers, JsonNode body) { }
    public record Canned(int status, String body) { }

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final HttpServer server;
    public final List<Recorded> requests = new ArrayList<>();
    private final Deque<Canned> responses = new ArrayDeque<>();
    /** When set, computes each reply from the request body instead of the queue. */
    private volatile java.util.function.Function<JsonNode, Canned> responder;

    public FakeLlmServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            byte[] in = exchange.getRequestBody().readAllBytes();
            JsonNode body = in.length == 0 ? MAPPER.nullNode() : MAPPER.readTree(in);
            synchronized (this) {
                requests.add(new Recorded(exchange.getRequestURI().getPath(), exchange.getRequestHeaders(), body));
            }
            Canned c;
            var fn = responder;
            if (fn != null) {
                c = fn.apply(body);
            } else {
                synchronized (this) {
                    c = responses.isEmpty() ? new Canned(500, "{\"error\":\"no canned response\"}") : responses.poll();
                }
            }
            byte[] out = c.body().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(c.status(), out.length);
            exchange.getResponseBody().write(out);
            exchange.close();
        });
        server.setExecutor(java.util.concurrent.Executors.newFixedThreadPool(8, r -> {
            Thread t = new Thread(r, "fake-llm");
            t.setDaemon(true);
            return t;
        }));
        server.start();
    }

    public FakeLlmServer respondWith(java.util.function.Function<JsonNode, Canned> fn) {
        this.responder = fn;
        return this;
    }

    public synchronized List<Recorded> snapshot() {
        return List.copyOf(requests);
    }

    public synchronized FakeLlmServer reply(int status, String body) {
        responses.add(new Canned(status, body));
        return this;
    }

    public String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    public synchronized Recorded last() {
        return requests.get(requests.size() - 1);
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
