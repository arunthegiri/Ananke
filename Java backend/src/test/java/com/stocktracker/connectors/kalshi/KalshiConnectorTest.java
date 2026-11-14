package com.stocktracker.connectors.kalshi;

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

/** Kalshi connector against a fake Kalshi API (responses shaped like recorded live ones). */
class KalshiConnectorTest {

    static final Instant NOW = Instant.parse("2026-11-14T18:00:00Z");

    final ObjectMapper om = new ObjectMapper();
    final List<String> requests = new CopyOnWriteArrayList<>();
    final AtomicInteger permits = new AtomicInteger();
    HttpServer server;
    KalshiConnector kalshi;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.start();
        kalshi = new KalshiConnector("http://127.0.0.1:" + server.getAddress().getPort() + "/v2", Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    ConnectorContext ctx() {
        return new ConnectorContext(kalshi.spec(), k -> Optional.empty(), HttpClient.newHttpClient(), om, permits::incrementAndGet);
    }

    void handle(HttpExchange ex) throws IOException {
        String p = ex.getRequestURI().getPath();
        String q = ex.getRequestURI().getRawQuery() == null ? "" : URLDecoder.decode(ex.getRequestURI().getRawQuery(), StandardCharsets.UTF_8);
        requests.add(p + "?" + q);
        if (p.equals("/v2/series") && q.contains("category=Economics")) {
            send(ex, 200, """
                    {"series":[
                     {"ticker":"KXFED","title":"Fed rate decision","category":"Economics","tags":["Fed","Interest Rates"],"frequency":"custom","last_updated_ts":"2026-11-13T01:39:23Z","volume_fp":"91000000.00"},
                     {"ticker":"KXRATEHIKE","title":"Number of Fed rate hikes","category":"Economics","tags":["Fed"],"frequency":"annual","last_updated_ts":"2026-10-01T00:00:00Z","volume_fp":"12000.00"},
                     {"ticker":"KXCPI","title":"CPI","category":"Economics","tags":["Inflation"],"frequency":"monthly","last_updated_ts":"2026-11-10T00:00:00Z","volume_fp":"5000000.00"}]}""");
        } else if (p.equals("/v2/series")) {
            send(ex, 200, "{\"series\":[]}");
        } else if (p.equals("/v2/series/KXFED")) {
            send(ex, 200, "{\"series\":{\"ticker\":\"KXFED\",\"title\":\"Fed rate decision\"}}");
        } else if (p.equals("/v2/events")) {
            send(ex, 200, """
                    {"cursor":"x","events":[{"category":"Economics","event_ticker":"KXFED-26DEC","last_updated_ts":"2026-11-13T01:39:33.37608Z",
                     "mutually_exclusive":false,"series_ticker":"KXFED","sub_title":"Dec 9, 2026","title":"Fed decision in Dec 2026?",
                     "markets":[
                      {"ticker":"KXFED-26DEC-T2.75","yes_sub_title":"Above 2.75%","status":"active","yes_bid_dollars":"0.9900","yes_ask_dollars":"1.0000",
                       "last_price_dollars":"0.9800","volume_fp":"23899.11","volume_24h_fp":"0.00","open_interest_fp":"6801.79",
                       "close_time":"2026-12-09T18:55:00Z","result":"","updated_time":"2026-10-27T18:37:20.024122Z"},
                      {"ticker":"KXFED-26DEC-T3.50","yes_sub_title":"Above 3.50%","status":"active","yes_bid_dollars":"0.4100","yes_ask_dollars":"0.4300",
                       "last_price_dollars":"0.4200","volume_fp":"16265.88","volume_24h_fp":"1500.00","open_interest_fp":"17765.88",
                       "close_time":"2026-12-09T18:55:00Z","result":"","updated_time":"2026-11-13T22:00:00Z"}]}]}""");
        } else if (p.equals("/v2/markets/KXFED-26DEC-T3.50")) {
            send(ex, 200, """
                    {"market":{"ticker":"KXFED-26DEC-T3.50","event_ticker":"KXFED-26DEC","title":"Will the upper bound be above 3.50%?",
                     "yes_sub_title":"Above 3.50%","status":"active","yes_bid_dollars":"0.4100","yes_ask_dollars":"0.4300","last_price_dollars":"0.4200",
                     "previous_price_dollars":"0.3900","no_bid_dollars":"0.5700","no_ask_dollars":"0.5900","volume_fp":"16265.88","volume_24h_fp":"1500.00",
                     "open_interest_fp":"17765.88","close_time":"2026-12-09T18:55:00Z","open_time":"2025-08-06T14:30:00Z",
                     "expected_expiration_time":"2026-12-09T19:05:00Z","rules_primary":"If the upper bound ... then Yes.","result":"",
                     "updated_time":"2026-11-13T22:00:00Z"}}""");
        } else if (p.equals("/v2/markets/candlesticks")) {
            send(ex, 200, """
                    {"markets":[{"market_ticker":"KXFED-26DEC-T3.50","candlesticks":[
                     {"end_period_ts":1794114000,"open_interest_fp":"16265.88","price":{"previous_dollars":"0.3800"},"volume_fp":"0.00",
                      "yes_ask":{"close_dollars":"0.4000"},"yes_bid":{"close_dollars":"0.3700"}},
                     {"end_period_ts":1794200400,"open_interest_fp":"17765.88","price":{"close_dollars":"0.4200","previous_dollars":"0.3800"},"volume_fp":"1500.00",
                      "yes_ask":{"close_dollars":"0.4300"},"yes_bid":{"close_dollars":"0.4100"}}]}]}""");
        } else {
            send(ex, 404, "{\"error\":{\"code\":\"not_found\",\"message\":\"not found\"}}");
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
    void spec_isKeylessPredictionMarkets_andToolsArePrefixed() {
        assertThat(kalshi.spec().keyless()).isTrue();
        assertThat(kalshi.tools()).allSatisfy(t -> assertThat(t.name()).startsWith("kalshi_"));
    }

    @Test
    void test_makesOneCall() throws Exception {
        assertThat(kalshi.test(ctx())).contains("Fed rate decision");
        assertThat(permits).hasValue(1);
    }

    @Test
    @SuppressWarnings("unchecked")
    void search_matchesEveryWord_ranksByVolume_andCachesTheSeriesList() throws Exception {
        Map<String, Object> out = (Map<String, Object>) kalshi.search(ctx(), om.readTree("{\"query\":\"Fed rate\",\"category\":\"economics\"}"));
        List<Map<String, Object>> rows = (List<Map<String, Object>>) out.get("series");
        assertThat(rows).extracting(r -> r.get("series_ticker")).containsExactly("KXFED", "KXRATEHIKE");
        assertThat(rows.get(0)).containsEntry("volume", 9.1E7).containsEntry("updated_at", "2026-11-13T01:39:23Z");
        assertThat(requests.get(0)).contains("category=Economics").contains("include_volume=true");

        kalshi.search(ctx(), om.readTree("{\"query\":\"cpi\",\"category\":\"Economics\"}"));
        assertThat(requests).hasSize(1);   // in-memory series cache

        // Default categories: four lists, each fetched once.
        kalshi.search(ctx(), om.readTree("{\"query\":\"fed\"}"));
        assertThat(requests).hasSize(4);
        assertThat(permits).hasValue(requests.size());
    }

    @Test
    void badArguments_areBadRequests() {
        assertThatThrownBy(() -> kalshi.search(ctx(), om.readTree("{\"query\":\"  \"}")))
                .isInstanceOf(ConnectorException.class).extracting("kind").isEqualTo(ConnectorException.Kind.BAD_REQUEST);
        assertThatThrownBy(() -> kalshi.search(ctx(), om.readTree("{\"query\":\"fed\",\"category\":\"Astrology\"}")))
                .extracting("kind").isEqualTo(ConnectorException.Kind.BAD_REQUEST);
        assertThatThrownBy(() -> kalshi.events(ctx(), om.readTree("{\"series_ticker\":\"KX FED/../x\"}")))
                .extracting("kind").isEqualTo(ConnectorException.Kind.BAD_REQUEST);
        assertThat(requests).isEmpty();
    }

    @Test
    @SuppressWarnings("unchecked")
    void events_parsePrices_andCapMarkets() throws Exception {
        Map<String, Object> out = (Map<String, Object>) kalshi.events(ctx(), om.readTree("{\"series_ticker\":\"kxfed\",\"markets_per_event\":1}"));
        List<Map<String, Object>> events = (List<Map<String, Object>>) out.get("events");
        assertThat(events).hasSize(1);
        assertThat(events.get(0)).containsEntry("event_ticker", "KXFED-26DEC").containsEntry("market_count", 2)
                .containsEntry("updated_at", "2026-11-13T01:39:33.37608Z");
        List<Map<String, Object>> markets = (List<Map<String, Object>>) events.get(0).get("markets");
        assertThat(markets).hasSize(1);
        assertThat(markets.get(0)).containsEntry("label", "Above 2.75%").containsEntry("yes_bid", 0.99)
                .containsEntry("last_price", 0.98).containsEntry("open_interest", 6801.79)
                .containsEntry("updated_at", "2026-10-27T18:37:20.024122Z").doesNotContainKey("result");
        assertThat(requests.get(0)).contains("series_ticker=KXFED").contains("status=open").contains("with_nested_markets=true");
    }

    @Test
    @SuppressWarnings("unchecked")
    void market_detail_andUnknownTickerIsBadRequest() throws Exception {
        Map<String, Object> m = (Map<String, Object>) kalshi.market(ctx(), om.readTree("{\"ticker\":\"KXFED-26DEC-T3.50\"}"));
        assertThat(m).containsEntry("previous_price", 0.39).containsEntry("rules", "If the upper bound ... then Yes.")
                .containsEntry("updated_at", "2026-11-13T22:00:00Z");
        assertThatThrownBy(() -> kalshi.market(ctx(), om.readTree("{\"ticker\":\"NOPE-1\"}")))
                .extracting("kind").isEqualTo(ConnectorException.Kind.BAD_REQUEST);
    }

    @Test
    @SuppressWarnings("unchecked")
    void history_dailyCandles_withIsoTimes_andPreviousPriceWhenNoTrade() throws Exception {
        Map<String, Object> out = (Map<String, Object>) kalshi.history(ctx(), om.readTree("{\"ticker\":\"KXFED-26DEC-T3.50\",\"days\":7}"));
        List<Map<String, Object>> candles = (List<Map<String, Object>>) out.get("candles");
        assertThat(candles).hasSize(2);
        assertThat(candles.get(0)).containsEntry("t", "2026-11-08T05:00:00Z").containsEntry("price", 0.38).containsEntry("traded", false);
        assertThat(candles.get(1)).containsEntry("price", 0.42).containsEntry("traded", true).containsEntry("volume", 1500.0);
        long end = NOW.getEpochSecond();
        assertThat(requests.get(0)).contains("period_interval=1440").contains("end_ts=" + end).contains("start_ts=" + (end - 7 * 86400));
    }
}
