package com.stocktracker.connectors.remote;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

/**
 * A local HTTP server for REST connector tests: every request is recorded (path, decoded query,
 * chosen headers) and answered by {@link #route}.
 */
public class FakeRestServer implements AutoCloseable {

    public record Request(String path, Map<String, String> query, Map<String, String> headers) { }

    public record Response(int status, String body) {
        public static Response ok(String body) {
            return new Response(200, body);
        }
    }

    public final List<Request> requests = new CopyOnWriteArrayList<>();
    public volatile Function<Request, Response> route = r -> new Response(404, "{\"error\":\"not found\"}");

    private final HttpServer server;
    private final List<String> watched;

    public FakeRestServer(String... watchedHeaders) throws IOException {
        watched = List.of(watchedHeaders);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.start();
    }

    public String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    public Request last() {
        return requests.get(requests.size() - 1);
    }

    @Override
    public void close() {
        server.stop(0);
    }

    private void handle(HttpExchange ex) throws IOException {
        Map<String, String> q = new LinkedHashMap<>();
        String raw = ex.getRequestURI().getRawQuery();
        if (raw != null) {
            for (String kv : raw.split("&")) {
                int i = kv.indexOf('=');
                String k = URLDecoder.decode(i < 0 ? kv : kv.substring(0, i), StandardCharsets.UTF_8);
                String v = i < 0 ? "" : URLDecoder.decode(kv.substring(i + 1), StandardCharsets.UTF_8);
                q.put(k, v);
            }
        }
        Map<String, String> h = new LinkedHashMap<>();
        for (String w : watched) {
            String v = ex.getRequestHeaders().getFirst(w);
            if (v != null) h.put(w, v);
        }
        Request req = new Request(ex.getRequestURI().getRawPath(), q, h);
        requests.add(req);
        Response r = route.apply(req);
        byte[] b = r.body().getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(r.status(), b.length);
        ex.getResponseBody().write(b);
        ex.close();
    }
}
