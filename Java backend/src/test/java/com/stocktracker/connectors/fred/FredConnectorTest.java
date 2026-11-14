package com.stocktracker.connectors.fred;

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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** FRED connector against a fake FRED (responses shaped like api.stlouisfed.org). */
class FredConnectorTest {

    static final String KEY = "abcdefghijklmnopqrstuvwxyz012345";

    final ObjectMapper om = new ObjectMapper();
    final List<String> queries = new CopyOnWriteArrayList<>();
    HttpServer server;
    FredConnector fred;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.start();
        fred = new FredConnector("http://127.0.0.1:" + server.getAddress().getPort() + "/fred",
                Clock.fixed(Instant.parse("2026-11-14T15:00:00Z"), ZoneOffset.UTC));
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    ConnectorContext ctx(String key) {
        return new ConnectorContext(fred.spec(), k -> k.equals("api_key") ? Optional.ofNullable(key) : Optional.empty(),
                HttpClient.newHttpClient(), om, null);
    }

    void handle(HttpExchange ex) throws IOException {
        String p = ex.getRequestURI().getPath();
        String q = URLDecoder.decode(ex.getRequestURI().getRawQuery() == null ? "" : ex.getRequestURI().getRawQuery(), StandardCharsets.UTF_8);
        queries.add(p + "?" + q);
        if (!q.contains("api_key=" + KEY)) {
            send(ex, 400, "{\"error_code\":400,\"error_message\":\"Bad Request.  The value for variable api_key is not registered.  Read https:\\/\\/fred.stlouisfed.org\\/docs\\/api\\/api_key.html for more information.\"}");
            return;
        }
        switch (p) {
            case "/fred/series" -> {
                if (q.contains("series_id=NOPE")) {
                    send(ex, 400, "{\"error_code\":400,\"error_message\":\"Bad Request.  The series does not exist.\"}");
                } else {
                    send(ex, 200, """
                            {"realtime_start":"2026-11-14","realtime_end":"2026-11-14","seriess":[{"id":"GDP","realtime_start":"2026-11-14",
                             "realtime_end":"2026-11-14","title":"Gross Domestic Product","observation_start":"1947-01-01",
                             "observation_end":"2026-07-01","frequency":"Quarterly","frequency_short":"Q","units":"Billions of Dollars",
                             "units_short":"Bil. of $","seasonal_adjustment":"Seasonally Adjusted Annual Rate","seasonal_adjustment_short":"SAAR",
                             "last_updated":"2026-10-30 07:51:02-05","popularity":92}]}""");
                }
            }
            case "/fred/series/observations" -> send(ex, 200, """
                    {"realtime_start":"2025-08-01","realtime_end":"2025-08-01","observation_start":"1600-01-01","observation_end":"9999-12-31",
                     "units":"lin","output_type":1,"file_type":"json","order_by":"observation_date","sort_order":"desc","count":3,"offset":0,"limit":2,
                     "observations":[
                      {"realtime_start":"2025-07-30","realtime_end":"2025-08-01","date":"2025-04-01","value":"30331.117"},
                      {"realtime_start":"2025-06-26","realtime_end":"2025-08-01","date":"2025-01-01","value":"."}]}""");
            case "/fred/series/vintagedates" -> send(ex, 200, """
                    {"realtime_start":"1776-07-04","realtime_end":"9999-12-31","order_by":"vintage_date","sort_order":"desc","count":412,
                     "offset":0,"limit":2,"vintage_dates":["2026-10-30","2026-09-25"]}""");
            case "/fred/series/release" -> send(ex, 200, """
                    {"realtime_start":"2026-11-14","realtime_end":"2026-11-14","releases":[{"id":50,"realtime_start":"2026-11-14",
                     "realtime_end":"2026-11-14","name":"Employment Situation","press_release":true,"link":"http://www.bls.gov/ces/"}]}""");
            case "/fred/release/dates" -> send(ex, 200, """
                    {"realtime_start":"2026-11-14","realtime_end":"2026-12-31","order_by":"release_date","sort_order":"asc","count":2,
                     "offset":0,"limit":1000,"release_dates":[{"release_id":50,"date":"2026-11-06"},{"release_id":50,"date":"2026-12-04"}]}""");
            case "/fred/releases/dates" -> send(ex, 200, """
                    {"realtime_start":"2026-11-14","realtime_end":"2026-11-28","order_by":"release_date","sort_order":"asc","count":3,
                     "offset":0,"limit":1000,"release_dates":[
                      {"release_id":10,"release_name":"Consumer Price Index","date":"2026-11-14"},
                      {"release_id":53,"release_name":"Gross Domestic Product","date":"2026-11-25"},
                      {"release_id":21,"release_name":"H.6 Money Stock Measures","date":"2026-11-26"}]}""");
            case "/fred/series/search" -> send(ex, 200, """
                    {"count":1,"offset":0,"limit":10,"seriess":[{"id":"PCEPILFE","title":"Personal Consumption Expenditures Excluding Food and Energy (Chain-Type Price Index)",
                     "observation_start":"1959-01-01","observation_end":"2026-09-01","frequency_short":"M","units_short":"Index 2017=100",
                     "seasonal_adjustment_short":"SA","last_updated":"2026-10-31 07:47:02-05","popularity":81,"notes":"long text"}]}""");
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
    void badKey_isAuth_andMissingKey_isNotLinked() {
        assertThatThrownBy(() -> fred.test(ctx("badbadbadbadbadbadbadbadbadbadba")))
                .isInstanceOf(ConnectorException.class)
                .satisfies(e -> assertThat(((ConnectorException) e).kind()).isEqualTo(ConnectorException.Kind.AUTH));
        assertThatThrownBy(() -> fred.test(ctx(null)))
                .satisfies(e -> assertThat(((ConnectorException) e).kind()).isEqualTo(ConnectorException.Kind.NOT_LINKED));
        assertThat(fred.test(ctx(KEY))).contains("Gross Domestic Product").contains("2026-10-30");
    }

    @Test
    @SuppressWarnings("unchecked")
    void observations_asOf_asksAlfredForThatVintage_andKeepsRealtimeStart() throws Exception {
        Map<String, Object> out = (Map<String, Object>) fred.observations(ctx(KEY),
                om.readTree("{\"series_id\":\"gdp\",\"as_of\":\"2025-08-01\",\"limit\":2}"));
        String obsQuery = queries.stream().filter(s -> s.startsWith("/fred/series/observations")).findFirst().orElseThrow();
        assertThat(obsQuery).contains("series_id=GDP").contains("realtime_start=2025-08-01").contains("realtime_end=2025-08-01")
                .contains("sort_order=desc").contains("limit=2").contains("file_type=json");
        List<Map<String, Object>> rows = (List<Map<String, Object>>) out.get("observations");
        assertThat(rows.get(0)).containsEntry("date", "2025-04-01").containsEntry("value", 30331.117)
                .containsEntry("realtime_start", "2025-07-30").doesNotContainKey("realtime_end");
        assertThat(rows.get(1)).containsEntry("value", null);
        assertThat(out).containsEntry("as_of", "2025-08-01").containsEntry("title", "Gross Domestic Product");
        assertThat(out.get("note")).asString().contains("ALFRED");
    }

    @Test
    void observations_validateArgsBeforeCalling() throws Exception {
        assertThatThrownBy(() -> fred.observations(ctx(KEY), om.readTree("{\"series_id\":\"GDP; DROP\"}")))
                .satisfies(e -> assertThat(((ConnectorException) e).kind()).isEqualTo(ConnectorException.Kind.BAD_REQUEST));
        assertThatThrownBy(() -> fred.observations(ctx(KEY), om.readTree("{\"series_id\":\"GDP\",\"as_of\":\"2025-01-01\",\"realtime_start\":\"2024-01-01\"}")))
                .hasMessageContaining("either as_of");
        assertThatThrownBy(() -> fred.observations(ctx(KEY), om.readTree("{\"series_id\":\"GDP\",\"as_of\":\"2027-01-01\"}")))
                .hasMessageContaining("future");
        assertThatThrownBy(() -> fred.observations(ctx(KEY), om.readTree("{\"series_id\":\"GDP\",\"units\":\"bogus\"}")))
                .hasMessageContaining("units must be one of");
        assertThat(queries).isEmpty();

        assertThatThrownBy(() -> fred.observations(ctx(KEY), om.readTree("{\"series_id\":\"NOPE\"}")))
                .hasMessageContaining("The series does not exist")
                .satisfies(e -> assertThat(((ConnectorException) e).kind()).isEqualTo(ConnectorException.Kind.BAD_REQUEST));
    }

    @Test
    @SuppressWarnings("unchecked")
    void releaseCalendar_includesScheduledDates_andResolvesASeriesToItsRelease() throws Exception {
        Map<String, Object> all = (Map<String, Object>) fred.releaseCalendar(ctx(KEY), om.readTree("{\"start\":\"2026-11-20\",\"end\":\"2026-11-30\"}"));
        String q = queries.get(queries.size() - 1);
        // future start: FRED is asked from today, then filtered locally
        assertThat(q).contains("realtime_start=2026-11-14").contains("realtime_end=2026-11-30").contains("include_release_dates_with_no_data=true");
        List<Map<String, Object>> rows = (List<Map<String, Object>>) all.get("release_dates");
        assertThat(rows).extracting(r -> r.get("release")).containsExactly("Gross Domestic Product", "H.6 Money Stock Measures");
        assertThat(rows.get(0)).containsEntry("status", "scheduled");

        Map<String, Object> nfp = (Map<String, Object>) fred.releaseCalendar(ctx(KEY), om.readTree("{\"series_id\":\"PAYEMS\",\"start\":\"2026-11-01\",\"end\":\"2026-12-31\"}"));
        assertThat(nfp).containsEntry("release", "Employment Situation").containsEntry("release_id", 50);
        List<Map<String, Object>> dates = (List<Map<String, Object>>) nfp.get("release_dates");
        assertThat(dates).extracting(r -> r.get("status")).containsExactly("published", "scheduled");

        assertThatThrownBy(() -> fred.releaseCalendar(ctx(KEY), om.readTree("{\"start\":\"2026-01-01\",\"end\":\"2026-12-31\"}")))
                .hasMessageContaining("120 days");
    }

    @Test
    @SuppressWarnings("unchecked")
    void search_andVintages_areCompact() throws Exception {
        Map<String, Object> s = (Map<String, Object>) fred.search(ctx(KEY), om.readTree("{\"query\":\"core pce\"}"));
        List<Map<String, Object>> rows = (List<Map<String, Object>>) s.get("series");
        assertThat(rows.get(0)).containsEntry("id", "PCEPILFE").containsEntry("frequency", "M").doesNotContainKey("notes");
        assertThat(queries.get(0)).contains("search_text=core pce").contains("order_by=popularity");

        Map<String, Object> v = (Map<String, Object>) fred.vintageDates(ctx(KEY), om.readTree("{\"series_id\":\"GDP\",\"limit\":2}"));
        assertThat((List<String>) v.get("vintage_dates")).containsExactly("2026-10-30", "2026-09-25");
        assertThat(v).containsEntry("total_vintages", 412);
    }
}
