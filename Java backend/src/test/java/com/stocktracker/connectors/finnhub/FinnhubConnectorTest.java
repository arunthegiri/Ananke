package com.stocktracker.connectors.finnhub;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stocktracker.connectors.ConnectorContext;
import com.stocktracker.connectors.ConnectorException;
import com.stocktracker.connectors.ConnectorException.Kind;
import com.stocktracker.connectors.ConnectorTool;
import com.stocktracker.connectors.remote.FakeRestServer;
import com.stocktracker.connectors.remote.FakeRestServer.Response;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Finnhub against a fake API shaped like its swagger definitions. */
class FinnhubConnectorTest {

    static final String KEY = "fh-key";

    final ObjectMapper om = new ObjectMapper();
    final AtomicInteger permits = new AtomicInteger();
    FakeRestServer server;
    FinnhubConnector fh;

    @BeforeEach
    void start() throws Exception {
        server = new FakeRestServer("X-Finnhub-Token");
        server.route = r -> {
            String token = r.headers().get("X-Finnhub-Token");
            if (token == null) return new Response(401, "{\"error\":\"Please use an API key.\"}");
            if (!KEY.equals(token)) return new Response(401, "{\"error\":\"Invalid API key\"}");
            return switch (r.path()) {
                case "/quote" -> Response.ok("{\"c\":257.3,\"d\":1.3,\"dp\":0.51,\"h\":258,\"l\":255.5,\"o\":256.1,\"pc\":256,\"t\":1763150400}");
                case "/company-news" -> Response.ok("""
                        [{"category":"company","datetime":1763100000,"headline":"Older","id":1,"image":"","related":"AAPL","source":"Yahoo","summary":"a","url":"https://x/1"},
                         {"category":"company","datetime":1763130000,"headline":"Newer","id":2,"image":"","related":"AAPL","source":"MarketWatch","summary":"b","url":"https://x/2"}]""");
                case "/calendar/earnings" -> Response.ok("""
                        {"earningsCalendar":[
                          {"date":"2026-11-19","epsActual":null,"epsEstimate":1.25,"hour":"amc","quarter":3,"revenueActual":null,"revenueEstimate":54000000000,"symbol":"NVDA","year":2026},
                          {"date":"2026-11-18","epsActual":null,"epsEstimate":2.1,"hour":"bmo","quarter":3,"revenueActual":null,"revenueEstimate":170000000000,"symbol":"WMT","year":2026}]}""");
                case "/stock/recommendation" -> Response.ok("""
                        [{"buy":24,"hold":7,"period":"2026-10-01","sell":0,"strongBuy":13,"strongSell":0,"symbol":"AAPL"},
                         {"buy":25,"hold":6,"period":"2026-11-01","sell":0,"strongBuy":14,"strongSell":0,"symbol":"AAPL"}]""");
                case "/stock/insider-sentiment" -> Response.ok("""
                        {"data":[{"symbol":"AAPL","year":2026,"month":9,"change":-1200,"mspr":-12.5},{"symbol":"AAPL","year":2026,"month":10,"change":5000,"mspr":40.1}],"symbol":"AAPL"}""");
                case "/news" -> new Response(429, "{\"error\":\"API limit reached. Please try again later.\"}");
                default -> new Response(403, "{\"error\":\"You don't have access to this resource.\"}");
            };
        };
        fh = new FinnhubConnector(Clock.fixed(Instant.parse("2026-11-14T03:00:00Z"), ZoneOffset.UTC), server.url());
    }

    @AfterEach
    void stop() {
        server.close();
    }

    ConnectorContext ctx(String key) {
        return new ConnectorContext(fh.spec(), k -> k.equals("api_key") ? Optional.ofNullable(key) : Optional.empty(),
                HttpClient.newHttpClient(), om, permits::incrementAndGet);
    }

    @Test
    void test_keyInHeader_badKeyIsAuth() {
        assertThat(fh.test(ctx(KEY))).contains("257.3");
        assertThat(server.last().query()).doesNotContainKey("token");
        assertThat(permits.get()).isEqualTo(1);
        assertThatThrownBy(() -> fh.test(ctx("bad")))
                .isInstanceOfSatisfying(ConnectorException.class, e -> assertThat(e.kind()).isEqualTo(Kind.AUTH))
                .hasMessageContaining("Invalid API key");
        assertThat(fh.tools()).extracting(ConnectorTool::name).allMatch(n -> n.startsWith("finnhub_"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void companyNews_defaultsToLastWeekInEastern_newestFirst() throws Exception {
        Map<String, Object> out = (Map<String, Object>) fh.companyNews(ctx(KEY), om.readTree("{\"symbol\":\"aapl\"}"));
        // 03:00Z on the 14th is still the 13th in New York
        assertThat(server.last().query()).containsEntry("symbol", "AAPL").containsEntry("from", "2026-11-06").containsEntry("to", "2026-11-13");
        List<Map<String, Object>> a = (List<Map<String, Object>>) out.get("articles");
        assertThat(a.get(0)).containsEntry("headline", "Newer").containsEntry("published_utc", "2025-11-14T14:20:00Z");
    }

    @Test
    @SuppressWarnings("unchecked")
    void earningsCalendar_sortedWithReadableHour() throws Exception {
        Map<String, Object> out = (Map<String, Object>) fh.earningsCalendar(ctx(KEY), om.readTree("{}"));
        assertThat(server.last().query()).containsEntry("from", "2026-11-13").containsEntry("to", "2026-11-27");
        List<Map<String, Object>> rows = (List<Map<String, Object>>) out.get("releases");
        assertThat(rows.get(0)).containsEntry("symbol", "WMT").containsEntry("hour", "before market open").containsEntry("eps_actual", null);
    }

    @Test
    @SuppressWarnings("unchecked")
    void trendsAndSentiment_newestFirst() throws Exception {
        Map<String, Object> rec = (Map<String, Object>) fh.recommendations(ctx(KEY), om.readTree("{\"symbol\":\"AAPL\",\"months\":1}"));
        assertThat((List<Map<String, Object>>) rec.get("trends")).singleElement()
                .satisfies(t -> assertThat(t).containsEntry("period", "2026-11-01").containsEntry("strongBuy", 14));
        Map<String, Object> ins = (Map<String, Object>) fh.insiderSentiment(ctx(KEY), om.readTree("{\"symbol\":\"AAPL\"}"));
        assertThat(server.last().query()).containsEntry("from", "2025-11-13");
        assertThat(((List<Map<String, Object>>) ins.get("months")).get(0)).containsEntry("month", "2026-10").containsEntry("mspr", 40.1);
    }

    @Test
    void upstream429_isRateLimited_andBadRangeIsRejectedLocally() {
        assertThatThrownBy(() -> fh.marketNews(ctx(KEY), om.createObjectNode()))
                .isInstanceOfSatisfying(ConnectorException.class, e -> assertThat(e.kind()).isEqualTo(Kind.RATE_LIMITED));
        int before = server.requests.size();
        assertThatThrownBy(() -> fh.companyNews(ctx(KEY), om.readTree("{\"symbol\":\"AAPL\",\"from\":\"2026-11-10\",\"to\":\"2026-11-01\"}")))
                .isInstanceOfSatisfying(ConnectorException.class, e -> assertThat(e.kind()).isEqualTo(Kind.BAD_REQUEST));
        assertThat(server.requests).hasSize(before);
    }
}
