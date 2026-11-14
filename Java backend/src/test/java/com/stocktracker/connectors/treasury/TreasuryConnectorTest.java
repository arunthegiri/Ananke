package com.stocktracker.connectors.treasury;

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

/** Treasury connector against a fake Fiscal Data API and a fake yield-curve CSV (shapes recorded from the real ones). */
class TreasuryConnectorTest {

    final ObjectMapper om = new ObjectMapper();
    final List<String> queries = new CopyOnWriteArrayList<>();
    final AtomicInteger debtCalls = new AtomicInteger();
    final AtomicInteger permits = new AtomicInteger();
    HttpServer server;
    TreasuryConnector tsy;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.start();
        String base = "http://127.0.0.1:" + server.getAddress().getPort();
        tsy = new TreasuryConnector(base + "/fiscal", base + "/rates", Clock.fixed(Instant.parse("2026-11-14T15:00:00Z"), ZoneOffset.UTC));
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    ConnectorContext ctx() {
        return new ConnectorContext(tsy.spec(), k -> Optional.empty(), HttpClient.newHttpClient(), om, permits::incrementAndGet);
    }

    void handle(HttpExchange ex) throws IOException {
        String p = ex.getRequestURI().getPath();
        String q = URLDecoder.decode(ex.getRequestURI().getRawQuery() == null ? "" : ex.getRequestURI().getRawQuery(), StandardCharsets.UTF_8);
        queries.add(p + "?" + q);
        switch (p) {
            case "/rates/2026/all" -> send(ex, 200, q.contains("real_yield_curve") ? """
                    Date,"5 YR","7 YR","10 YR","20 YR","30 YR"
                    11/13/2026,2.68,2.78,2.91,3.15,3.28
                    """ : """
                    Date,"1 Mo","1.5 Month","2 Mo","3 Mo","4 Mo","6 Mo","1 Yr","2 Yr","3 Yr","5 Yr","7 Yr","10 Yr","20 Yr","30 Yr"
                    11/13/2026,4.10,4.18,4.26,4.32,4.34,4.42,4.65,4.88,4.92,4.98,5.05,5.15,5.52,5.47
                    11/12/2026,4.12,4.18,4.26,4.32,4.35,4.42,4.65,4.86,4.90,4.95,5.02,5.10,5.48,5.43
                    01/02/2026,4.30,,4.29,4.31,4.30,4.25,4.18,4.20,4.22,4.30,4.41,4.55,4.90,4.85
                    """, "text/csv");
            case "/rates/2025/all" -> send(ex, 200, """
                    Date,"1 Mo","2 Mo","3 Mo","4 Mo","6 Mo","1 Yr","2 Yr","3 Yr","5 Yr","7 Yr","10 Yr","20 Yr","30 Yr"
                    12/31/2025,4.31,4.30,4.32,4.30,4.24,4.17,4.24,4.27,4.38,4.48,4.57,4.86,4.78
                    """, "text/csv");
            case "/fiscal/v2/accounting/od/debt_to_penny" -> {
                if (debtCalls.getAndIncrement() == 0) {
                    send(ex, 503, "<!doctype html><title>Site Outage Page | U.S. Treasury Fiscal Data</title>", "text/html");
                } else {
                    send(ex, 200, """
                            {"data":[{"record_date":"2026-11-12","debt_held_public_amt":"32824386143424.80","intragov_hold_amt":"7784120713478.45","tot_pub_debt_out_amt":"40608506856903.25"},
                                     {"record_date":"2026-11-10","debt_held_public_amt":"32800204226578.75","intragov_hold_amt":"7783925853694.14","tot_pub_debt_out_amt":"40584130080272.89"}],
                             "meta":{"count":2,"total-count":8433}}""", "application/json");
                }
            }
            case "/fiscal/v1/accounting/dts/operating_cash_balance" -> send(ex, 200, """
                    {"data":[{"record_date":"2026-11-13","account_type":"Treasury General Account (TGA) Closing Balance","close_today_bal":"null","open_today_bal":"912134","open_month_bal":"912134"},
                             {"record_date":"2026-11-12","account_type":"Treasury General Account (TGA) Closing Balance","close_today_bal":"null","open_today_bal":"933127","open_month_bal":"933127"}],
                     "meta":{"count":2}}""", "application/json");
            case "/fiscal/v2/accounting/od/avg_interest_rates" -> send(ex, 200, """
                    {"data":[{"record_date":"2026-10-31","security_type_desc":"Marketable","security_desc":"Treasury Bills","avg_interest_rate_amt":"4.014"},
                             {"record_date":"2026-10-31","security_type_desc":"Marketable","security_desc":"Treasury Notes","avg_interest_rate_amt":"3.399"},
                             {"record_date":"2026-09-30","security_type_desc":"Marketable","security_desc":"Treasury Bills","avg_interest_rate_amt":"4.051"},
                             {"record_date":"2026-08-31","security_type_desc":"Marketable","security_desc":"Treasury Bills","avg_interest_rate_amt":"4.100"}]}""", "application/json");
            case "/fiscal/v1/accounting/od/treasury_securities_auctions_v2" -> send(ex, 200, q.contains(":gte:") ? """
                    {"data":[{"cusip":"91282CPQ8","security_type":"Note","security_desc":"10-Year Note","security_term":"10-Year",
                              "comp_auction_close_date":"2026-11-18","comp_auction_close_time":"01:00 PM ET","announcemt_date":"2026-11-12",
                              "issue_date":"2026-11-30","maturity_date":"2036-11-15","offering_amt":"42000000000","reopening":"No",
                              "bid_to_cover_ratio":"null","high_yield":"null"}]}""" : """
                    {"data":[{"cusip":"912797WF0","security_type":"Bill","security_desc":"4-Week Bill","security_term":"28-Day",
                              "comp_auction_close_date":"2026-11-12","comp_auction_close_time":"11:30 AM ET","announcemt_date":"2026-11-10",
                              "issue_date":"2026-11-17","maturity_date":"2026-12-15","offering_amt":"115000000000","reopening":"Yes",
                              "bid_to_cover_ratio":"2.94","high_rate":"3.955","high_yield":"null","investment_rate":"4.022","int_rate":"null",
                              "grand_total_par_awarded":"122676698100","grand_total_par_recog":"345602105100",
                              "prim_deal_comp_par_awarded":"27150000000","dir_bid_comp_par_awarded":"8251540000","indir_bid_comp_par_awarded":"72150108000"},
                             {"cusip":"91282CPM7","security_type":"Note","security_desc":"3-Year Note","security_term":"3-Year",
                              "comp_auction_close_date":"2026-11-10","comp_auction_close_time":"01:00 PM ET","announcemt_date":"2026-11-05",
                              "issue_date":"2026-11-17","maturity_date":"2029-11-15","offering_amt":"58000000000","reopening":"No",
                              "bid_to_cover_ratio":"2.61","high_yield":"4.912","int_rate":"4.875",
                              "grand_total_par_awarded":"58000000000","grand_total_par_recog":"151000000000",
                              "prim_deal_comp_par_awarded":"9000000000","dir_bid_comp_par_awarded":"10000000000","indir_bid_comp_par_awarded":"38000000000"}]}""",
                    "application/json");
            default -> send(ex, 404, "{}", "application/json");
        }
    }

    void send(HttpExchange ex, int status, String body, String type) throws IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", type);
        ex.sendResponseHeaders(status, b.length);
        ex.getResponseBody().write(b);
        ex.close();
    }

    @Test
    @SuppressWarnings("unchecked")
    void yieldCurve_normalisesTenors_addsSpreads_andSpansYears() throws Exception {
        Map<String, Object> out = (Map<String, Object>) tsy.yieldCurve(ctx(), om.readTree("{\"start\":\"2025-12-30\",\"end\":\"2026-11-14\"}"));
        List<Map<String, Object>> rows = (List<Map<String, Object>>) out.get("rows");
        assertThat(rows).extracting(r -> r.get("date")).containsExactly("2026-11-13", "2026-11-12", "2026-01-02", "2025-12-31");
        Map<String, Object> y = (Map<String, Object>) rows.get(0).get("yields");
        assertThat(y).containsEntry("1M", 4.10).containsEntry("1.5M", 4.18).containsEntry("10Y", 5.15).containsEntry("30Y", 5.47);
        assertThat(rows.get(0)).containsEntry("spread_10y_2y", 0.27).containsEntry("spread_10y_3m", 0.83);
        assertThat((Map<String, Object>) rows.get(2).get("yields")).doesNotContainKey("1.5M");   // blank cell
        assertThat(queries).anyMatch(s -> s.startsWith("/rates/2025/all") && s.contains("type=daily_treasury_yield_curve") && s.contains("_format=csv"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void yieldCurve_filtersTenors_realCurve_andValidates() throws Exception {
        Map<String, Object> out = (Map<String, Object>) tsy.yieldCurve(ctx(), om.readTree("{\"curve\":\"real\",\"tenors\":[\"10y\",\"30 Yr\"]}"));
        Map<String, Object> y = (Map<String, Object>) ((List<Map<String, Object>>) out.get("rows")).get(0).get("yields");
        assertThat(y).containsOnlyKeys("10Y", "30Y").containsEntry("10Y", 2.91);
        assertThatThrownBy(() -> tsy.yieldCurve(ctx(), om.readTree("{\"start\":\"2015-01-01\",\"end\":\"2026-01-01\"}")))
                .satisfies(e -> assertThat(((ConnectorException) e).kind()).isEqualTo(ConnectorException.Kind.BAD_REQUEST));
        assertThatThrownBy(() -> tsy.yieldCurve(ctx(), om.readTree("{\"curve\":\"zero\"}"))).hasMessageContaining("curve must be one of");
    }

    @Test
    @SuppressWarnings("unchecked")
    void auctions_resultsCarryTakedownShares_andUpcomingUsesAFutureFilter() throws Exception {
        Map<String, Object> out = (Map<String, Object>) tsy.auctions(ctx(), om.readTree("{\"security\":\"bill\"}"));
        List<Map<String, Object>> rows = (List<Map<String, Object>>) out.get("auctions");
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0)).containsEntry("security", "4-Week Bill").containsEntry("high_rate", 3.955).containsEntry("bid_to_cover", 2.94)
                .containsEntry("auction_date", "2026-11-12").containsEntry("auction_close_et", "11:30 AM ET")
                .containsEntry("indirect_pct", 67.1).containsEntry("primary_dealer_pct", 25.2).containsEntry("reopening", true)
                .doesNotContainKey("high_yield");
        assertThat(queries.get(0)).contains("filter=comp_auction_close_date:lte:2026-11-14").contains("sort=-comp_auction_close_date");

        Map<String, Object> notes = (Map<String, Object>) tsy.auctions(ctx(), om.readTree("{\"security\":\"3-year\"}"));
        assertThat((List<Map<String, Object>>) notes.get("auctions")).singleElement().satisfies(r ->
                assertThat(r).containsEntry("high_yield", 4.912).containsEntry("coupon", 4.875));

        Map<String, Object> up = (Map<String, Object>) tsy.auctions(ctx(), om.readTree("{\"status\":\"upcoming\"}"));
        assertThat((List<Map<String, Object>>) up.get("auctions")).singleElement().satisfies(r ->
                assertThat(r).containsEntry("security", "10-Year Note").containsEntry("offering_amt", 42000000000L).doesNotContainKey("bid_to_cover"));
        assertThat(queries.get(queries.size() - 1)).contains("comp_auction_close_date:gte:2026-11-14");

        assertThatThrownBy(() -> tsy.auctions(ctx(), om.readTree("{\"security\":\"x';drop\"}")))
                .satisfies(e -> assertThat(((ConnectorException) e).kind()).isEqualTo(ConnectorException.Kind.BAD_REQUEST));
    }

    @Test
    @SuppressWarnings("unchecked")
    void cashBalance_readsTheTgaQuirk_andEstimatesPublication() throws Exception {
        Map<String, Object> out = (Map<String, Object>) tsy.cashBalance(ctx(), om.readTree("{\"days\":1}"));
        List<Map<String, Object>> rows = (List<Map<String, Object>>) out.get("rows");
        assertThat(rows).singleElement().satisfies(r -> assertThat(r)
                .containsEntry("date", "2026-11-13").containsEntry("tga_closing_balance", 912134L)
                .containsEntry("change", -20993L).containsEntry("published_est", "2026-11-16"));   // Friday → Monday
        assertThat(queries.get(0)).contains("account_type:eq:Treasury General Account (TGA) Closing Balance").contains("page[size]=2");
    }

    @Test
    @SuppressWarnings("unchecked")
    void debt_retriesOnceThroughAFiscalDataOutage() throws Exception {
        Map<String, Object> out = (Map<String, Object>) tsy.debt(ctx(), om.readTree("{}"));
        List<Map<String, Object>> rows = (List<Map<String, Object>>) out.get("rows");
        assertThat(rows.get(0)).containsEntry("date", "2026-11-12").containsEntry("total_public_debt", 40608506856903.25)
                .containsEntry("published_est", "2026-11-13");
        assertThat((Double) rows.get(0).get("change_total")).isCloseTo(24376776630.36, org.assertj.core.data.Offset.offset(0.01));
        assertThat(debtCalls).hasValue(2);
        assertThat(permits).hasValue(2);
    }

    @Test
    @SuppressWarnings("unchecked")
    void avgInterestRates_groupByMonth_andStopAtTheAskedCount() throws Exception {
        Map<String, Object> out = (Map<String, Object>) tsy.avgInterestRates(ctx(), om.readTree("{\"months\":2}"));
        List<Map<String, Object>> months = (List<Map<String, Object>>) out.get("months");
        assertThat(months).extracting(m -> m.get("month_end")).containsExactly("2026-10-31", "2026-09-30");
        assertThat((Map<String, Object>) months.get(0).get("rates")).containsEntry("Treasury Bills", 4.014).containsEntry("Treasury Notes", 3.399);
        assertThat(queries.get(0)).contains("record_date:gte:2026-08-01").contains("security_type_desc:eq:Marketable");
    }

    @Test
    void test_readsTheYieldCurve() {
        assertThat(tsy.test(ctx())).contains("5.15% on 2026-11-13");
    }
}
