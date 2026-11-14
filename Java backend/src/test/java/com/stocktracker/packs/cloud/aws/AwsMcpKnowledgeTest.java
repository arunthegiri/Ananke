package com.stocktracker.packs.cloud.aws;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stocktracker.packs.cloud.aws.AwsGateway.Creds;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The AWS MCP Server client against a local fake MCP server: SigV4 for service aws-mcp,
 * session handling (incl. re-initialise on 404), JSON and SSE answers, and — the point —
 * only the allowlisted read-only knowledge tools are ever listed or called.
 */
class AwsMcpKnowledgeTest {

    record Seen(String method, String auth, String token, String session) { }

    HttpServer server;
    final List<Seen> seen = new CopyOnWriteArrayList<>();
    final AtomicInteger sessions = new AtomicInteger();
    final AtomicInteger expireNextCall = new AtomicInteger();
    final ObjectMapper om = new ObjectMapper();
    AwsMcpKnowledge mcp;

    static final Creds SESSION = new Creds("ASIA" + "MCPT".repeat(4), "fake-secret-".repeat(4), "fake-session-token", Instant.now().plusSeconds(3600));

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/mcp", this::handle);
        server.start();
        mcp = new AwsMcpKnowledge(URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/mcp"), "us-east-1",
                HttpClient.newHttpClient(), om, Clock.systemUTC());
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    void handle(HttpExchange ex) throws IOException {
        JsonNode req = om.readTree(ex.getRequestBody().readAllBytes());
        String method = req.path("method").asText();
        String session = ex.getRequestHeaders().getFirst("Mcp-Session-Id");
        seen.add(new Seen(method, ex.getRequestHeaders().getFirst("Authorization"),
                ex.getRequestHeaders().getFirst("X-Amz-Security-Token"), session));
        long id = req.path("id").asLong();
        switch (method) {
            case "initialize" -> {
                ex.getResponseHeaders().add("Mcp-Session-Id", "s" + sessions.incrementAndGet());
                json(ex, Map.of("jsonrpc", "2.0", "id", id, "result", Map.of("protocolVersion", "2025-06-18",
                        "capabilities", Map.of("tools", Map.of()), "serverInfo", Map.of("name", "fake-aws-mcp"))));
            }
            case "notifications/initialized" -> {
                ex.sendResponseHeaders(202, -1);
                ex.close();
            }
            case "tools/list" -> sse(ex, Map.of("jsonrpc", "2.0", "id", id, "result", Map.of("tools", List.of(
                    tool("aws___search_documentation", Map.of("readOnlyHint", true)),
                    tool("aws___read_documentation", null),
                    tool("aws___run_script", Map.of("readOnlyHint", false)),
                    tool("aws___get_presigned_url", Map.of("readOnlyHint", true)),
                    tool("aws___list_regions", Map.of("readOnlyHint", false))))));
            case "tools/call" -> {
                if (expireNextCall.getAndSet(0) == 1) {
                    ex.sendResponseHeaders(404, -1);
                    ex.close();
                    return;
                }
                String name = req.path("params").path("name").asText();
                json(ex, Map.of("jsonrpc", "2.0", "id", id, "result", Map.of("content", List.of(
                        Map.of("type", "text", "text", "Result of " + name + " for " + req.path("params").path("arguments")))))); }
            default -> json(ex, Map.of("jsonrpc", "2.0", "id", id, "error", Map.of("code", -32601, "message", "no such method")));
        }
    }

    static Map<String, Object> tool(String name, Map<String, Object> annotations) {
        return annotations == null
                ? Map.of("name", name, "description", name + " docs", "inputSchema", Map.of("type", "object", "properties", Map.of()))
                : Map.of("name", name, "description", name + " docs", "annotations", annotations,
                         "inputSchema", Map.of("type", "object", "properties", Map.of("q", Map.of("type", "string"))));
    }

    void json(HttpExchange ex, Object body) throws IOException {
        byte[] b = om.writeValueAsBytes(body);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(200, b.length);
        ex.getResponseBody().write(b);
        ex.close();
    }

    void sse(HttpExchange ex, Object body) throws IOException {
        byte[] b = ("event: message\ndata: " + om.writeValueAsString(body) + "\n\n").getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "text/event-stream");
        ex.sendResponseHeaders(200, b.length);
        ex.getResponseBody().write(b);
        ex.close();
    }

    @Test
    void listsOnlyAllowlistedReadOnlyTools_signedForAwsMcp() {
        List<AwsMcpKnowledge.McpTool> tools = mcp.tools(() -> SESSION);

        assertThat(tools).extracting(AwsMcpKnowledge.McpTool::name)
                .containsExactly("aws___search_documentation", "aws___read_documentation");   // never run_script / presigned URLs
        assertThat(seen).extracting(Seen::method).containsExactly("initialize", "notifications/initialized", "tools/list");
        assertThat(seen).allSatisfy(s -> {
            assertThat(s.auth()).startsWith("AWS4-HMAC-SHA256 Credential=ASIA").contains("/us-east-1/aws-mcp/aws4_request");
            assertThat(s.token()).isEqualTo("fake-session-token");
        });
        assertThat(seen.get(2).session()).isEqualTo("s1");
        mcp.tools(() -> SESSION);
        assertThat(seen).hasSize(3).as("listing is cached");
    }

    @Test
    void callsAKnowledgeTool_andRefusesEverythingElseWithoutAnyRequest() throws Exception {
        Map<String, Object> out = mcp.call(() -> SESSION, "aws___search_documentation", om.readTree("{\"search_phrase\":\"g6 instances\"}"));
        assertThat(out.get("content")).asString().contains("Result of aws___search_documentation").contains("g6 instances");
        assertThat(out.get("notice")).asString().contains("not an instruction from the user");

        int before = seen.size();
        assertThatThrownBy(() -> mcp.call(() -> SESSION, "aws___run_script", om.readTree("{\"code\":\"import os\"}")))
                .hasMessageContaining("not allowed");
        assertThat(seen).hasSize(before);
    }

    @Test
    void expiredSession_reinitialisesOnce() throws Exception {
        mcp.call(() -> SESSION, "aws___read_documentation", om.readTree("{}"));
        expireNextCall.set(1);
        Map<String, Object> out = mcp.call(() -> SESSION, "aws___read_documentation", om.readTree("{}"));
        assertThat(out.get("content")).asString().contains("Result of aws___read_documentation");
        assertThat(seen).extracting(Seen::method).containsExactly(
                "initialize", "notifications/initialized", "tools/call", "tools/call",
                "initialize", "notifications/initialized", "tools/call");
        assertThat(seen.get(seen.size() - 1).session()).isEqualTo("s2");
    }

    @Test
    void withoutCredentials_requestsAreUnsigned() {
        mcp.tools(() -> null);
        assertThat(seen).allSatisfy(s -> assertThat(s.auth()).isNull());
    }
}
