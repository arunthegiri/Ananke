package com.stocktracker.connectors.orats;

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
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** ORATS connector against a fake Data API serving the OpenAPI examples. */
class OratsConnectorTest {

    final ObjectMapper om = new ObjectMapper();
    final List<String> paths = new CopyOnWriteArrayList<>();
    HttpServer server;
    OratsConnector orats;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.start();
        orats = new OratsConnector("http://127.0.0.1:" + server.getAddress().getPort());
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    ConnectorContext ctx(String token) {
        return new ConnectorContext(orats.spec(), k -> k.equals("token") ? Optional.ofNullable(token) : Optional.empty(),
                HttpClient.newHttpClient(), om, () -> { });
    }

    void handle(HttpExchange ex) throws IOException {
        String p = ex.getRequestURI().getPath();
        String q = ex.getRequestURI().getQuery();
        paths.add(p + (q == null ? "" : "?" + q));
        if (q != null && q.contains("token=soft")) {
            send(ex, 200, "{\"message\":\"Invalid token\"}");
            return;
        }
        if (q == null || !q.contains("token=good")) {
            send(ex, 403, "{\"Message\":\"User is not authorized to access this resource with an explicit deny in an identity-based policy\"}");
            return;
        }
        switch (p) {
            case "/datav2/summaries" -> send(ex, 200, """
                    {"data":[{"ticker":"AAPL","tradeDate":"2023-11-03","stockPrice":176.78,"iv10d":0.195644,"iv20d":0.188896,
                      "iv30d":0.185834,"iv60d":0.192219,"iv90d":0.205658,"iv6m":0.221108,"iv1y":0.233177,"exErnIv30d":0.185834,
                      "rVol30":0.183112,"impliedMove":0.028577,"ieeEarnEffect":1.85201,"skewing":-0.18824,"contango":-0.0186,
                      "confidence":0.710872,"updatedAt":"2023-11-03T20:55:02Z"}]}""");
            case "/datav2/ivrank" -> send(ex, 200, """
                    {"data":[{"ticker":"AAPL","tradeDate":"2023-11-03","iv":18.311,"ivRank1m":0,"ivPct1m":0,"ivRank1y":17.49,
                      "ivPct1y":12.75,"updatedAt":"2023-11-03T20:55:02Z"},{"ticker":"SPY","tradeDate":"2023-11-03","ivRank1y":10}]}""");
            case "/datav2/monies/implied" -> send(ex, 200, """
                    {"data":[{"ticker":"AAPL","tradeDate":"2023-11-03","expirDate":"2024-06-21","stockPrice":176.78,"atmiv":0.228074,
                       "calVol":0.224091,"unadjVol":0.219517,"earnEffect":0.004575,"slope":4.50145,"vol25":0.206676,"vol50":0.228685,
                       "vol75":0.258977,"confidence":0.99,"updatedAt":"2023-11-03T19:59:57Z"},
                      {"ticker":"AAPL","tradeDate":"2023-11-03","expirDate":"2023-11-17","atmiv":0.19465,"calVol":0.19,"updatedAt":"2023-11-03T19:59:57Z"}]}""");
            case "/datav2/cores" -> send(ex, 200, """
                    {"data":[{"ticker":"AAPL","tradeDate":"2023-11-03","lastErn":"2023-11-02","nextErn":"2024-02-01","absAvgErnMv":3.4,
                      "impErnMv":2.9,"ernMv1":-0.5,"ernMv2":4.7,"ernMv3":2.4,"ernMv4":-4.8,"ernMvStdv":1.9,"impliedIee":1.85,
                      "ivEarnReturn":0.1,"updatedAt":"2023-11-03T20:55:02Z"}]}""");
            case "/datav2/hist/earnings" -> send(ex, 200, """
                    {"data":[{"ticker":"AAPL","earnDate":"1987-09-25","anncTod":"1630","updatedAt":"2018-01-12T17:31:59Z"},
                      {"ticker":"AAPL","earnDate":"2023-11-02","anncTod":"1630","updatedAt":"2023-11-03T00:00:00Z"},
                      {"ticker":"AAPL","earnDate":"2023-08-03","anncTod":"930","updatedAt":"2023-08-04T00:00:00Z"}]}""");
            case "/datav2/hist/ivrank" -> {
                LocalDate start = LocalDate.parse("2023-01-01");
                send(ex, 200, "{\"data\":[" + IntStream.range(0, 300).mapToObj(i -> "{\"ticker\":\"AAPL\",\"tradeDate\":\""
                        + start.plusDays(i) + "\",\"iv\":" + (20 + i % 7) + ",\"ivRank1y\":" + (i % 100) + "}").collect(Collectors.joining(",")) + "]}");
            }
            default -> send(ex, 404, "{\"message\":\"Not Found\"}");
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
    void test_tokenInQuery_badTokenIsAuth_evenAsA200Message() throws Exception {
        assertThat(orats.test(ctx("good"))).contains("as of 2023-11-03");
        assertThat(paths.get(0)).contains("/datav2/ivrank").contains("token=good").contains("ticker=SPY");
        assertThatThrownBy(() -> orats.test(ctx("bad"))).satisfies(e -> assertThat(kind(e)).isEqualTo(ConnectorException.Kind.AUTH));
        assertThatThrownBy(() -> orats.test(ctx("soft"))).satisfies(e -> assertThat(kind(e)).isEqualTo(ConnectorException.Kind.AUTH));
    }

    @Test
    @SuppressWarnings("unchecked")
    void ivSummary_mergesRank_requestsOnlyNeededFields_andReportsMissing() throws Exception {
        Map<String, Object> out = (Map<String, Object>) orats.ivSummary(ctx("good"), om.readTree("{\"tickers\":\"aapl,zzzz\"}"));
        Map<String, Object> aapl = ((List<Map<String, Object>>) out.get("tickers")).get(0);
        assertThat(aapl).containsEntry("iv30d", 0.185834).containsEntry("implied_earnings_move", 0.028577)
                .containsEntry("iv_rank_1y", 17.49).containsEntry("updated_at", "2023-11-03T20:55:02Z");
        assertThat((List<String>) out.get("not_found")).containsExactly("ZZZZ");
        assertThat(paths.get(0)).contains("ticker=AAPL,ZZZZ").contains("fields=ticker,tradeDate");
        assertThatThrownBy(() -> orats.ivSummary(ctx("good"), om.readTree("{\"tickers\":[\"A\",\"B\",\"C\",\"D\",\"E\",\"F\",\"G\",\"H\",\"I\",\"J\",\"K\"]}")))
                .satisfies(e -> assertThat(kind(e)).isEqualTo(ConnectorException.Kind.BAD_REQUEST));
        assertThatThrownBy(() -> orats.ivSummary(ctx("good"), om.readTree("{}")))
                .satisfies(e -> assertThat(kind(e)).isEqualTo(ConnectorException.Kind.BAD_REQUEST));
    }

    @Test
    @SuppressWarnings("unchecked")
    void termStructure_sortedByExpiry_withDte() throws Exception {
        Map<String, Object> out = (Map<String, Object>) orats.termStructure(ctx("good"), om.readTree("{\"ticker\":\"AAPL\"}"));
        List<Map<String, Object>> rows = (List<Map<String, Object>>) out.get("expiries");
        assertThat(rows).extracting(r -> r.get("expiry")).containsExactly("2023-11-17", "2024-06-21");
        assertThat(rows.get(0)).containsEntry("dte", 14L);
        assertThat(rows.get(1)).containsEntry("smoothed_iv_ex_earnings", 0.219517).containsEntry("iv_25_delta", 0.206676);
    }

    @Test
    @SuppressWarnings("unchecked")
    void earnings_impliedVsHistorical_andNewestPastDates() throws Exception {
        Map<String, Object> out = (Map<String, Object>) orats.earnings(ctx("good"), om.readTree("{\"ticker\":\"AAPL\",\"history\":2}"));
        assertThat(out).containsEntry("implied_move_pct", 2.9).containsEntry("avg_abs_move_pct", 3.4).containsEntry("next_earnings", "2024-02-01");
        assertThat((List<Double>) out.get("last_four_moves_pct")).containsExactly(-0.5, 4.7, 2.4, -4.8);
        List<Map<String, Object>> past = (List<Map<String, Object>>) out.get("past_earnings");
        assertThat(past).extracting(r -> r.get("date")).containsExactly("2023-11-02", "2023-08-03");
        assertThat(past.get(1)).containsEntry("announce_time", "09:30");
    }

    @Test
    @SuppressWarnings("unchecked")
    void ivRankHistory_windowsAndLimits() throws Exception {
        Map<String, Object> out = (Map<String, Object>) orats.ivRankHistory(ctx("good"), om.readTree("{\"ticker\":\"AAPL\",\"limit\":10}"));
        List<Map<String, Object>> days = (List<Map<String, Object>>) out.get("days");
        assertThat(days).hasSize(10);
        assertThat(days.get(9)).containsEntry("date", "2023-10-27");
        Map<String, Object> ranged = (Map<String, Object>) orats.ivRankHistory(ctx("good"),
                om.readTree("{\"ticker\":\"AAPL\",\"from\":\"2023-02-01\",\"to\":\"2023-02-10\"}"));
        assertThat((List<?>) ranged.get("days")).hasSize(10);
        assertThatThrownBy(() -> orats.ivRankHistory(ctx("good"), om.readTree("{\"ticker\":\"AAPL\",\"from\":\"2023-03-01\",\"to\":\"2023-02-01\"}")))
                .satisfies(e -> assertThat(kind(e)).isEqualTo(ConnectorException.Kind.BAD_REQUEST));
    }
}
