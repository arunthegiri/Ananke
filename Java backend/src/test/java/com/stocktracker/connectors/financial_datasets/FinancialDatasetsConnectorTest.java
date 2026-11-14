package com.stocktracker.connectors.financial_datasets;

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
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Financial Datasets against a fake API shaped like its OpenAPI spec. */
class FinancialDatasetsConnectorTest {

    static final String KEY = "fd-key-123";

    final ObjectMapper om = new ObjectMapper();
    final AtomicInteger permits = new AtomicInteger();
    FakeRestServer server;
    FinancialDatasetsConnector fd;

    @BeforeEach
    void start() throws Exception {
        server = new FakeRestServer("X-API-KEY");
        server.route = r -> {
            if (!KEY.equals(r.headers().get("X-API-KEY"))) {
                return new Response(401, "{\"error\":\"Invalid API key\",\"message\":\"Please provide a valid API key from https://financialdatasets.ai\"}");
            }
            return switch (r.path()) {
                case "/company/facts" -> Response.ok("""
                        {"company_facts":{"ticker":"AAPL","name":"Apple Inc","cik":"0000320193","industry":"Consumer Electronics",
                         "sector":"Information Technology","exchange":"NASDAQ","is_active":true,"location":"Cupertino, CA",
                         "sec_filings_url":"https://www.sec.gov/cgi-bin/browse-edgar?CIK=0000320193","sic_code":"3571"}}""");
                case "/financials/income-statements" -> Response.ok("""
                        {"income_statements":[
                          {"ticker":"AAPL","report_period":"2026-09-27","fiscal_period":"FY","period":"annual","currency":"USD",
                           "accession_number":"0000320193-26-000105","form_type":"10-K","filing_url":"https://www.sec.gov/x",
                           "filing_date":"2026-10-31","filing_datetime":"2026-10-31T06:01:36Z","revenue":416161000000,"net_income":112010000000,
                           "earnings_per_share_diluted":7.46},
                          {"ticker":"AAPL","report_period":"2025-09-27","period":"annual","filing_date":"2025-10-31","revenue":391035000000}],
                         "next_page_url":null}""");
                case "/financials/income-statements/segments" -> Response.ok("""
                        {"segmented_financials":[{"ticker":"AAPL","report_period":"2026-09-27","fiscal_period":"FY","period":"annual",
                          "currency":"USD","accession_number":"a","filing_url":"u",
                          "revenue":{"product":[{"label":"iPhone","value":209586000000}],"segment":[{"label":"Americas","value":178353000000}]},
                          "operating_income":null,"depreciation":null}]}""");
                case "/insider-trades" -> Response.ok("""
                        {"insider_trades":[{"ticker":"AAPL","issuer":"Apple Inc.","name":"COOK TIMOTHY D","title":"Chief Executive Officer",
                          "is_board_director":true,"form_type":"4","filing_date":"2026-10-03","report_period":"2026-10-01",
                          "transaction_date":"2026-10-01","transaction_code":"S","transaction_type":"Open market sale",
                          "transaction_shares":-129963,"transaction_price_per_share":255.4,"transaction_value":-33192736.2,
                          "shares_owned_before_transaction":3409254,"shares_owned_after_transaction":3279291,"security_title":"Common Stock"}]}""");
                case "/institutional-holdings" -> Response.ok("""
                        {"ticker":"AAPL","institutional_holdings":[{"ticker":"AAPL","name_of_issuer":"APPLE INC","report_period":"2026-09-30",
                          "filing_date":"2026-11-13","form_type":"13F-HR","accession_number":"x","title_of_class":"COM","put_call":null,
                          "shares":1415926535,"value_usd":361000000000,"reported_price":255.0,"filer_cik":"0000102909","filer_name":"VANGUARD GROUP INC",
                          "subsidiaries":[{"row_num":1,"shares":1}]}]}""");
                case "/news" -> new Response(402, "{\"error\":\"Payment required\",\"message\":\"Insufficient credits\"}");
                default -> new Response(404, "{\"error\":\"Not found\"}");
            };
        };
        fd = new FinancialDatasetsConnector(server.url());
    }

    @AfterEach
    void stop() {
        server.close();
    }

    ConnectorContext ctx(String key) {
        return new ConnectorContext(fd.spec(), k -> k.equals("api_key") ? Optional.ofNullable(key) : Optional.empty(),
                HttpClient.newHttpClient(), om, permits::incrementAndGet);
    }

    @Test
    void test_oneRequest_keyInHeader_badKeyIsAuth() {
        assertThat(fd.test(ctx(KEY))).contains("Apple Inc").contains("0000320193");
        assertThat(server.last().query()).doesNotContainKey("api_key");
        assertThat(permits.get()).isEqualTo(1);
        assertThatThrownBy(() -> fd.test(ctx("wrong")))
                .isInstanceOfSatisfying(ConnectorException.class, e -> assertThat(e.kind()).isEqualTo(Kind.AUTH))
                .hasMessageNotContaining("wrong");
        assertThat(fd.tools()).extracting(ConnectorTool::name).allMatch(n -> n.startsWith("financial_datasets_") && n.length() <= 64);
    }

    @Test
    @SuppressWarnings("unchecked")
    void statements_carryFilingTimes_andLimitIsOnePage() throws Exception {
        Map<String, Object> out = (Map<String, Object>) fd.statements(ctx(KEY),
                om.readTree("{\"ticker\":\"aapl\",\"statement\":\"income\",\"period\":\"annual\",\"limit\":50}"));
        assertThat(server.last().path()).isEqualTo("/financials/income-statements");
        assertThat(server.last().query()).containsEntry("ticker", "AAPL").containsEntry("period", "annual").containsEntry("limit", "10");
        List<Map<String, Object>> rows = (List<Map<String, Object>>) out.get("rows");
        assertThat(rows.get(0)).containsEntry("filing_datetime", "2026-10-31T06:01:36Z").containsEntry("revenue", 416161000000L)
                .containsEntry("earnings_per_share_diluted", 7.46);
        assertThatThrownBy(() -> fd.statements(ctx(KEY), om.readTree("{\"ticker\":\"AAPL\",\"statement\":\"equity\",\"period\":\"annual\"}")))
                .isInstanceOfSatisfying(ConnectorException.class, e -> assertThat(e.kind()).isEqualTo(Kind.BAD_REQUEST));
    }

    @Test
    @SuppressWarnings("unchecked")
    void segments_insiders_institutions_parse() throws Exception {
        Map<String, Object> seg = (Map<String, Object>) fd.segments(ctx(KEY), om.readTree("{\"ticker\":\"AAPL\",\"period\":\"annual\"}"));
        Map<String, Object> first = ((List<Map<String, Object>>) seg.get("rows")).get(0);
        assertThat(((Map<String, Object>) first.get("revenue")).get("product")).asList().hasSize(1);

        Map<String, Object> ins = (Map<String, Object>) fd.insiders(ctx(KEY), om.readTree("{\"ticker\":\"AAPL\",\"filed_since\":\"2026-10-01\"}"));
        assertThat(server.last().query()).containsEntry("filing_date_gte", "2026-10-01");
        assertThat(((List<Map<String, Object>>) ins.get("rows")).get(0)).containsEntry("filing_date", "2026-10-03")
                .containsEntry("transaction_type", "Open market sale").doesNotContainKey("issuer");

        Map<String, Object> inst = (Map<String, Object>) fd.institutions(ctx(KEY), om.readTree("{\"ticker\":\"AAPL\"}"));
        assertThat(((List<Map<String, Object>>) inst.get("rows")).get(0)).containsEntry("filer_name", "VANGUARD GROUP INC")
                .doesNotContainKey("subsidiaries");
    }

    @Test
    void outOfCredits_isAnUpstreamError() {
        assertThatThrownBy(() -> fd.news(ctx(KEY), om.createObjectNode()))
                .isInstanceOfSatisfying(ConnectorException.class, e -> assertThat(e.kind()).isEqualTo(Kind.UPSTREAM))
                .hasMessageContaining("402");
    }
}
