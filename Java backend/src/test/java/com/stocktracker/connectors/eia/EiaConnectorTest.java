package com.stocktracker.connectors.eia;

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

/** EIA connector against a fake EIA v2 API (bodies recorded from api.eia.gov). */
class EiaConnectorTest {

    static final String KEY = "eiakey123";

    final ObjectMapper om = new ObjectMapper();
    final List<String> queries = new CopyOnWriteArrayList<>();
    HttpServer server;
    EiaConnector eia;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.start();
        eia = new EiaConnector("http://127.0.0.1:" + server.getAddress().getPort() + "/v2");
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    ConnectorContext ctx(String key) {
        return new ConnectorContext(eia.spec(), k -> k.equals("api_key") ? Optional.ofNullable(key) : Optional.empty(),
                HttpClient.newHttpClient(), om, null);
    }

    void handle(HttpExchange ex) throws IOException {
        String p = ex.getRequestURI().getPath();
        String q = URLDecoder.decode(ex.getRequestURI().getRawQuery() == null ? "" : ex.getRequestURI().getRawQuery(), StandardCharsets.UTF_8);
        queries.add(p + "?" + q);
        if (!q.contains("api_key=" + KEY)) {
            send(ex, 403, "{\"error\":{\"code\":\"API_KEY_INVALID\",\"message\":\"An invalid api_key was supplied. Get one at https://api.eia.gov:443\"}}");
            return;
        }
        switch (p) {
            case "/v2/petroleum/stoc/wstk" -> send(ex, 200, """
                    {"response":{"id":"petroleum","description":"EIA petroleum gas survey data","frequency":[{"id":"weekly","query":"W"}],
                     "facets":[{"id":"duoarea","description":"DuoArea"},{"id":"series","description":"Series"}],"data":{"value":[]},
                     "startPeriod":"1982-08-20","endPeriod":"2026-11-06","defaultFrequency":"weekly"},"apiVersion":"2.1.14"}""");
            case "/v2/" -> send(ex, 200, """
                    {"response":{"id":"","name":"","description":"Root","routes":[{"id":"coal","name":"Coal"},{"id":"petroleum","name":"Petroleum"}]}}""");
            case "/v2/petroleum/nope" -> send(ex, 400, "{\"error\":\"Requested path \\/petroleum\\/nope is not valid.\",\"code\":400}");
            case "/v2/petroleum/stoc/wstk/data/" -> send(ex, 200, """
                    {"warnings":[{"warning":"incomplete return"}],"response":{"total":"4225","dateFormat":"YYYY-MM-DD","frequency":"weekly","data":[
                     {"period":"2026-11-06","series":"WGTSTUS1","series-description":"U.S. Ending Stocks of Total Gasoline (Thousand Barrels)","value":"210757","units":"MBBL"},
                     {"period":"2026-11-06","series":"WCESTUS1","series-description":"U.S. Ending Stocks excluding SPR of Crude Oil (Thousand Barrels)","value":"414417","units":"MBBL"},
                     {"period":"2026-10-30","series":"WGTSTUS1","series-description":"U.S. Ending Stocks of Total Gasoline (Thousand Barrels)","value":"208958","units":"MBBL"},
                     {"period":"2026-10-30","series":"WCESTUS1","series-description":"U.S. Ending Stocks excluding SPR of Crude Oil (Thousand Barrels)","value":"416200","units":"MBBL"}]}}""");
            case "/v2/natural-gas/stor/wkly/data/" -> send(ex, 200, """
                    {"response":{"total":"7040","frequency":"weekly","data":[
                     {"period":"2026-11-06","series":"NW2_EPG0_SWO_R48_BCF","series-description":"Weekly Lower 48 States Natural Gas Working Underground Storage (Billion Cubic Feet)","value":"3862","units":"BCF"},
                     {"period":"2026-10-30","series":"NW2_EPG0_SWO_R48_BCF","series-description":"Weekly Lower 48 States Natural Gas Working Underground Storage (Billion Cubic Feet)","value":"3818","units":"BCF"}]}}""");
            case "/v2/petroleum/pri/spt/data/" -> send(ex, 200, """
                    {"response":{"total":"20311","frequency":"daily","data":[
                     {"period":"2026-11-11","series":"RWTC","series-description":"Cushing, OK WTI Spot Price FOB (Dollars per Barrel)","value":"89.62","units":"$/BBL"},
                     {"period":"2026-11-11","series":"RBRTE","series-description":"Europe Brent Spot Price FOB (Dollars per Barrel)","value":"92.14","units":"$/BBL"}]}}""");
            case "/v2/natural-gas/pri/fut/data/" -> send(ex, 200, """
                    {"response":{"total":"7504","frequency":"daily","data":[
                     {"period":"2026-11-11","series":"RNGWHHD","series-description":"Henry Hub Natural Gas Spot Price (Dollars per Million Btu)","value":"3","units":"$/MMBTU"}]}}""");
            default -> send(ex, 404, "{}");
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
    void badKey_isAuth() {
        assertThatThrownBy(() -> eia.test(ctx("wrong")))
                .isInstanceOf(ConnectorException.class)
                .satisfies(e -> assertThat(((ConnectorException) e).kind()).isEqualTo(ConnectorException.Kind.AUTH));
        assertThat(eia.test(ctx(KEY))).contains("2026-11-06");
    }

    @Test
    @SuppressWarnings("unchecked")
    void petroleumInventories_pivotPerSeries_withWeeklyChangeAndReleaseEstimate() throws Exception {
        Map<String, Object> out = (Map<String, Object>) eia.petroleumInventories(ctx(KEY),
                om.readTree("{\"series\":[\"crude\",\"gasoline\"],\"weeks\":1}"));
        assertThat(queries.get(0)).contains("facets[series][]=WCESTUS1").contains("facets[series][]=WGTSTUS1")
                .contains("sort[0][column]=period").contains("sort[0][direction]=desc").contains("length=4").contains("frequency=weekly");
        List<Map<String, Object>> series = (List<Map<String, Object>>) out.get("series");
        assertThat(series).extracting(s -> s.get("name")).containsExactly("crude", "gasoline");
        Map<String, Object> crude = ((List<Map<String, Object>>) series.get(0).get("values")).get(0);
        assertThat(crude).containsEntry("week_ending", "2026-11-06").containsEntry("released_est", "2026-11-11").containsEntry("value", 414417L);
        assertThat(crude.get("change").toString()).isEqualTo("-1783");
        assertThat((List<?>) series.get(0).get("values")).hasSize(1);

        assertThatThrownBy(() -> eia.petroleumInventories(ctx(KEY), om.readTree("{\"series\":[\"unobtainium\"]}")))
                .hasMessageContaining("series must be among");
    }

    @Test
    @SuppressWarnings("unchecked")
    void gasStorage_andSpotPrices() throws Exception {
        Map<String, Object> gas = (Map<String, Object>) eia.gasStorage(ctx(KEY), om.readTree("{\"weeks\":1}"));
        Map<String, Object> l48 = ((List<Map<String, Object>>) gas.get("series")).get(0);
        Map<String, Object> v = ((List<Map<String, Object>>) l48.get("values")).get(0);
        assertThat(v).containsEntry("released_est", "2026-11-12").containsEntry("value", 3862L);
        assertThat(v.get("change").toString()).isEqualTo("44");
        assertThat(((List<Map<String, Object>>) gas.get("series")).get(1)).containsKey("warning");   // regions missing in the fake

        Map<String, Object> spot = (Map<String, Object>) eia.spotPrices(ctx(KEY), om.readTree("{}"));
        List<Map<String, Object>> s = (List<Map<String, Object>>) spot.get("series");
        assertThat(s).extracting(x -> x.get("name")).containsExactly("wti", "brent", "henry_hub");
        assertThat(((List<Map<String, Object>>) s.get(0).get("values")).get(0)).containsEntry("date", "2026-11-11").containsEntry("value", 89.62);
        assertThat(queries).anyMatch(x -> x.startsWith("/v2/natural-gas/pri/fut/data/") && x.contains("facets[series][]=RNGWHHD"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void genericData_andBrowse() throws Exception {
        Map<String, Object> out = (Map<String, Object>) eia.data(ctx(KEY), om.readTree(
                "{\"route\":\"/v2/petroleum/stoc/wstk/data\",\"frequency\":\"weekly\",\"facets\":{\"series\":[\"WCESTUS1\"]},\"start\":\"2026-10-01\",\"limit\":2}"));
        assertThat(queries.get(0)).startsWith("/v2/petroleum/stoc/wstk/data/").contains("data[0]=value").contains("start=2026-10-01").contains("length=2");
        List<Map<String, Object>> rows = (List<Map<String, Object>>) out.get("rows");
        assertThat(rows).hasSize(2);
        assertThat(rows.get(0)).containsEntry("value", 210757L).containsEntry("units", "MBBL");
        assertThat(out).containsEntry("total", 4225);

        Map<String, Object> root = (Map<String, Object>) eia.browse(ctx(KEY), om.readTree("{}"));
        assertThat((List<Map<String, Object>>) root.get("routes")).extracting(r -> r.get("route")).containsExactly("coal", "petroleum");
        Map<String, Object> wstk = (Map<String, Object>) eia.browse(ctx(KEY), om.readTree("{\"route\":\"petroleum/stoc/wstk\"}"));
        assertThat(wstk).containsEntry("end_period", "2026-11-06").containsEntry("frequencies", List.of("weekly"));

        assertThatThrownBy(() -> eia.browse(ctx(KEY), om.readTree("{\"route\":\"petroleum/nope\"}")))
                .hasMessageContaining("is not valid")
                .satisfies(e -> assertThat(((ConnectorException) e).kind()).isEqualTo(ConnectorException.Kind.BAD_REQUEST));
        assertThatThrownBy(() -> eia.data(ctx(KEY), om.readTree("{\"route\":\"../../etc\"}"))).hasMessageContaining("route must look like");
        assertThatThrownBy(() -> eia.data(ctx(KEY), om.readTree("{\"route\":\"petroleum/stoc/wstk\",\"facets\":{\"series\":[\"a&b=c\"]}}")))
                .hasMessageContaining("Bad facet value");
        assertThatThrownBy(() -> eia.data(ctx(KEY), om.readTree("{\"route\":\"petroleum/stoc/wstk\",\"start\":\"last week\"}")))
                .hasMessageContaining("start must look like");
    }
}
