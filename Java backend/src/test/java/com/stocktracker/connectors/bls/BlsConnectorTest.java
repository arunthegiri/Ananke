package com.stocktracker.connectors.bls;

import com.fasterxml.jackson.databind.JsonNode;
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

/** BLS connector against a fake BLS v2 API (bodies recorded from api.bls.gov). */
class BlsConnectorTest {

    static final String KEY = "0123456789abcdef0123456789abcdef";

    final ObjectMapper om = new ObjectMapper();
    final List<JsonNode> bodies = new CopyOnWriteArrayList<>();
    HttpServer server;
    BlsConnector bls;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.start();
        bls = new BlsConnector("http://127.0.0.1:" + server.getAddress().getPort() + "/publicAPI/v2/timeseries/data/",
                Clock.fixed(Instant.parse("2026-11-14T15:00:00Z"), ZoneOffset.UTC));
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    ConnectorContext ctx(String key) {
        return new ConnectorContext(bls.spec(), k -> k.equals("api_key") ? Optional.ofNullable(key) : Optional.empty(),
                HttpClient.newHttpClient(), om, null);
    }

    void handle(HttpExchange ex) throws IOException {
        JsonNode body = om.readTree(ex.getRequestBody());
        bodies.add(body);
        String key = body.path("registrationkey").asText("");
        if (!key.isEmpty() && !key.equals(KEY)) {
            send(ex, """
                    {"status":"REQUEST_NOT_PROCESSED","responseTime":0,"message":["The key:%s provided by the User is invalid. Please provide a proper key for the operation to be successful"],"Results":{}}""".formatted(key));
            return;
        }
        if (body.path("seriesid").toString().contains("THROTTLED")) {
            send(ex, """
                    {"status":"REQUEST_NOT_PROCESSED","responseTime":0,"message":["Request could not be serviced, as the daily threshold for total number of requests allocated to the user has been reached."],"Results":{}}""");
            return;
        }
        String calc = key.isEmpty() ? "" : ",\"calculations\":{\"net_changes\":{\"1\":\"0.6\"},\"pct_changes\":{\"1\":\"0.2\",\"12\":\"2.9\"}}";
        String catalog = key.isEmpty() ? "" : "\"catalog\":{\"series_title\":\"All items in U.S. city average, all urban consumers, seasonally adjusted\"},";
        send(ex, """
                {"status":"REQUEST_SUCCEEDED","responseTime":20,"message":["Invalid Series for Series BOGUS123"],"Results":{"series":[
                 {"seriesID":"CUSR0000SA0",%s"data":[
                   {"year":"2026","period":"M10","periodName":"October","latest":"true","value":"337.476","footnotes":[{"code":"P","text":"preliminary"}]%s},
                   {"year":"2026","period":"M09","periodName":"September","value":"336.900","footnotes":[{}]},
                   {"year":"2026","period":"M08","periodName":"August","value":"336.100","footnotes":[{}]}]},
                 {"seriesID":"BOGUS123","data":[]}]}}""".formatted(catalog, calc));
    }

    void send(HttpExchange ex, String body) throws IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(200, b.length);
        ex.getResponseBody().write(b);
        ex.close();
    }

    @Test
    @SuppressWarnings("unchecked")
    void keyless_parsesSeries_flagsPreliminary_andReportsUnknownIds() throws Exception {
        Map<String, Object> out = (Map<String, Object>) bls.series(ctx(null), om.readTree("{\"series_ids\":[\"cusr0000sa0\",\"BOGUS123\"],\"limit\":2}"));
        JsonNode sent = bodies.get(0);
        assertThat(sent.has("registrationkey")).isFalse();
        assertThat(sent.path("startyear").asText()).isEqualTo("2025");
        assertThat(sent.path("endyear").asText()).isEqualTo("2026");
        List<Map<String, Object>> series = (List<Map<String, Object>>) out.get("series");
        Map<String, Object> cpi = series.get(0);
        assertThat(cpi).containsEntry("series_id", "CUSR0000SA0").containsEntry("title", "CPI-U all items, SA (index)");
        List<Map<String, Object>> obs = (List<Map<String, Object>>) cpi.get("observations");
        assertThat(obs).hasSize(2);
        assertThat(obs.get(0)).containsEntry("period", "2026-10").containsEntry("value", 337.476).containsEntry("preliminary", true);
        assertThat(obs.get(1)).doesNotContainKey("preliminary");
        assertThat(series.get(1)).containsKey("warning");
        assertThat((List<String>) out.get("messages")).containsExactly("Invalid Series for Series BOGUS123");
    }

    @Test
    @SuppressWarnings("unchecked")
    void withKey_sendsIt_andReturnsTitlesAndChanges() throws Exception {
        Map<String, Object> out = (Map<String, Object>) bls.series(ctx(KEY), om.readTree("{\"series_ids\":\"CUSR0000SA0\",\"start_year\":2010}"));
        assertThat(bodies.get(0).path("registrationkey").asText()).isEqualTo(KEY);
        assertThat(bodies.get(0).path("calculations").asBoolean()).isTrue();
        Map<String, Object> cpi = ((List<Map<String, Object>>) out.get("series")).get(0);
        assertThat(cpi.get("title")).asString().startsWith("All items");
        Map<String, Object> first = ((List<Map<String, Object>>) cpi.get("observations")).get(0);
        assertThat(first).containsEntry("pct_change_1m", 0.2).containsEntry("pct_change_12m", 2.9);
    }

    @Test
    void badKey_isAuth_withoutEchoingTheKey() {
        assertThatThrownBy(() -> bls.test(ctx("ffffffffffffffffffffffffffffffff")))
                .isInstanceOf(ConnectorException.class)
                .satisfies(e -> assertThat(((ConnectorException) e).kind()).isEqualTo(ConnectorException.Kind.AUTH))
                .satisfies(e -> assertThat(e.getMessage()).doesNotContain("ffffffff"));
    }

    @Test
    void validation_andDailyThreshold() throws Exception {
        assertThatThrownBy(() -> bls.series(ctx(null), om.readTree("{}"))).hasMessageContaining("series_ids is required");
        assertThatThrownBy(() -> bls.series(ctx(null), om.readTree("{\"series_ids\":[\"cpi; drop\"]}"))).hasMessageContaining("Not a BLS series id");
        assertThatThrownBy(() -> bls.series(ctx(null), om.readTree("{\"series_ids\":[\"CUSR0000SA0\"],\"start_year\":2010}")))
                .hasMessageContaining("At most 10 years");
        assertThat(bodies).isEmpty();
        assertThatThrownBy(() -> bls.series(ctx(null), om.readTree("{\"series_ids\":[\"THROTTLED\"]}")))
                .satisfies(e -> assertThat(((ConnectorException) e).kind()).isEqualTo(ConnectorException.Kind.RATE_LIMITED));
    }

    @Test
    void periods() {
        assertThat(BlsConnector.period("2026", "M01")).isEqualTo("2026-01");
        assertThat(BlsConnector.period("2026", "Q03")).isEqualTo("2026-Q3");
        assertThat(BlsConnector.period("2025", "M13")).isEqualTo("2025 (annual)");
        assertThat(BlsConnector.period("2025", "S01")).isEqualTo("2025-H1");
    }
}
