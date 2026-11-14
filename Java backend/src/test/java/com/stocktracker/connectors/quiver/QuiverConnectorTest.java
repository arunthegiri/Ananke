package com.stocktracker.connectors.quiver;

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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Quiver connector against a fake Quiver API shaped like its OpenAPI examples. */
class QuiverConnectorTest {

    final ObjectMapper om = new ObjectMapper();
    final List<String> paths = new CopyOnWriteArrayList<>();
    final List<String> auths = new CopyOnWriteArrayList<>();
    HttpServer server;
    QuiverConnector quiver;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.start();
        quiver = new QuiverConnector("http://127.0.0.1:" + server.getAddress().getPort());
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    ConnectorContext ctx(String key) {
        return new ConnectorContext(quiver.spec(), k -> k.equals("api_key") ? Optional.ofNullable(key) : Optional.empty(),
                HttpClient.newHttpClient(), om, () -> { });
    }

    void handle(HttpExchange ex) throws IOException {
        String p = ex.getRequestURI().getPath();
        String q = ex.getRequestURI().getRawQuery();
        paths.add(p + (q == null ? "" : "?" + q));
        String auth = ex.getRequestHeaders().getFirst("Authorization");
        auths.add(auth);
        if (!"Bearer good".equals(auth)) {
            send(ex, 401, "{\"detail\":\"Invalid token.\"}");
            return;
        }
        switch (p) {
            case "/beta/live/congresstrading" -> send(ex, 200, """
                    [{"Representative":"Nancy Pelosi","BioGuideID":"P000197","ReportDate":"2026-10-30T00:00:00Z",
                      "TransactionDate":"2026-09-20T00:00:00Z","Ticker":"NVDA","Transaction":"Purchase","Range":"$1,000,001 - $5,000,000",
                      "House":"Representatives","Amount":"1000001.0","Party":"D","District":"CA11","TickerType":"ST","Description":null,
                      "ExcessReturn":3.2,"last_modified":"2026-10-31"},
                     {"Representative":"Tommy Tuberville","BioGuideID":"T000278","ReportDate":"2026-11-02T00:00:00Z",
                      "TransactionDate":"2026-10-28T00:00:00Z","Ticker":"MSFT","Transaction":"Sale","Range":"$15,001 - $50,000",
                      "House":"Senate","Amount":"15001.0","Party":"R"}]""");
            case "/beta/historical/congresstrading/NVDA" -> send(ex, 200, """
                    [{"Representative":"Nancy Pelosi","ReportDate":"2026-10-30","TransactionDate":"2026-09-20","Ticker":"NVDA",
                      "Transaction":"Purchase","Range":"$1,000,001 - $5,000,000","House":"Representatives","Amount":"1000001.0"},
                     {"Representative":"Old Trade","ReportDate":"2025-01-10","TransactionDate":"2024-12-01","Ticker":"NVDA",
                      "Transaction":"Sale","Range":"$1,001 - $15,000","House":"Senate","Amount":"1001.0"}]""");
            case "/beta/historical/lobbying/AAPL" -> send(ex, 200, """
                    [{"Date":"2026-07-20T00:00:00Z","Amount":"2500000.0","Client":"Apple Inc.","Issue":"Taxation",
                      "Specific_Issue":"%s","Registrant":"APPLE INC.","Ticker":"AAPL"}]""".formatted("tariff ".repeat(100)));
            case "/beta/historical/govcontractsall/LMT" -> send(ex, 200, """
                    [{"Ticker":"LMT","Date":"2026-01-02","Description":"F-35 sustainment","Agency":"Department of Defense","Amount":1.2E9,"action_date":"2025-12-30"},
                     {"Ticker":"LMT","Date":"2026-03-02","Description":"Missiles","Agency":"Department of the Army","Amount":5.0E8,"action_date":"2026-02-27"}]""");
            case "/beta/live/insiders" -> send(ex, 403, "{\"detail\":\"Upgrade your subscription plan to access this dataset.\"}");
            default -> send(ex, 404, "<html>Not Found</html>");
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
    void test_sendsBearerToken_andRejectsABadOne() throws Exception {
        assertThat(quiver.test(ctx("good"))).contains("2 recent congressional trades");
        assertThat(auths).contains("Bearer good");
        assertThatThrownBy(() -> quiver.test(ctx("bad")))
                .isInstanceOf(ConnectorException.class)
                .satisfies(e -> assertThat(((ConnectorException) e).kind()).isEqualTo(ConnectorException.Kind.AUTH));
        assertThatThrownBy(() -> quiver.test(ctx(null)))
                .satisfies(e -> assertThat(((ConnectorException) e).kind()).isEqualTo(ConnectorException.Kind.NOT_LINKED));
    }

    @Test
    @SuppressWarnings("unchecked")
    void congressTrades_carryBothDates_andTheLag_newestDisclosureFirst() throws Exception {
        Map<String, Object> out = (Map<String, Object>) quiver.congressTrades(ctx("good"), om.readTree("{}"));
        List<Map<String, Object>> rows = (List<Map<String, Object>>) out.get("trades");
        assertThat(rows).extracting(r -> r.get("ticker")).containsExactly("MSFT", "NVDA");
        assertThat(rows.get(1)).containsEntry("transaction_date", "2026-09-20").containsEntry("disclosed", "2026-10-30")
                .containsEntry("disclosure_lag_days", 40L).containsEntry("amount_min_usd", 1000001.0)
                .containsEntry("amount_range", "$1,000,001 - $5,000,000");
    }

    @Test
    @SuppressWarnings("unchecked")
    void congressTrades_byTicker_filtersByDisclosureDate_andLimits() throws Exception {
        Map<String, Object> out = (Map<String, Object>) quiver.congressTrades(ctx("good"),
                om.readTree("{\"ticker\":\"nvda\",\"disclosed_since\":\"2026-01-01\",\"limit\":500}"));
        assertThat(paths).anyMatch(p -> p.startsWith("/beta/historical/congresstrading/NVDA"));
        assertThat((List<?>) out.get("trades")).hasSize(1);

        assertThatThrownBy(() -> quiver.congressTrades(ctx("good"), om.readTree("{\"disclosed_since\":\"last week\"}")))
                .satisfies(e -> assertThat(((ConnectorException) e).kind()).isEqualTo(ConnectorException.Kind.BAD_REQUEST));
        assertThatThrownBy(() -> quiver.congressTrades(ctx("good"), om.readTree("{\"ticker\":\"NOT A TICKER!\"}")))
                .satisfies(e -> assertThat(((ConnectorException) e).kind()).isEqualTo(ConnectorException.Kind.BAD_REQUEST));
    }

    @Test
    @SuppressWarnings("unchecked")
    void lobbying_trimsLongIssueText_andPassesPaging() throws Exception {
        Map<String, Object> out = (Map<String, Object>) quiver.lobbying(ctx("good"), om.readTree("{\"ticker\":\"AAPL\",\"limit\":5}"));
        Map<String, Object> row = ((List<Map<String, Object>>) out.get("filings")).get(0);
        assertThat(row).containsEntry("date", "2026-07-20").containsEntry("amount_usd", 2500000.0);
        assertThat((String) row.get("specific_issue")).hasSizeLessThanOrEqualTo(301).endsWith("…");
        assertThat(paths).anyMatch(p -> p.contains("page_size=5"));
        assertThatThrownBy(() -> quiver.lobbying(ctx("good"), om.readTree("{}")))
                .satisfies(e -> assertThat(((ConnectorException) e).kind()).isEqualTo(ConnectorException.Kind.BAD_REQUEST));
    }

    @Test
    @SuppressWarnings("unchecked")
    void govContracts_newestActionFirst() throws Exception {
        Map<String, Object> out = (Map<String, Object>) quiver.govContracts(ctx("good"), om.readTree("{\"ticker\":\"LMT\"}"));
        List<Map<String, Object>> rows = (List<Map<String, Object>>) out.get("contracts");
        assertThat(rows).extracting(r -> r.get("action_date")).containsExactly("2026-02-27", "2025-12-30");
        assertThat(rows.get(1)).containsEntry("amount_usd", 1.2E9).containsEntry("agency", "Department of Defense");
    }

    @Test
    void insiderTrades_withoutTheTraderPlan_isAnAuthError() {
        assertThatThrownBy(() -> quiver.insiderTrades(ctx("good"), om.readTree("{\"ticker\":\"AAPL\"}")))
                .isInstanceOf(ConnectorException.class)
                .hasMessageContaining("Upgrade your subscription")
                .satisfies(e -> assertThat(((ConnectorException) e).kind()).isEqualTo(ConnectorException.Kind.AUTH));
    }

    @Test
    void toolNamesArePrefixed() {
        assertThat(quiver.tools()).allSatisfy(t -> assertThat(t.name()).startsWith("quiver_"));
    }
}
