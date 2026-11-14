package com.stocktracker.connectors.bluesky;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stocktracker.connectors.ConnectorContext;
import com.stocktracker.connectors.ConnectorException;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Bluesky connector against a fake AppView + PDS (payloads recorded from api.bsky.app). */
class BlueskyConnectorTest {

    final ObjectMapper om = new ObjectMapper();
    final List<String> calls = new CopyOnWriteArrayList<>();
    final Map<String, String> fields = new HashMap<>();
    HttpServer server;
    BlueskyConnector bsky;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.start();
        String base = "http://127.0.0.1:" + server.getAddress().getPort();
        bsky = new BlueskyConnector(Clock.systemUTC(), base + "/appview", base + "/pds");
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    ConnectorContext ctx() {
        return new ConnectorContext(bsky.spec(), k -> Optional.ofNullable(fields.get(k)), HttpClient.newHttpClient(), om, () -> { });
    }

    void handle(HttpExchange ex) throws IOException {
        String p = ex.getRequestURI().getPath();
        String q = ex.getRequestURI().getRawQuery();
        String auth = ex.getRequestHeaders().getFirst("Authorization");
        calls.add(p + (q == null ? "" : "?" + URLDecoder.decode(q, StandardCharsets.UTF_8)) + (auth == null ? "" : " [" + auth + "]"));
        switch (p) {
            case "/appview/xrpc/app.bsky.feed.searchPosts" -> send(ex, 200, SEARCH);
            case "/pds/xrpc/app.bsky.feed.searchPosts" -> {
                if ("Bearer jwt-1".equals(auth)) send(ex, 200, SEARCH);
                else send(ex, 401, "{\"error\":\"AuthenticationRequired\"}");
            }
            case "/pds/xrpc/com.atproto.server.createSession" -> {
                String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                if (body.contains("\"password\":\"good-pass\"")) send(ex, 200, "{\"accessJwt\":\"jwt-1\",\"refreshJwt\":\"r\",\"handle\":\"me.bsky.social\",\"did\":\"did:plc:me\"}");
                else send(ex, 401, "{\"error\":\"AuthenticationRequired\",\"message\":\"Invalid identifier or password\"}");
            }
            default -> send(ex, 404, "{}");
        }
    }

    void send(HttpExchange ex, int status, String body) throws IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(status, b.length);
        ex.getResponseBody().write(b);
        ex.close();
    }

    static final String SEARCH = """
            {"posts":[{"uri":"at://did:plc:6nkz2fo4vmtumpljkoqy6l54/app.bsky.feed.post/3n2c5jga45c2m","cid":"bafy",
              "author":{"did":"did:plc:6nkz2fo4vmtumpljkoqy6l54","handle":"econanalytica.bsky.social","displayName":"Econ Analytica","labels":[],"createdAt":"2024-11-27T16:25:28.946Z"},
              "record":{"$type":"app.bsky.feed.post","createdAt":"2026-11-14T18:39:51.761Z",
                "facets":[{"features":[{"$type":"app.bsky.richtext.facet#tag","tag":"$NVDA"}],"index":{"byteEnd":167,"byteStart":162}}],
                "langs":["en"],"text":"All corruption all the time\\n\\n“.. reported a nearly 50% decline in the number of $NVDA shares it held ..”"},
              "embed":{"external":{"uri":"https://www.cnbc.com/2026/11/13/howard-lutnick-nvidia.html","title":"Lutnick faces questions over Nvidia","description":"d","thumb":"t"},"$type":"app.bsky.embed.external#view"},
              "bookmarkCount":0,"replyCount":2,"repostCount":1,"likeCount":7,"quoteCount":0,"indexedAt":"2026-11-14T18:39:54.048Z","labels":[]}],
             "cursor":"abc","hitsTotal":6154}""";

    @Test
    @SuppressWarnings("unchecked")
    void publicSearch_needsNoAccount_andKeepsBothTimestamps() throws Exception {
        assertThat(bsky.spec().keyless()).isTrue();
        Map<String, Object> out = (Map<String, Object>) bsky.searchPosts(ctx(), om.readTree(
                "{\"query\":\"$NVDA\",\"since\":\"2026-11-01\",\"until\":\"2026-11-15\",\"lang\":\"en\",\"limit\":5}"));
        assertThat(out).containsEntry("hits_total", 6154L).containsEntry("count", 1);
        Map<String, Object> post = ((List<Map<String, Object>>) out.get("posts")).get(0);
        assertThat(post).containsEntry("created_at", "2026-11-14T18:39:51.761Z").containsEntry("indexed_at", "2026-11-14T18:39:54.048Z")
                .containsEntry("author", "econanalytica.bsky.social").containsEntry("likes", 7).containsEntry("replies", 2)
                .containsEntry("url", "https://bsky.app/profile/econanalytica.bsky.social/post/3n2c5jga45c2m");
        assertThat((String) post.get("text")).doesNotContain("\n").contains("$NVDA");
        assertThat(calls).singleElement().asString()
                .startsWith("/appview/xrpc/app.bsky.feed.searchPosts?q=$NVDA&sort=latest&since=2026-11-01T00:00:00Z&until=2026-11-15T00:00:00Z&lang=en&limit=5")
                .doesNotContain("Bearer");
    }

    @Test
    @SuppressWarnings("unchecked")
    void withAppPassword_signsInOnce_andSearchesThroughThePds() throws Exception {
        fields.put("handle", "@me.bsky.social");
        fields.put("app_password", "good-pass");
        bsky.searchPosts(ctx(), om.readTree("{\"query\":\"nvidia\"}"));
        Map<String, Object> out = (Map<String, Object>) bsky.searchPosts(ctx(), om.readTree("{\"query\":\"nvidia\",\"sort\":\"top\"}"));
        assertThat(out).containsEntry("count", 1);
        assertThat(calls).hasSize(3);
        assertThat(calls.get(0)).startsWith("/pds/xrpc/com.atproto.server.createSession");
        assertThat(calls.get(1)).startsWith("/pds/xrpc/app.bsky.feed.searchPosts").endsWith("[Bearer jwt-1]");
        assertThat(calls.get(2)).contains("sort=top");
        assertThat(bsky.test(ctx())).contains("signed in as @me.bsky.social");
    }

    @Test
    void wrongAppPassword_isAuth() {
        fields.put("handle", "me.bsky.social");
        fields.put("app_password", "nope");
        assertThatThrownBy(() -> bsky.test(ctx())).isInstanceOf(ConnectorException.class)
                .hasMessageContaining("handle or app password").extracting("kind").isEqualTo(ConnectorException.Kind.AUTH);
    }

    @Test
    void halfConfiguredAccount_andBadArgs_areBadRequest() {
        assertThatThrownBy(() -> bsky.searchPosts(ctx(), om.readTree("{}")))
                .extracting("kind").isEqualTo(ConnectorException.Kind.BAD_REQUEST);
        assertThatThrownBy(() -> bsky.searchPosts(ctx(), om.readTree("{\"query\":\"x\",\"since\":\"14/11/2026\"}")))
                .extracting("kind").isEqualTo(ConnectorException.Kind.BAD_REQUEST);
        assertThatThrownBy(() -> bsky.searchPosts(ctx(), om.readTree("{\"query\":\"x\",\"since\":\"2026-11-10\",\"until\":\"2026-11-01\"}")))
                .extracting("kind").isEqualTo(ConnectorException.Kind.BAD_REQUEST);
        assertThatThrownBy(() -> bsky.searchPosts(ctx(), om.readTree("{\"query\":\"x\",\"sort\":\"hot\"}")))
                .extracting("kind").isEqualTo(ConnectorException.Kind.BAD_REQUEST);
        fields.put("handle", "me.bsky.social");
        assertThatThrownBy(() -> bsky.searchPosts(ctx(), om.readTree("{\"query\":\"x\"}")))
                .extracting("kind").isEqualTo(ConnectorException.Kind.BAD_REQUEST);
        assertThat(calls).isEmpty();
    }
}
