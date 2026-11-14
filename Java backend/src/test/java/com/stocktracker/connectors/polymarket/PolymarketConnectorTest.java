package com.stocktracker.connectors.polymarket;

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
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Polymarket connector against fake Gamma + CLOB APIs (responses shaped like recorded live ones). */
class PolymarketConnectorTest {

    static final Instant NOW = Instant.parse("2026-11-14T18:00:00Z");

    static final String MARKET = """
            {"id":"616902","question":"Will no Fed rate cuts happen in 2026?","slug":"will-no-fed-rate-cuts-happen-in-2026",
             "groupItemTitle":"0 (0 bps)","outcomes":"[\\"Yes\\", \\"No\\"]","outcomePrices":"[\\"0.9855\\", \\"0.0145\\"]",
             "clobTokenIds":"[\\"111\\", \\"222\\"]","lastTradePrice":0.985,"bestBid":0.985,"bestAsk":0.986,"spread":0.001,
             "oneDayPriceChange":0.002,"oneWeekPriceChange":0.01,"volume":"9644934.654367002","volume24hr":31063.67,"volume1wk":119601.26,
             "liquidity":"147133.79718","endDate":"2027-01-01T04:59:00Z","startDate":"2025-09-29T22:24:45.988997Z","closed":false,
             "resolutionSource":"https://www.federalreserve.gov/monetarypolicy/fomccalendars.htm",
             "description":"This market will resolve according to the exact amount of cuts.","createdAt":"2025-09-29T13:23:19.152108Z",
             "updatedAt":"2026-11-14T18:43:55.920126Z"}""";

    final ObjectMapper om = new ObjectMapper();
    final List<String> requests = new CopyOnWriteArrayList<>();
    final AtomicInteger permits = new AtomicInteger();
    HttpServer server;
    PolymarketConnector poly;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.start();
        String base = "http://127.0.0.1:" + server.getAddress().getPort();
        poly = new PolymarketConnector(base + "/gamma", base + "/clob", Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    ConnectorContext ctx() {
        return new ConnectorContext(poly.spec(), k -> Optional.empty(), HttpClient.newHttpClient(), om, permits::incrementAndGet);
    }

    void handle(HttpExchange ex) throws IOException {
        String p = ex.getRequestURI().getPath();
        String q = ex.getRequestURI().getRawQuery() == null ? "" : URLDecoder.decode(ex.getRequestURI().getRawQuery(), StandardCharsets.UTF_8);
        requests.add(p + "?" + q);
        switch (p) {
            case "/gamma/public-search" -> send(ex, 200, """
                    {"events":[{"id":"51456","slug":"how-many-fed-rate-cuts-in-2026","title":"How many Fed rate cuts in 2026?",
                      "endDate":"2027-01-01T04:59:00Z","closed":false,"volume":55902405.04,"volume24hr":120000.5,"liquidity":3167993.67,
                      "openInterest":1974063.06,"updatedAt":"2026-11-14T18:46:10.528059Z","markets":[%s,%s]}],
                     "pagination":{"hasMore":true,"totalResults":41}}""".formatted(MARKET, MARKET));
            case "/gamma/events" -> send(ex, 200, q.contains("slug=nope") ? "[]"
                    : "[{\"slug\":\"how-many-fed-rate-cuts-in-2026\",\"title\":\"How many Fed rate cuts in 2026?\",\"markets\":[" + MARKET + "]}]");
            case "/gamma/markets" -> send(ex, 200, q.contains("slug=will-no-fed-rate-cuts-happen-in-2026") ? "[" + MARKET + "]" : "[]");
            case "/clob/prices-history" -> send(ex, 200, """
                    {"history":[{"t":1794096059,"p":0.9785},{"t":1794182458,"p":0.9805}]}""");
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

    @Test
    void spec_isKeyless_andToolsArePrefixed() {
        assertThat(poly.spec().keyless()).isTrue();
        assertThat(poly.tools()).allSatisfy(t -> assertThat(t.name()).startsWith("polymarket_"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void search_decodesStringEncodedPrices_capsMarkets_andKeepsTimestamps() throws Exception {
        Map<String, Object> out = (Map<String, Object>) poly.search(ctx(), om.readTree("{\"query\":\"fed rate\",\"markets_per_event\":1}"));
        assertThat(out).containsEntry("total_results", 41).containsEntry("count", 1);
        Map<String, Object> ev = ((List<Map<String, Object>>) out.get("events")).get(0);
        assertThat(ev).containsEntry("market_count", 2).containsEntry("updated_at", "2026-11-14T18:46:10.528059Z");
        Map<String, Object> m = ((List<Map<String, Object>>) ev.get("markets")).get(0);
        assertThat(m).containsEntry("yes_price", 0.9855).containsEntry("volume", 9644934.654367002)
                .containsEntry("label", "0 (0 bps)").containsEntry("updated_at", "2026-11-14T18:43:55.920126Z");
        assertThat((Map<String, Object>) m.get("outcome_prices")).containsEntry("No", 0.0145);
        assertThat(requests.get(0)).contains("q=fed rate").contains("events_status=active");

        poly.search(ctx(), om.readTree("{\"query\":\"fed\",\"status\":\"all\"}"));
        assertThat(requests.get(1)).doesNotContain("events_status");
    }

    @Test
    @SuppressWarnings("unchecked")
    void market_detail_andUnknownSlugIsBadRequest() throws Exception {
        Map<String, Object> m = (Map<String, Object>) poly.market(ctx(), om.readTree("{\"slug\":\"will-no-fed-rate-cuts-happen-in-2026\"}"));
        assertThat(m).containsEntry("best_bid", 0.985).containsEntry("change_1w", 0.01).containsEntry("created_at", "2025-09-29T13:23:19.152108Z");
        assertThatThrownBy(() -> poly.market(ctx(), om.readTree("{\"slug\":\"how-many-fed-rate-cuts-in-2026\"}")))
                .isInstanceOf(ConnectorException.class).hasMessageContaining("event slug is not a market slug")
                .extracting("kind").isEqualTo(ConnectorException.Kind.BAD_REQUEST);
        assertThatThrownBy(() -> poly.event(ctx(), om.readTree("{\"slug\":\"nope\"}")))
                .extracting("kind").isEqualTo(ConnectorException.Kind.BAD_REQUEST);
    }

    @Test
    @SuppressWarnings("unchecked")
    void history_resolvesTheOutcomeToken_andBoundsTheWindow() throws Exception {
        Map<String, Object> out = (Map<String, Object>) poly.history(ctx(),
                om.readTree("{\"slug\":\"will-no-fed-rate-cuts-happen-in-2026\",\"outcome\":\"no\",\"days\":7}"));
        assertThat(out).containsEntry("outcome", "No").containsEntry("count", 2).containsEntry("interval_minutes", 50);
        assertThat(((List<Map<String, Object>>) out.get("points")).get(0)).containsEntry("t", "2026-11-08T00:00:59Z").containsEntry("p", 0.9785);
        String hist = requests.get(1);
        long end = NOW.getEpochSecond();
        assertThat(hist).contains("market=222").contains("endTs=" + end).contains("startTs=" + (end - 7 * 86400));
        assertThat(permits).hasValue(2);

        assertThatThrownBy(() -> poly.history(ctx(), om.readTree("{\"slug\":\"will-no-fed-rate-cuts-happen-in-2026\",\"outcome\":\"Maybe\"}")))
                .extracting("kind").isEqualTo(ConnectorException.Kind.BAD_REQUEST);
    }

    @Test
    void badArguments_areBadRequests() {
        assertThatThrownBy(() -> poly.search(ctx(), om.readTree("{}"))).extracting("kind").isEqualTo(ConnectorException.Kind.BAD_REQUEST);
        assertThatThrownBy(() -> poly.market(ctx(), om.readTree("{\"slug\":\"../admin?x=1\"}"))).extracting("kind").isEqualTo(ConnectorException.Kind.BAD_REQUEST);
        assertThatThrownBy(() -> poly.search(ctx(), om.readTree("{\"query\":\"x\",\"status\":\"open\"}"))).extracting("kind").isEqualTo(ConnectorException.Kind.BAD_REQUEST);
        assertThat(requests).isEmpty();
    }
}
