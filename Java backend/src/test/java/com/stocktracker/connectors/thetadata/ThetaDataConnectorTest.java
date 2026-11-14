package com.stocktracker.connectors.thetadata;

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
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** ThetaData connector against a fake Theta Terminal v3 serving the OpenAPI examples. */
class ThetaDataConnectorTest {

    final ObjectMapper om = new ObjectMapper();
    final List<String> paths = new CopyOnWriteArrayList<>();
    final AtomicInteger permits = new AtomicInteger();
    HttpServer server;
    ThetaDataConnector theta;
    String base;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();
        // 2026-11-14T02:00Z = 2026-11-13 21:00 in New York.
        theta = new ThetaDataConnector(Clock.fixed(Instant.parse("2026-11-14T02:00:00Z"), ZoneOffset.UTC), base);
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    /** {@code url} = the user's base_url field (null = default). */
    ConnectorContext ctx(String url) {
        return new ConnectorContext(theta.spec(), k -> k.equals("base_url") ? Optional.ofNullable(url) : Optional.empty(),
                HttpClient.newHttpClient(), om, permits::incrementAndGet);
    }

    void handle(HttpExchange ex) throws IOException {
        String p = ex.getRequestURI().getPath();
        String q = ex.getRequestURI().getQuery();
        paths.add(p + (q == null ? "" : "?" + q));
        switch (p) {
            case "/v3/calendar/today" -> send(ex, 200, "{\n  \"response\": [\n    {\"type\":\"open\",\"open\":\"09:30:00\",\"close\":\"16:00:00\"}\n  ]\n}\n");
            case "/v3/stock/history/eod" -> send(ex, 200, """
                    {"response":[
                      {"ask_size":2,"last_trade":"2024-01-03T17:16:28.586","created":"2024-01-03T17:16:29.883","count":654127,"volume":58308345,
                       "high":185.880,"low":183.430,"bid_size":5,"ask":184.26,"bid":184.25,"close":184.250,"open":184.200},
                      {"ask_size":2,"last_trade":"2024-01-02T17:17:51.877","created":"2024-01-02T17:17:53.606","count":1003582,"volume":80680243,
                       "high":188.440,"low":183.885,"bid_size":2,"ask":185.65,"bid":185.64,"close":185.640,"open":187.030}]}""");
            case "/v3/option/list/expirations" -> send(ex, 200, """
                    {"response":[{"symbol":"AAPL","expiration":"2026-11-06"},{"symbol":"AAPL","expiration":"2026-11-13"},
                      {"symbol":"AAPL","expiration":"2026-11-20"},{"symbol":"AAPL","expiration":"2026-12-18"},{"symbol":"AAPL","expiration":"2012-06-01"}]}""");
            case "/v3/option/history/eod" -> send(ex, 200, "{\"response\":[" + IntStream.range(0, 30).mapToObj(i ->
                    "{\"contract\":{\"symbol\":\"AAPL\",\"strike\":" + (160 + i) + ".000,\"right\":\"" + (i % 2 == 0 ? "CALL" : "PUT")
                            + "\",\"expiration\":\"2024-11-15\"},\"data\":[{\"last_trade\":\"2024-11-04T15:48:12.005\",\"created\":\"2024-11-04T17:16:56.205\","
                            + "\"count\":3,\"volume\":10,\"high\":52.75,\"low\":52.40,\"ask\":52.45,\"bid\":52.05,\"close\":52.40,\"open\":52.54}]}")
                    .collect(Collectors.joining(",")) + "]}");
            case "/v3/option/history/greeks/eod" -> send(ex, 471, "Your account does not have the permissions required to execute the request.");
            case "/v3/option/history/open_interest" -> {
                if (q.contains("symbol=NODATA")) {
                    send(ex, 472, "No data found for your request");
                    return;
                }
                send(ex, 200, """
                        {"response":[{"contract":{"symbol":"AAPL","strike":220.000,"right":"CALL","expiration":"2024-11-08"},
                          "data":[{"open_interest":2732,"timestamp":"2024-11-04T06:30:04"}]}]}""");
            }
            default -> send(ex, 404, "NO_IMPL");
        }
    }

    void send(HttpExchange ex, int status, String body) throws IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", body.startsWith("{") ? "application/json" : "text/plain");
        ex.sendResponseHeaders(status, b.length);
        ex.getResponseBody().write(b);
        ex.close();
    }

    static ConnectorException.Kind kind(Throwable e) {
        return ((ConnectorException) e).kind();
    }

    @Test
    void spec_isKeyless_andSaysItCallsLocalhost() {
        assertThat(theta.spec().keyless()).isTrue();
        assertThat(theta.spec().description()).contains("localhost").contains("25503");
        assertThat(ThetaDataConnector.DEFAULT_URL).isEqualTo("http://127.0.0.1:25503");
    }

    @Test
    void test_usesTheConfiguredUrl_andExplainsAnUnreachableTerminal() throws Exception {
        assertThat(theta.test(ctx(base + "/v3/"))).contains("open 09:30:00–16:00:00 ET");
        assertThat(paths.get(0)).isEqualTo("/v3/calendar/today");
        assertThat(permits).hasValue(1);
        assertThatThrownBy(() -> theta.test(ctx("http://127.0.0.1:1")))
                .hasMessageContaining("start the Terminal").satisfies(e -> assertThat(kind(e)).isEqualTo(ConnectorException.Kind.UPSTREAM));
        assertThatThrownBy(() -> theta.test(ctx("file:///etc/passwd")))
                .satisfies(e -> assertThat(kind(e)).isEqualTo(ConnectorException.Kind.BAD_REQUEST));
    }

    @Test
    @SuppressWarnings("unchecked")
    void stockEod_usesYyyymmdd_andEasternToday_oldestFirst() throws Exception {
        Map<String, Object> out = (Map<String, Object>) theta.stockEod(ctx(null), om.readTree("{\"symbol\":\"aapl\"}"));
        assertThat(paths.get(0)).contains("symbol=AAPL").contains("start_date=20261014").contains("end_date=20261113").contains("format=json");
        List<Map<String, Object>> bars = (List<Map<String, Object>>) out.get("bars");
        assertThat(bars).extracting(b -> b.get("date")).containsExactly("2024-01-02", "2024-01-03");
        assertThat(bars.get(0)).containsEntry("close", 185.64).containsEntry("volume", 80680243.0).containsEntry("report_time_et", "2024-01-02T17:17:53.606");
        assertThatThrownBy(() -> theta.stockEod(ctx(null), om.readTree("{\"symbol\":\"AAPL\",\"from\":\"2020-01-01\",\"to\":\"2024-01-01\"}")))
                .satisfies(e -> assertThat(kind(e)).isEqualTo(ConnectorException.Kind.BAD_REQUEST));
    }

    @Test
    @SuppressWarnings("unchecked")
    void expirations_upcomingFirst_relativeToEasternDate() throws Exception {
        Map<String, Object> out = (Map<String, Object>) theta.expirations(ctx(null), om.readTree("{\"symbol\":\"AAPL\"}"));
        assertThat((List<String>) out.get("expirations")).containsExactly("2026-11-13", "2026-11-20", "2026-12-18");
        Map<String, Object> all = (Map<String, Object>) theta.expirations(ctx(null), om.readTree("{\"symbol\":\"AAPL\",\"include_past\":true,\"limit\":4}"));
        assertThat((List<String>) all.get("expirations")).containsExactly("2026-11-13", "2026-11-20", "2026-12-18", "2026-11-06");
    }

    @Test
    @SuppressWarnings("unchecked")
    void optionEod_flattensContracts_boundsRows_andDefaultsToAStrikeRange() throws Exception {
        Map<String, Object> out = (Map<String, Object>) theta.optionHistory(ctx(null),
                om.readTree("{\"symbol\":\"AAPL\",\"expiration\":\"2024-11-15\",\"from\":\"2024-11-04\",\"to\":\"2024-11-04\",\"limit\":5}"),
                "/v3/option/history/eod", false);
        assertThat(paths.get(0)).contains("expiration=20241115").contains("strike_range=10").contains("right=both").doesNotContain("strike=");
        List<Map<String, Object>> rows = (List<Map<String, Object>>) out.get("rows");
        assertThat(rows).hasSize(5);
        assertThat(rows.get(0)).containsEntry("strike", 160.0).containsEntry("right", "call").containsEntry("date", "2024-11-04").containsEntry("close", 52.40);
        assertThat(out).containsEntry("total_rows", 30).containsKey("truncated");

        theta.optionHistory(ctx(null), om.readTree("{\"symbol\":\"AAPL\",\"expiration\":\"2024-11-15\",\"strike\":170,\"right\":\"put\",\"to\":\"2024-11-04\"}"),
                "/v3/option/history/eod", false);
        assertThat(paths.get(1)).contains("strike=170.000").contains("right=put").doesNotContain("strike_range");

        assertThatThrownBy(() -> theta.optionHistory(ctx(null), om.readTree("{\"symbol\":\"AAPL\"}"), "/v3/option/history/eod", false))
                .satisfies(e -> assertThat(kind(e)).isEqualTo(ConnectorException.Kind.BAD_REQUEST));
        assertThatThrownBy(() -> theta.optionHistory(ctx(null), om.readTree("{\"symbol\":\"AAPL\",\"expiration\":\"2024-11-15\",\"right\":\"straddle\"}"),
                "/v3/option/history/eod", false)).satisfies(e -> assertThat(kind(e)).isEqualTo(ConnectorException.Kind.BAD_REQUEST));
    }

    @Test
    void greeks_withoutTheSubscription_isAuth() {
        assertThatThrownBy(() -> theta.optionHistory(ctx(null), om.readTree("{\"symbol\":\"AAPL\",\"expiration\":\"2024-11-08\"}"),
                "/v3/option/history/greeks/eod", true))
                .hasMessageContaining("471").satisfies(e -> assertThat(kind(e)).isEqualTo(ConnectorException.Kind.AUTH));
    }

    @Test
    @SuppressWarnings("unchecked")
    void openInterest_parses_andNoDataIsAnEmptyResult() throws Exception {
        Map<String, Object> out = (Map<String, Object>) theta.openInterest(ctx(null), om.readTree("{\"symbol\":\"AAPL\",\"expiration\":\"2024-11-08\",\"date\":\"2024-11-04\"}"));
        assertThat(paths.get(0)).contains("date=20241104");
        assertThat(((List<Map<String, Object>>) out.get("contracts")).get(0)).containsEntry("open_interest", 2732.0)
                .containsEntry("reported_at_et", "2024-11-04T06:30:04").containsEntry("right", "call");
        Map<String, Object> none = (Map<String, Object>) theta.openInterest(ctx(null), om.readTree("{\"symbol\":\"NODATA\",\"expiration\":\"2024-11-08\"}"));
        assertThat(none).containsEntry("count", 0).containsEntry("date", "2026-11-13");
    }
}
