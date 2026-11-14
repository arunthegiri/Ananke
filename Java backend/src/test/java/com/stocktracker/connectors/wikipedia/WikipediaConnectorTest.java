package com.stocktracker.connectors.wikipedia;

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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Wikipedia pageviews connector against a fake Wikimedia REST API + action API. */
class WikipediaConnectorTest {

    static final Instant NOW = Instant.parse("2026-11-14T20:00:00Z");

    final ObjectMapper om = new ObjectMapper();
    final List<String> calls = new CopyOnWriteArrayList<>();
    final List<String> agents = new CopyOnWriteArrayList<>();
    HttpServer server;
    WikipediaConnector wiki;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.start();
        String base = "http://127.0.0.1:" + server.getAddress().getPort();
        wiki = new WikipediaConnector(Clock.fixed(NOW, ZoneOffset.UTC), base + "/rest", base + "/site/%s");
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    ConnectorContext ctx() {
        return new ConnectorContext(wiki.spec(), k -> Optional.empty(), HttpClient.newHttpClient(), om, () -> { });
    }

    void handle(HttpExchange ex) throws IOException {
        String raw = ex.getRequestURI().getRawPath();
        String q = ex.getRequestURI().getRawQuery();
        String full = raw + (q == null ? "" : "?" + URLDecoder.decode(q, StandardCharsets.UTF_8));
        calls.add(full);
        agents.add(ex.getRequestHeaders().getFirst("User-Agent"));
        if (raw.equals("/site/en.wikipedia/w/api.php")) {
            if (full.contains("titles=NVIDIA")) {
                send(ex, 200, "{\"batchcomplete\":true,\"query\":{\"redirects\":[{\"from\":\"NVIDIA\",\"to\":\"Nvidia\"}],\"pages\":[{\"pageid\":39120,\"ns\":0,\"title\":\"Nvidia\"}]}}");
            } else if (full.contains("titles=No such")) {
                send(ex, 200, "{\"batchcomplete\":true,\"query\":{\"pages\":[{\"ns\":0,\"title\":\"No such\",\"missing\":true}]}}");
            } else {
                send(ex, 200, "{\"batchcomplete\":true,\"query\":{\"pages\":[{\"pageid\":1,\"ns\":0,\"title\":\"AT&T\"}]}}");
            }
        } else if (raw.equals("/rest/metrics/pageviews/per-article/en.wikipedia/all-access/user/Nvidia/daily/20261107/20261113")) {
            // 2026-11-10 missing (the API omits zero days)
            send(ex, 200, "{\"items\":[" + pv("2026110700", 100) + "," + pv("2026110800", 120) + "," + pv("2026110900", 80) + ","
                    + pv("2026111100", 110) + "," + pv("2026111200", 90) + "," + pv("2026111300", 1000) + "]}");
        } else if (raw.startsWith("/rest/metrics/pageviews/per-article/en.wikipedia/all-access/user/AT%26T/")) {
            send(ex, 404, "{\"detail\":\"The date(s) you used are valid, but we either do not have data for those date(s)\",\"status\":404}");
        } else if (raw.equals("/rest/metrics/pageviews/top/en.wikipedia/all-access/2026/11/13")) {
            send(ex, 200, top("13", "[" + art("Main_Page", 4142452, 1) + "," + art("Jensen_Huang", 1072168, 2) + ","
                    + art("Special:Search", 849642, 3) + "," + art("Colette_Kress", 463931, 4) + "," + art("Taylor_Swift", 300000, 5) + "]"));
        } else if (raw.equals("/rest/metrics/pageviews/top/en.wikipedia/all-access/2026/11/12")) {
            send(ex, 200, top("12", "[" + art("Main_Page", 4000000, 1) + "," + art("Jensen_Huang", 100000, 2) + ","
                    + art("Taylor_Swift", 290000, 3) + "]"));
        } else {
            send(ex, 404, "{}");
        }
    }

    static String pv(String ts, long v) {
        return "{\"project\":\"en.wikipedia\",\"article\":\"Nvidia\",\"granularity\":\"daily\",\"timestamp\":\"" + ts
                + "\",\"access\":\"all-access\",\"agent\":\"user\",\"views\":" + v + "}";
    }

    static String art(String a, long v, int rank) {
        return "{\"article\":\"" + a + "\",\"views\":" + v + ",\"rank\":" + rank + "}";
    }

    static String top(String day, String articles) {
        return "{\"items\":[{\"project\":\"en.wikipedia\",\"access\":\"all-access\",\"year\":\"2026\",\"month\":\"11\",\"day\":\"" + day
                + "\",\"articles\":" + articles + "}]}";
    }

    void send(HttpExchange ex, int status, String body) throws IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(status, b.length);
        ex.getResponseBody().write(b);
        ex.close();
    }

    @Test
    @SuppressWarnings("unchecked")
    void pageviews_followRedirects_fillMissingDays_andMeasureTheSpike() throws Exception {
        Map<String, Object> out = (Map<String, Object>) wiki.pageviews(ctx(), om.readTree("{\"article\":\"NVIDIA\",\"days\":7}"));
        assertThat(out).containsEntry("article", "Nvidia").containsEntry("requested", "NVIDIA")
                .containsEntry("from", "2026-11-07").containsEntry("to", "2026-11-13")
                .containsEntry("total", 1500L).containsEntry("median_before_latest", 95L).containsEntry("latest_vs_median", 10.53);
        List<Map<String, Object>> pts = (List<Map<String, Object>>) out.get("points");
        assertThat(pts).hasSize(7);
        assertThat(pts.get(3)).containsEntry("date", "2026-11-10").containsEntry("views", 0L);
        assertThat((Map<String, Object>) out.get("peak")).containsEntry("date", "2026-11-13");
        assertThat(agents).allSatisfy(a -> assertThat(a).startsWith("Ananke/1 ("));
    }

    @Test
    void unknownArticle_orNoData_isBadRequest() {
        assertThatThrownBy(() -> wiki.pageviews(ctx(), om.readTree("{\"article\":\"No such\"}")))
                .hasMessageContaining("No Wikipedia article").extracting("kind").isEqualTo(ConnectorException.Kind.BAD_REQUEST);
        assertThatThrownBy(() -> wiki.pageviews(ctx(), om.readTree("{\"article\":\"AT&T\"}")))
                .hasMessageContaining("No pageviews").extracting("kind").isEqualTo(ConnectorException.Kind.BAD_REQUEST);
        // titles are path-encoded ("&" must not split the path)
        assertThat(calls).anyMatch(c -> c.contains("/user/AT%26T/daily/"));
    }

    @Test
    void badArgs_areBadRequest_beforeAnyCall() {
        assertThatThrownBy(() -> wiki.pageviews(ctx(), om.readTree("{}")))
                .extracting("kind").isEqualTo(ConnectorException.Kind.BAD_REQUEST);
        assertThatThrownBy(() -> wiki.pageviews(ctx(), om.readTree("{\"article\":\"Nvidia\",\"project\":\"evil.com/x\"}")))
                .extracting("kind").isEqualTo(ConnectorException.Kind.BAD_REQUEST);
        assertThatThrownBy(() -> wiki.pageviews(ctx(), om.readTree("{\"article\":\"Nvidia\",\"end\":\"2026-11-14\"}")))
                .extracting("kind").isEqualTo(ConnectorException.Kind.BAD_REQUEST);
        assertThatThrownBy(() -> wiki.top(ctx(), om.readTree("{\"sort\":\"weird\"}")))
                .extracting("kind").isEqualTo(ConnectorException.Kind.BAD_REQUEST);
        assertThat(calls).isEmpty();
    }

    @Test
    @SuppressWarnings("unchecked")
    void top_dropsNavigationPages_andRanksMovers() throws Exception {
        Map<String, Object> byViews = (Map<String, Object>) wiki.top(ctx(), om.readTree("{}"));
        List<Map<String, Object>> rows = (List<Map<String, Object>>) byViews.get("articles");
        assertThat(byViews).containsEntry("date", "2026-11-13");
        assertThat(rows).extracting(r -> r.get("article")).containsExactly("Jensen_Huang", "Colette_Kress", "Taylor_Swift");
        assertThat(rows.get(0)).containsEntry("views_prev_day", 100000L).containsEntry("change", 10.72);
        assertThat(rows.get(1)).containsEntry("views_prev_day", null);

        Map<String, Object> movers = (Map<String, Object>) wiki.top(ctx(), om.readTree("{\"sort\":\"change\",\"limit\":2}"));
        assertThat((List<Map<String, Object>>) movers.get("articles")).extracting(r -> r.get("article"))
                .containsExactly("Colette_Kress", "Jensen_Huang");   // new entrant first, then the biggest jump
    }
}
