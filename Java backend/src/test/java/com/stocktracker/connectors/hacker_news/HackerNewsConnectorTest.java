package com.stocktracker.connectors.hacker_news;

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
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Hacker News connector against a fake HN Search (Algolia) API, payloads recorded from hn.algolia.com. */
class HackerNewsConnectorTest {

    static final Instant NOW = Instant.parse("2026-11-14T20:00:00Z");

    final ObjectMapper om = new ObjectMapper();
    final List<String> calls = new CopyOnWriteArrayList<>();
    HttpServer server;
    HackerNewsConnector hn;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.start();
        hn = new HackerNewsConnector(Clock.fixed(NOW, ZoneOffset.UTC), "http://127.0.0.1:" + server.getAddress().getPort() + "/api/v1");
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    ConnectorContext ctx() {
        return new ConnectorContext(hn.spec(), k -> Optional.empty(), HttpClient.newHttpClient(), om, () -> { });
    }

    static final Pattern FROM = Pattern.compile("created_at_i>=(\\d+)");

    void handle(HttpExchange ex) throws IOException {
        String q = URLDecoder.decode(ex.getRequestURI().getRawQuery(), StandardCharsets.UTF_8);
        calls.add(ex.getRequestURI().getPath() + "?" + q);
        String body;
        Matcher m = FROM.matcher(q);
        if (q.contains("hitsPerPage=0") && m.find()) {
            long day = (Long.parseLong(m.group(1)) - Instant.parse("2026-11-01T00:00:00Z").getEpochSecond()) / 86400;
            body = "{\"hits\":[],\"nbHits\":" + (day == 12 ? 30 : 10) + ",\"hitsPerPage\":0}";   // spike on 2026-11-13
        } else {
            body = SEARCH;
        }
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(200, b.length);
        ex.getResponseBody().write(b);
        ex.close();
    }

    static final String SEARCH = """
            {"hits":[
             {"_highlightResult":{},"_tags":["story","author_pretext","story_50499553"],"author":"pretext","created_at":"2026-11-14T18:38:56Z",
              "created_at_i":1794681536,"num_comments":0,"objectID":"50499553","points":5,"story_id":50499553,
              "title":"Nvidia board urged to consider 'return of Jensen Huang' by two major investors","updated_at":"2026-11-14T18:44:23Z",
              "url":"https://www.cnbc.com/2026/11/14/nvidia-investors-jensen-huang-return-board-letter.html"},
             {"_highlightResult":{},"_tags":["comment","author_extraextra","story_50498741"],"author":"extraextra",
              "comment_text":"I agree with your point<p>In regards to China, they don&#x27;t mind &gt; importing <a href=\\"x\\">chips</a>",
              "created_at":"2026-11-14T18:32:39Z","created_at_i":1794681159,"objectID":"50499489","parent_id":50499271,"story_id":50498741,
              "story_title":"Kevin Bass: The coverage of the Huang affair","story_url":"https://github.com/x/y"}],
             "nbHits":1915,"page":0,"nbPages":50,"hitsPerPage":2}""";

    @Test
    @SuppressWarnings("unchecked")
    void search_returnsStoriesAndCleanedComments_withCreatedTimes() throws Exception {
        Map<String, Object> out = (Map<String, Object>) hn.search(ctx(), om.readTree(
                "{\"query\":\"nvidia\",\"type\":\"all\",\"days\":7,\"min_points\":3,\"limit\":2}"));
        assertThat(out).containsEntry("total_hits", 1915L).containsEntry("count", 2);
        List<Map<String, Object>> hits = (List<Map<String, Object>>) out.get("hits");
        assertThat(hits.get(0)).containsEntry("type", "story").containsEntry("created_at", "2026-11-14T18:38:56Z")
                .containsEntry("points", 5).containsEntry("hn_url", "https://news.ycombinator.com/item?id=50499553")
                .containsEntry("link", "https://www.cnbc.com/2026/11/14/nvidia-investors-jensen-huang-return-board-letter.html");
        assertThat(hits.get(1)).containsEntry("type", "comment").containsEntry("created_at", "2026-11-14T18:32:39Z")
                .containsEntry("text", "I agree with your point In regards to China, they don't mind > importing chips");
        long since = NOW.minusSeconds(7 * 86400).getEpochSecond();
        assertThat(calls.get(0)).startsWith("/api/v1/search_by_date?query=nvidia&tags=(story,comment)&numericFilters=created_at_i>"
                + since + ",points>=3&hitsPerPage=2");
    }

    @Test
    void relevanceSort_usesTheRelevanceEndpoint() throws Exception {
        hn.search(ctx(), om.readTree("{\"query\":\"nvidia\",\"sort\":\"relevance\"}"));
        assertThat(calls.get(0)).startsWith("/api/v1/search?query=nvidia&tags=story");
    }

    @Test
    @SuppressWarnings("unchecked")
    void mentions_countOneUtcDayPerRequest_andFlagTheSpike() throws Exception {
        Map<String, Object> out = (Map<String, Object>) hn.mentions(ctx(), om.readTree("{\"query\":\"nvidia\",\"days\":4}"));
        List<Map<String, Object>> days = (List<Map<String, Object>>) out.get("per_day");
        assertThat(days).extracting(d -> d.get("date")).containsExactly("2026-11-11", "2026-11-12", "2026-11-13", "2026-11-14");
        assertThat(out).containsEntry("total", 60L).containsEntry("latest_full_day_vs_average", 1.8);
        assertThat((Map<String, Object>) out.get("latest_full_day")).containsEntry("date", "2026-11-13").containsEntry("count", 30L);
        assertThat(calls).hasSize(4).allMatch(c -> c.contains("hitsPerPage=0"));
        assertThat(calls.get(0)).contains("created_at_i>=" + Instant.parse("2026-11-11T00:00:00Z").getEpochSecond()
                + ",created_at_i<" + Instant.parse("2026-11-12T00:00:00Z").getEpochSecond());
    }

    @Test
    void badArgs_areBadRequest() {
        assertThatThrownBy(() -> hn.search(ctx(), om.readTree("{}")))
                .isInstanceOf(ConnectorException.class).extracting("kind").isEqualTo(ConnectorException.Kind.BAD_REQUEST);
        assertThatThrownBy(() -> hn.search(ctx(), om.readTree("{\"query\":\"x\",\"type\":\"job\"}")))
                .extracting("kind").isEqualTo(ConnectorException.Kind.BAD_REQUEST);
        assertThatThrownBy(() -> hn.mentions(ctx(), om.readTree("{\"query\":\"x\",\"type\":\"all\"}")))
                .extracting("kind").isEqualTo(ConnectorException.Kind.BAD_REQUEST);
        assertThat(calls).isEmpty();
        assertThat(hn.spec().keyless()).isTrue();
    }
}
