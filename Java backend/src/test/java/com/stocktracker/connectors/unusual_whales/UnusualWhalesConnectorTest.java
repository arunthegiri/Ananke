package com.stocktracker.connectors.unusual_whales;

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
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Unusual Whales connector against a fake API serving the OpenAPI examples. */
class UnusualWhalesConnectorTest {

    static final String GOOD = "123e4567-e89b-12d3-a456-426614174000";
    static final String UNPAID = "00000000-0000-0000-0000-000000000402";

    final ObjectMapper om = new ObjectMapper();
    final List<String> paths = new CopyOnWriteArrayList<>();
    HttpServer server;
    UnusualWhalesConnector uw;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.start();
        uw = new UnusualWhalesConnector("http://127.0.0.1:" + server.getAddress().getPort());
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    ConnectorContext ctx(String token) {
        return new ConnectorContext(uw.spec(), k -> k.equals("api_token") ? Optional.ofNullable(token) : Optional.empty(),
                HttpClient.newHttpClient(), om, () -> { });
    }

    void handle(HttpExchange ex) throws IOException {
        String p = ex.getRequestURI().getPath();
        String q = ex.getRequestURI().getRawQuery();
        paths.add(p + (q == null ? "" : "?" + q));
        String auth = ex.getRequestHeaders().getFirst("Authorization");
        if (("Bearer " + UNPAID).equals(auth)) {
            send(ex, 402, "{\"code\":\"payment_required\",\"message\":\"unpaid\"}");
            return;
        }
        if (!("Bearer " + GOOD).equals(auth)) {
            send(ex, 401, "{\"code\":\"authentication_required\",\"reason\":\"malformed_token\",\"message\":\"The API token provided is not in the expected format.\"}");
            return;
        }
        switch (p) {
            case "/api/option-trades/flow-alerts" -> send(ex, 200, """
                    {"data":[{"alert_rule":"RepeatedHits","all_opening_trades":false,"created_at":"2023-12-12 16:35:52 UTC",
                      "expiry":"2023-12-22","expiry_count":1,"has_floor":false,"has_multileg":false,"has_singleleg":true,
                      "has_sweep":true,"issue_type":"Common Stock","open_interest":7913,"option_chain":"MSFT231222C00375000",
                      "price":"4.05","strike":"375","ticker":"MSFT","total_ask_side_prem":"151875","total_bid_side_prem":"405",
                      "total_premium":"186705","total_size":461,"trade_count":32,"type":"call","underlying_price":"372.99",
                      "volume":2442,"volume_oi_ratio":"0.30860609124226"}]}""");
            case "/api/darkpool/QID" -> send(ex, 200, """
                    {"data":[{"canceled":false,"executed_at":"2025-05-02 13:42:12 UTC","ext_hour_sold_codes":"extended_hours_trade",
                      "market_center":"L","nbbo_ask":"19","nbbo_ask_quantity":6600,"nbbo_bid":"18.99","nbbo_bid_quantity":29100,
                      "premium":"121538.56","price":"18.9904","sale_cond_codes":null,"size":6400,"ticker":"QID",
                      "tracking_id":71984388012245,"trade_code":null,"trade_settlement":"regular_settlement",
                      "trf_executed_at":"2025-05-02 13:42:11 UTC","volume":9946819}]}""");
            case "/api/market/market-tide" -> send(ex, 200, "{\"data\":[" + IntStream.range(0, 78).mapToObj(i ->
                    "{\"date\":\"2023-09-08\",\"net_call_premium\":\"" + (1000 * i) + ".0000\",\"net_put_premium\":\"-" + (500 * i)
                            + ".0000\",\"net_volume\":" + i + ",\"timestamp\":\"2023-09-08 " + String.format("%02d:%02d", 9 + (30 + 5 * i) / 60, (30 + 5 * i) % 60)
                            + ":00 -0400\"}").collect(Collectors.joining(",")) + "]}");
            case "/api/congress/recent-trades" -> send(ex, 200, """
                    {"data":[{"amounts":"$1,000 - $15,000","filed_at_date":"2023-02-13","is_active":false,"issuer":"joint",
                      "member_type":"house","name":"Deborah Ross","notes":"Subholding Of: Fidelity Brokerage Account stock 8000",
                      "politician_id":"5b1e3f4a","reporter":"Debby Ross","ticker":"MSFT","transaction_date":"2023-02-01","txn_type":"Sell"}],
                     "page":0,"total_pages":3}""");
            case "/api/stock/SPY/greek-exposure" -> send(ex, 200, """
                    {"data":[{"call_charm":"81465130.0002","call_delta":"210202465.3421","call_gamma":"8456599.8505","call_vanna":"161231587973.6811",
                       "date":"2023-09-07","put_charm":"-1054548432.6111","put_delta":"-210881557.3003","put_gamma":"-12703877.0243","put_vanna":"488921784213.1121"},
                      {"call_charm":"102382359.5786","call_delta":"227549667.4651","call_gamma":"9356683.4241","call_vanna":"152099632406.9564",
                       "date":"2023-09-08","put_charm":"-943028472.4815","put_delta":"-191893077.7193","put_gamma":"-12337386.0524","put_vanna":"488921784213.1121"}]}""");
            default -> send(ex, 404, "{\"error\":\"Route not found\"}");
        }
    }

    void send(HttpExchange ex, int status, String body) throws IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(status, b.length);
        ex.getResponseBody().write(b);
        ex.close();
    }

    static ConnectorException.Kind kind(Throwable e) {
        return ((ConnectorException) e).kind();
    }

    @Test
    void test_badAndUnpaidTokensAreAuthErrors() throws Exception {
        assertThat(uw.test(ctx(GOOD))).contains("78 points").contains("2023-09-08");
        assertThatThrownBy(() -> uw.test(ctx("nope"))).satisfies(e -> assertThat(kind(e)).isEqualTo(ConnectorException.Kind.AUTH));
        assertThatThrownBy(() -> uw.test(ctx(UNPAID)))
                .hasMessageContaining("unpaid").satisfies(e -> assertThat(kind(e)).isEqualTo(ConnectorException.Kind.AUTH));
    }

    @Test
    @SuppressWarnings("unchecked")
    void flowAlerts_parseStringNumbers_normaliseTime_andPassFilters() throws Exception {
        Map<String, Object> out = (Map<String, Object>) uw.flowAlerts(ctx(GOOD),
                om.readTree("{\"tickers\":[\"msft\"],\"side\":\"calls\",\"min_premium\":100000,\"sweeps_only\":true,\"limit\":999}"));
        Map<String, Object> a = ((List<Map<String, Object>>) out.get("alerts")).get(0);
        assertThat(a).containsEntry("created_at", "2023-12-12T16:35:52Z").containsEntry("total_premium", 186705.0)
                .containsEntry("strike", 375.0).containsEntry("ask_side_premium", 151875.0).containsEntry("volume_oi_ratio", 0.309)
                .containsEntry("has_sweep", true);
        assertThat(paths.get(0)).contains("ticker_symbol=MSFT").contains("is_call=true").contains("min_premium=100000")
                .contains("is_sweep=true").contains("limit=100").doesNotContain("is_put");
    }

    @Test
    void flowAlerts_rejectBadArgs() {
        assertThatThrownBy(() -> uw.flowAlerts(ctx(GOOD), om.readTree("{\"side\":\"sideways\"}")))
                .satisfies(e -> assertThat(kind(e)).isEqualTo(ConnectorException.Kind.BAD_REQUEST));
        assertThatThrownBy(() -> uw.flowAlerts(ctx(GOOD), om.readTree("{\"tickers\":\"A,B,C,D,E,F,G,H,I,J,K\"}")))
                .satisfies(e -> assertThat(kind(e)).isEqualTo(ConnectorException.Kind.BAD_REQUEST));
        assertThat(paths).isEmpty();
    }

    @Test
    @SuppressWarnings("unchecked")
    void darkpool_mapsPrints() throws Exception {
        Map<String, Object> out = (Map<String, Object>) uw.darkpool(ctx(GOOD), om.readTree("{\"ticker\":\"QID\",\"date\":\"2025-05-02\"}"));
        Map<String, Object> p = ((List<Map<String, Object>>) out.get("prints")).get(0);
        assertThat(p).containsEntry("executed_at", "2025-05-02T13:42:12Z").containsEntry("price", 18.9904)
                .containsEntry("premium", 121538.56).containsEntry("trf_reported_at", "2025-05-02T13:42:11Z");
        assertThatThrownBy(() -> uw.darkpool(ctx(GOOD), om.readTree("{\"ticker\":\"QID\",\"date\":\"May 2\"}")))
                .satisfies(e -> assertThat(kind(e)).isEqualTo(ConnectorException.Kind.BAD_REQUEST));
    }

    @Test
    @SuppressWarnings("unchecked")
    void marketTide_isDownsampled_keepingTheLatestPoint() throws Exception {
        Map<String, Object> out = (Map<String, Object>) uw.marketTide(ctx(GOOD), om.readTree("{\"points\":10}"));
        List<Map<String, Object>> pts = (List<Map<String, Object>>) out.get("points");
        assertThat(pts).hasSize(10);
        assertThat(out).containsEntry("points_total", 78).containsEntry("date", "2023-09-08");
        assertThat(pts.get(0)).containsEntry("timestamp", "2023-09-08T13:30:00Z");
        assertThat(pts.get(9)).isEqualTo(out.get("latest"));
        assertThat((Map<String, Object>) out.get("latest")).containsEntry("net_call_premium", 77000.0);
    }

    @Test
    @SuppressWarnings("unchecked")
    void congressTrades_carryFiledDate_andLag() throws Exception {
        Map<String, Object> out = (Map<String, Object>) uw.congressTrades(ctx(GOOD), om.readTree("{\"ticker\":\"MSFT\"}"));
        Map<String, Object> t = ((List<Map<String, Object>>) out.get("trades")).get(0);
        assertThat(t).containsEntry("transaction_date", "2023-02-01").containsEntry("filed_at", "2023-02-13")
                .containsEntry("disclosure_lag_days", 12L);
        assertThat(out).containsEntry("total_pages", 3);
    }

    @Test
    @SuppressWarnings("unchecked")
    void greekExposure_newestFirst_withNetGamma() throws Exception {
        Map<String, Object> out = (Map<String, Object>) uw.greekExposure(ctx(GOOD), om.readTree("{\"ticker\":\"spy\",\"limit\":1}"));
        List<Map<String, Object>> days = (List<Map<String, Object>>) out.get("days");
        assertThat(days).hasSize(1);
        assertThat(days.get(0)).containsEntry("date", "2023-09-08").containsEntry("net_gamma", -2980702.63);
    }

    @Test
    void timestampsNormalise() {
        assertThat(UnusualWhalesConnector.iso("2023-09-08 09:30:00 -0400")).isEqualTo("2023-09-08T13:30:00Z");
        assertThat(UnusualWhalesConnector.iso("garbage")).isEqualTo("garbage");
    }
}
