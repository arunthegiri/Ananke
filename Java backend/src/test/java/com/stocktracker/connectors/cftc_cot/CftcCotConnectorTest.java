package com.stocktracker.connectors.cftc_cot;

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
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** CFTC COT connector against a fake Socrata endpoint (rows trimmed from publicreporting.cftc.gov). */
class CftcCotConnectorTest {

    final ObjectMapper om = new ObjectMapper();
    final List<String> queries = new CopyOnWriteArrayList<>();
    final List<String> tokens = new CopyOnWriteArrayList<>();
    HttpServer server;
    CftcCotConnector cot;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.start();
        cot = new CftcCotConnector("http://127.0.0.1:" + server.getAddress().getPort() + "/resource");
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    ConnectorContext ctx(String token) {
        return new ConnectorContext(cot.spec(), k -> k.equals("app_token") ? Optional.ofNullable(token) : Optional.empty(),
                HttpClient.newHttpClient(), om, null);
    }

    void handle(HttpExchange ex) throws IOException {
        String p = ex.getRequestURI().getPath();
        String q = URLDecoder.decode(ex.getRequestURI().getRawQuery() == null ? "" : ex.getRequestURI().getRawQuery(), StandardCharsets.UTF_8);
        queries.add(p + "?" + q);
        String token = ex.getRequestHeaders().getFirst("X-App-Token");
        if (token != null) tokens.add(token);
        if ("bad".equals(token)) {
            send(ex, 403, "{\"code\":\"permission_denied\",\"error\":true,\"message\":\"Invalid app_token specified\"}");
            return;
        }
        if (q.contains("max(report_date_as_yyyy_mm_dd) as latest") && !q.contains("$group")) {
            send(ex, 200, "[{\"latest\":\"2026-11-03T00:00:00.000\"}]");
        } else if (q.contains("$group") && q.contains("'%GOLD%'")) {
            send(ex, 200, """
                    [{"cftc_contract_market_code":"088691","market_and_exchange_names":"GOLD - COMMODITY EXCHANGE INC.","latest":"2026-11-03T00:00:00.000"},
                     {"cftc_contract_market_code":"088695","market_and_exchange_names":"MICRO GOLD - COMMODITY EXCHANGE INC.","latest":"2026-11-03T00:00:00.000"},
                     {"cftc_contract_market_code":"088606","market_and_exchange_names":"GOLD, 100 TROY OZ - CHICAGO BOARD OF TRADE","latest":"2008-07-22T00:00:00.000"}]""");
        } else if (q.contains("$group") && q.contains("'%CRUDE%'")) {
            send(ex, 200, """
                    [{"cftc_contract_market_code":"067651","market_and_exchange_names":"WTI-PHYSICAL - NEW YORK MERCANTILE EXCHANGE","latest":"2026-11-03T00:00:00.000"},
                     {"cftc_contract_market_code":"067411","market_and_exchange_names":"CRUDE OIL, LIGHT SWEET-WTI - ICE FUTURES EUROPE","latest":"2026-11-03T00:00:00.000"}]""");
        } else if (p.equals("/resource/72hh-3qpy.json") && q.contains("cftc_contract_market_code='088691'")) {
            send(ex, 200, """
                    [{"market_and_exchange_names":"GOLD - COMMODITY EXCHANGE INC.","report_date_as_yyyy_mm_dd":"2026-11-03T00:00:00.000",
                      "cftc_contract_market_code":"088691","contract_units":"(CONTRACTS OF 100 TROY OUNCES)","open_interest_all":"500000",
                      "change_in_open_interest_all":"-1200",
                      "prod_merc_positions_long":"20000","prod_merc_positions_short":"60000","change_in_prod_merc_long":"100","change_in_prod_merc_short":"-50",
                      "traders_prod_merc_long_all":"20","traders_prod_merc_short_all":"30",
                      "swap_positions_long_all":"30000","swap__positions_short_all":"200000","swap__positions_spread_all":"15000",
                      "change_in_swap_long_all":"10","change_in_swap_short_all":"20","traders_swap_long_all":"10","traders_swap_short_all":"25",
                      "m_money_positions_long_all":"200000","m_money_positions_short_all":"25000","m_money_positions_spread":"40000",
                      "change_in_m_money_long_all":"5000","change_in_m_money_short_all":"-3000","traders_m_money_long_all":"150","traders_m_money_short_all":"60",
                      "other_rept_positions_long":"80000","other_rept_positions_short":"30000","other_rept_positions_spread":"60000",
                      "nonrept_positions_long_all":"25000","nonrept_positions_short_all":"40000"}]""");
        } else if (p.equals("/resource/6dca-aqww.json") && q.contains("cftc_contract_market_code='067651'")) {
            send(ex, 200, """
                    [{"market_and_exchange_names":"WTI-PHYSICAL - NEW YORK MERCANTILE EXCHANGE","report_date_as_yyyy_mm_dd":"2026-11-03T00:00:00.000",
                      "open_interest_all":"2000000","noncomm_positions_long_all":"300000","noncomm_positions_short_all":"100000","noncomm_postions_spread_all":"700000",
                      "comm_positions_long_all":"900000","comm_positions_short_all":"1150000","nonrept_positions_long_all":"100000","nonrept_positions_short_all":"50000"}]""");
        } else {
            send(ex, 200, "[]");
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
    void test_keyless_andABadAppTokenIsAuth() {
        assertThat(cot.test(ctx(null))).contains("2026-11-03");
        assertThatThrownBy(() -> cot.test(ctx("bad")))
                .isInstanceOf(ConnectorException.class)
                .satisfies(e -> assertThat(((ConnectorException) e).kind()).isEqualTo(ConnectorException.Kind.AUTH));
        assertThat(tokens).containsExactly("bad");
    }

    @Test
    @SuppressWarnings("unchecked")
    void report_resolvesAName_toTheExactActiveContract_andComputesNets() throws Exception {
        Map<String, Object> out = (Map<String, Object>) cot.report(ctx(null), om.readTree("{\"market\":\"gold\",\"report\":\"disaggregated\",\"weeks\":4}"));
        assertThat(out).containsEntry("code", "088691").containsEntry("market", "GOLD - COMMODITY EXCHANGE INC.");
        assertThat(queries.get(queries.size() - 1)).contains("/resource/72hh-3qpy.json").contains("$limit=4")
                .contains("$order=report_date_as_yyyy_mm_dd DESC");
        Map<String, Object> week = ((List<Map<String, Object>>) out.get("weeks")).get(0);
        assertThat(week).containsEntry("report_date", "2026-11-03").containsEntry("released_est", "2026-11-06").containsEntry("open_interest", 500000L);
        Map<String, Object> groups = (Map<String, Object>) week.get("groups");
        assertThat(groups).containsOnlyKeys("producer_merchant", "swap_dealer", "managed_money", "other_reportable", "nonreportable");
        assertThat((Map<String, Object>) groups.get("managed_money")).containsEntry("net", 175000L).containsEntry("net_pct_oi", 35.0)
                .containsEntry("spread", 40000L).containsEntry("change_long", 5000L).containsEntry("traders_long", 150L);
        assertThat((Map<String, Object>) groups.get("swap_dealer")).containsEntry("short", 200000L).containsEntry("net", -170000L);
    }

    @Test
    @SuppressWarnings("unchecked")
    void report_byCode_legacy_andAmbiguousNames() throws Exception {
        Map<String, Object> out = (Map<String, Object>) cot.report(ctx(null), om.readTree("{\"market\":\"067651\"}"));
        assertThat(queries).hasSize(1);   // a code needs no lookup
        Map<String, Object> groups = (Map<String, Object>) ((List<Map<String, Object>>) out.get("weeks")).get(0).get("groups");
        assertThat((Map<String, Object>) groups.get("noncommercial")).containsEntry("net", 200000L).containsEntry("spread", 700000L);
        assertThat((Map<String, Object>) groups.get("commercial")).containsEntry("net", -250000L).containsEntry("net_pct_oi", -12.5);
        assertThat(out.get("report")).asString().contains("futures only");

        assertThatThrownBy(() -> cot.report(ctx(null), om.readTree("{\"market\":\"crude\"}")))
                .hasMessageContaining("matches several markets").hasMessageContaining("067651");
        assertThatThrownBy(() -> cot.report(ctx(null), om.readTree("{\"market\":\"nothing here\"}")))
                .hasMessageContaining("No COT market matches");
        assertThatThrownBy(() -> cot.report(ctx(null), om.readTree("{\"market\":\"x;$where\"}")))
                .satisfies(e -> assertThat(((ConnectorException) e).kind()).isEqualTo(ConnectorException.Kind.BAD_REQUEST));
        assertThatThrownBy(() -> cot.report(ctx(null), om.readTree("{\"market\":\"067651\",\"report\":\"weekly\"}")))
                .hasMessageContaining("report must be one of");
    }

    @Test
    @SuppressWarnings("unchecked")
    void markets_escapeQuotes_andListLatestDates() throws Exception {
        Map<String, Object> out = (Map<String, Object>) cot.markets(ctx(null), om.readTree("{\"query\":\"gold\"}"));
        List<Map<String, Object>> rows = (List<Map<String, Object>>) out.get("markets");
        assertThat(rows.get(2)).containsEntry("code", "088606").containsEntry("latest_report", "2008-07-22");
        cot.markets(ctx(null), om.readTree("{\"query\":\"O'BRIEN\"}"));
        assertThat(queries.get(queries.size() - 1)).contains("'%O''BRIEN%'");
    }
}
