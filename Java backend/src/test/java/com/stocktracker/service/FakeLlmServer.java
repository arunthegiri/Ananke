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
final class FakeLlmServer implements AutoCloseable {

    record Recorded(String path, Headers headers, JsonNode body) { }
    record Canned(int status, String body) { }

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final HttpServer server;
    final List<Recorded> requests = new ArrayList<>();
    private final Deque<Canned> responses = new ArrayDeque<>();

    FakeLlmServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            byte[] in = exchange.getRequestBody().readAllBytes();
            JsonNode body = in.length == 0 ? MAPPER.nullNode() : MAPPER.readTree(in);
            synchronized (this) {
                requests.add(new Recorded(exchange.getRequestURI().getPath(), exchange.getRequestHeaders(), body));
            }
            Canned c;
            synchronized (this) {
                c = responses.isEmpty() ? new Canned(500, "{\"error\":\"no canned response\"}") : responses.poll();
            }
            byte[] out = c.body().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(c.status(), out.length);
            exchange.getResponseBody().write(out);
            exchange.close();
        });
        server.start();
    }

    synchronized FakeLlmServer reply(int status, String body) {
        responses.add(new Canned(status, body));
        return this;
    }

    String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    synchronized Recorded last() {
        return requests.get(requests.size() - 1);
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
