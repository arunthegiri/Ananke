package com.stocktracker.connectors.fmp;

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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** FMP connector against a fake /stable API serving FMP's published sample responses. */
class FmpConnectorTest {

    static final String TRANSCRIPT = "Operator: Good day, everyone. Welcome to the Apple Incorporated Third Quarter Fiscal Year 2020 Earnings Conference Call. ".repeat(300);

    final ObjectMapper om = new ObjectMapper();
    final List<String> paths = new CopyOnWriteArrayList<>();
    HttpServer server;
    FmpConnector fmp;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.start();
        // 2026-11-14 03:00Z is still 2026-11-13 in New York.
        fmp = new FmpConnector(Clock.fixed(Instant.parse("2026-11-14T03:00:00Z"), ZoneOffset.UTC),
                "http://127.0.0.1:" + server.getAddress().getPort() + "/stable");
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    ConnectorContext ctx(String key) {
        return new ConnectorContext(fmp.spec(), k -> k.equals("api_key") ? Optional.ofNullable(key) : Optional.empty(),
                HttpClient.newHttpClient(), om, () -> { });
    }

    void handle(HttpExchange ex) throws IOException {
        String p = ex.getRequestURI().getPath();
        String q = ex.getRequestURI().getQuery();
        paths.add(p + (q == null ? "" : "?" + q));
        if (q != null && q.contains("apikey=soft")) {
            send(ex, 200, "{\"Error Message\":\"Invalid API KEY. Feel free to create a Free API Key.\"}");
            return;
        }
        if (q != null && q.contains("apikey=free") && p.endsWith("/earning-call-transcript")) {
            send(ex, 402, "{\"Error Message\":\"Restricted Endpoint: This endpoint is not available under your current subscription\"}");
            return;
        }
        if (q == null || !q.contains("apikey=good") && !q.contains("apikey=free")) {
            send(ex, 401, "{\n  \"Error Message\": \"Invalid API KEY. Feel free to create a Free API Key.\"\n}");
            return;
        }
        switch (p) {
            case "/stable/quote" -> send(ex, 200, "[{\"symbol\":\"AAPL\",\"price\":338.19}]");
            case "/stable/earning-call-transcript-dates" -> send(ex, 200, """
                    [{"quarter":3,"fiscalYear":2026,"date":"2026-07-30"},{"quarter":4,"fiscalYear":2026,"date":"2026-10-30"},
                     {"quarter":2,"fiscalYear":2026,"date":"2026-04-30"}]""");
            case "/stable/earning-call-transcript" -> send(ex, 200, "[{\"symbol\":\"AAPL\",\"period\":\"Q4\",\"year\":2026,\"date\":\"2026-10-30\",\"content\":\""
                    + TRANSCRIPT + "\"}]");
            case "/stable/analyst-estimates" -> send(ex, 200, """
                    [{"symbol":"AAPL","date":"2030-09-27","revenueLow":648228509004,"revenueHigh":735022980353,"revenueAvg":679000000000,
                      "ebitdaAvg":245074834838,"netIncomeAvg":203538714818,"epsAvg":13.565,"epsHigh":15.01999,"epsLow":12.76582,
                      "numAnalystsRevenue":16,"numAnalystsEps":7},
                     {"symbol":"AAPL","date":"2027-09-27","revenueAvg":470000000000,"epsAvg":8.9},
                     {"symbol":"AAPL","date":"2026-09-26","revenueAvg":440000000000,"epsAvg":8.1}]""");
            case "/stable/price-target-consensus" -> send(ex, 200, "[{\"symbol\":\"AAPL\",\"targetHigh\":400,\"targetLow\":250,\"targetConsensus\":337.67,\"targetMedian\":340}]");
            case "/stable/price-target-summary" -> send(ex, 200, """
                    [{"symbol":"AAPL","lastMonthCount":8,"lastMonthAvgPriceTarget":333.75,"lastQuarterCount":20,"lastQuarterAvgPriceTarget":332.95,
                      "lastYearCount":67,"lastYearAvgPriceTarget":304.86,"allTimeCount":254,"allTimeAvgPriceTarget":230.51,"publishers":"[]"}]""");
            case "/stable/grades" -> send(ex, 200, """
                    [{"symbol":"AAPL","date":"2026-07-23","gradingCompany":"Morgan Stanley","previousGrade":"Overweight","newGrade":"Overweight","action":"maintain"},
                     {"symbol":"AAPL","date":"2026-10-31","gradingCompany":"Jefferies","previousGrade":"Hold","newGrade":"Buy","action":"upgrade"}]""");
            case "/stable/earnings-calendar" -> send(ex, 200, """
                    [{"symbol":"GRG.L","date":"2026-11-18","epsActual":null,"epsEstimated":0.501,"revenueActual":null,"revenueEstimated":1086300000,"lastUpdated":"2026-11-12"},
                     {"symbol":"NVDA","date":"2026-11-19","epsActual":null,"epsEstimated":1.25,"revenueActual":null,"revenueEstimated":54000000000,"lastUpdated":"2026-11-12"},
                     {"symbol":"AAPL","date":"2026-11-14","epsActual":1.9,"epsEstimated":1.8,"revenueActual":1,"revenueEstimated":1,"lastUpdated":"2026-11-14"}]""");
            case "/stable/economic-calendar" -> send(ex, 200, """
                    [{"date":"2026-11-14 03:30:00","country":"SG","event":"Import Prices YoY (Jun)","currency":"SGD","previous":18.5,"estimate":21,
                      "actual":13.6,"change":-4.9,"impact":"Low","changePercentage":-26.486,"unit":"%"},
                     {"date":"2026-11-14 13:30:00","country":"US","event":"CPI YoY","currency":"USD","previous":3.1,"estimate":3.0,
                      "actual":null,"change":null,"impact":"High","unit":"%"}]""");
            case "/stable/insider-trading/search" -> send(ex, 200, """
                    [{"symbol":"TRMK","filingDate":"2026-07-30","transactionDate":"2026-07-28","reportingCik":"0001661867","companyCik":"0000036146",
                      "transactionType":"A-Award","securitiesOwned":62959,"reportingName":"Tate Granville Jr","typeOfOwner":"officer: Secretary",
                      "acquisitionOrDisposition":"A","directOrIndirect":"D","formType":"4","securitiesTransacted":1608,"price":0,
                      "securityName":"Common Stock","url":"https://www.sec.gov/Archives/edgar/data/36146/000003614626000087/0000036146-26-000087-index.htm"}]""");
            default -> send(ex, 404, "[]");
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
    void test_badKeys_includingA200ErrorBody_areAuth() throws Exception {
        assertThat(fmp.test(ctx("good"))).contains("AAPL 338.19");
        assertThat(paths.get(0)).startsWith("/stable/quote?symbol=AAPL&apikey=good");
        assertThatThrownBy(() -> fmp.test(ctx("bad"))).satisfies(e -> assertThat(kind(e)).isEqualTo(ConnectorException.Kind.AUTH));
        assertThatThrownBy(() -> fmp.test(ctx("soft"))).satisfies(e -> assertThat(kind(e)).isEqualTo(ConnectorException.Kind.AUTH));
    }

    @Test
    @SuppressWarnings("unchecked")
    void transcript_defaultsToLatestCall_andChunks() throws Exception {
        Map<String, Object> out = (Map<String, Object>) fmp.transcript(ctx("good"), om.readTree("{\"symbol\":\"aapl\",\"max_chars\":5000}"));
        assertThat(paths).anyMatch(p -> p.contains("/earning-call-transcript?symbol=AAPL&year=2026&quarter=4"));
        assertThat(out).containsEntry("call_date", "2026-10-30").containsEntry("period", "Q4").containsEntry("offset", 0)
                .containsEntry("next_offset", 5000).containsEntry("total_chars", TRANSCRIPT.length());
        assertThat((String) out.get("content")).hasSize(5000);

        Map<String, Object> last = (Map<String, Object>) fmp.transcript(ctx("good"),
                om.readTree("{\"symbol\":\"AAPL\",\"year\":2026,\"quarter\":4,\"offset\":" + (TRANSCRIPT.length() - 100) + "}"));
        assertThat(last).containsEntry("next_offset", null);
        assertThat((String) last.get("content")).hasSize(100);

        assertThatThrownBy(() -> fmp.transcript(ctx("good"), om.readTree("{\"symbol\":\"AAPL\",\"year\":2026}")))
                .satisfies(e -> assertThat(kind(e)).isEqualTo(ConnectorException.Kind.BAD_REQUEST));
    }

    @Test
    void transcript_onAPlanWithoutIt_isAuthWithAPlanMessage() {
        assertThatThrownBy(() -> fmp.transcript(ctx("free"), om.readTree("{\"symbol\":\"AAPL\",\"year\":2026,\"quarter\":4}")))
                .hasMessageContaining("not included in your FMP plan").satisfies(e -> assertThat(kind(e)).isEqualTo(ConnectorException.Kind.AUTH));
    }

    @Test
    @SuppressWarnings("unchecked")
    void estimates_nearestUpcomingPeriodFirst() throws Exception {
        Map<String, Object> out = (Map<String, Object>) fmp.estimates(ctx("good"), om.readTree("{\"symbol\":\"AAPL\",\"limit\":2}"));
        List<Map<String, Object>> rows = (List<Map<String, Object>>) out.get("estimates");
        assertThat(rows).extracting(r -> r.get("period_end")).containsExactly("2027-09-27", "2030-09-27");
        assertThat(rows.get(1)).containsEntry("eps_avg", 13.565).containsEntry("analysts_eps", 7.0);
        assertThatThrownBy(() -> fmp.estimates(ctx("good"), om.readTree("{\"symbol\":\"AAPL\",\"period\":\"monthly\"}")))
                .satisfies(e -> assertThat(kind(e)).isEqualTo(ConnectorException.Kind.BAD_REQUEST));
    }

    @Test
    @SuppressWarnings("unchecked")
    void priceTargets_combineConsensusSummaryAndNewestGrades() throws Exception {
        Map<String, Object> out = (Map<String, Object>) fmp.priceTargets(ctx("good"), om.readTree("{\"symbol\":\"AAPL\",\"grades\":1}"));
        assertThat((Map<String, Object>) out.get("consensus")).containsEntry("consensus", 337.67).containsEntry("high", 400.0);
        assertThat((Map<String, Object>) out.get("summary")).containsEntry("last_quarter_count", 20.0);
        assertThat((List<Map<String, Object>>) out.get("rating_actions")).singleElement()
                .satisfies(g -> assertThat(g).containsEntry("firm", "Jefferies").containsEntry("action", "upgrade"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void calendar_defaultsToEasternToday_filters_andBoundsTheRange() throws Exception {
        Map<String, Object> out = (Map<String, Object>) fmp.calendar(ctx("good"), om.readTree("{\"symbols\":[\"nvda\",\"aapl\"]}"));
        assertThat(out).containsEntry("from", "2026-11-13").containsEntry("to", "2026-11-20").containsEntry("matched", 2);
        assertThat((List<Map<String, Object>>) out.get("events")).extracting(r -> r.get("symbol")).containsExactly("AAPL", "NVDA");

        Map<String, Object> eco = (Map<String, Object>) fmp.calendar(ctx("good"), om.readTree("{\"kind\":\"economic\",\"impact\":\"High\"}"));
        assertThat((List<Map<String, Object>>) eco.get("events")).singleElement()
                .satisfies(e -> assertThat(e).containsEntry("event", "CPI YoY").containsEntry("actual", null));

        assertThatThrownBy(() -> fmp.calendar(ctx("good"), om.readTree("{\"from\":\"2026-01-01\",\"to\":\"2026-06-01\"}")))
                .satisfies(e -> assertThat(kind(e)).isEqualTo(ConnectorException.Kind.BAD_REQUEST));
        assertThatThrownBy(() -> fmp.calendar(ctx("good"), om.readTree("{\"kind\":\"dividends\"}")))
                .satisfies(e -> assertThat(kind(e)).isEqualTo(ConnectorException.Kind.BAD_REQUEST));
    }

    @Test
    @SuppressWarnings("unchecked")
    void insiderTrades_carryFilingDate_andLag() throws Exception {
        Map<String, Object> out = (Map<String, Object>) fmp.insiderTrades(ctx("good"), om.readTree("{\"symbol\":\"TRMK\",\"limit\":500}"));
        assertThat(paths.get(0)).contains("symbol=TRMK").contains("limit=100");
        Map<String, Object> t = ((List<Map<String, Object>>) out.get("trades")).get(0);
        assertThat(t).containsEntry("transaction_date", "2026-07-28").containsEntry("filed_date", "2026-07-30")
                .containsEntry("filing_lag_days", 2L).containsEntry("shares", 1608.0).containsEntry("insider", "Tate Granville Jr");
    }
}
