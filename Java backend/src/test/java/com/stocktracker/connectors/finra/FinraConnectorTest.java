package com.stocktracker.connectors.finra;

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
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** FINRA connector against a fake Query API, CDN and token endpoint (rows recorded from api.finra.org / cdn.finra.org). */
class FinraConnectorTest {

    final ObjectMapper om = new ObjectMapper();
    final List<String> paths = new CopyOnWriteArrayList<>();
    final List<JsonNode> bodies = new CopyOnWriteArrayList<>();
    final List<String> auths = new CopyOnWriteArrayList<>();
    HttpServer server;
    FinraConnector finra;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.start();
        String base = "http://127.0.0.1:" + server.getAddress().getPort();
        // Saturday 2026-11-14: the latest file is Friday's
        finra = new FinraConnector(base + "/api", base + "/cdn", base + "/token?grant_type=client_credentials",
                Clock.fixed(Instant.parse("2026-11-14T15:00:00Z"), ZoneOffset.UTC));
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    ConnectorContext ctx(String id, String secret) {
        return new ConnectorContext(finra.spec(), k -> switch (k) {
            case "client_id" -> Optional.ofNullable(id);
            case "client_secret" -> Optional.ofNullable(secret);
            default -> Optional.empty();
        }, HttpClient.newHttpClient(), om, null);
    }

    void handle(HttpExchange ex) throws IOException {
        String p = ex.getRequestURI().getPath();
        paths.add(p);
        String auth = ex.getRequestHeaders().getFirst("Authorization");
        if (auth != null) auths.add(auth);
        if (p.equals("/token")) {
            String good = "Basic " + Base64.getEncoder().encodeToString("cid:secret".getBytes(StandardCharsets.UTF_8));
            if (good.equals(auth)) send(ex, 200, "{\"access_token\":\"tok123\",\"token_type\":\"Bearer\",\"expires_in\":\"1799\"}");
            else send(ex, 401, "{\"error\":\"invalid_client\"}");
            return;
        }
        if (p.startsWith("/api/")) {
            JsonNode body = om.readTree(ex.getRequestBody());
            bodies.add(body);
            if (auth != null && !auth.equals("Bearer tok123")) {
                send(ex, 401, "{\"message\":\"Unauthorized\"}");
                return;
            }
            String sym = body.path("compareFilters").path(0).path("fieldValue").asText();
            if (p.endsWith("/regShoDaily") && sym.equals("NODATA")) {
                ex.sendResponseHeaders(204, -1);   // FINRA answers 204 with no body when nothing matches
                ex.close();
            } else if (p.endsWith("/regShoDaily")) {
                send(ex, 200, sym.equals("BRK/B") ? """
                        [{"reportingFacilityCode":"NCTRF","totalParQuantity":123970.498977,"shortParQuantity":72243.724369,"marketCode":"B","tradeReportDate":"2026-11-12","securitiesInformationProcessorSymbolIdentifier":"BRK/B","shortExemptParQuantity":297},
                         {"reportingFacilityCode":"NQTRF","totalParQuantity":8782037.344107,"shortParQuantity":4650880.363833,"marketCode":"Q","tradeReportDate":"2026-11-12","securitiesInformationProcessorSymbolIdentifier":"BRK/B","shortExemptParQuantity":27084},
                         {"reportingFacilityCode":"NQTRF","totalParQuantity":1000000,"shortParQuantity":400000,"marketCode":"Q","tradeReportDate":"2026-11-13","securitiesInformationProcessorSymbolIdentifier":"BRK/B","shortExemptParQuantity":0}]""" : "[]");
            } else if (p.endsWith("/consolidatedShortInterest")) {
                send(ex, 200, """
                        [{"previousShortPositionQuantity":12370081,"averageDailyVolumeQuantity":4139681,"issueName":"BERKSHIRE HATHAWAY Class B","currentShortPositionQuantity":13769255,"changePreviousNumber":1399174,"settlementDate":"2026-10-15","marketClassCode":"NYSE","symbolCode":"BRKB","daysToCoverQuantity":3.33,"revisionFlag":null,"changePercent":11.31,"stockSplitFlag":null},
                         {"previousShortPositionQuantity":13769255,"averageDailyVolumeQuantity":4459554,"issueName":"BERKSHIRE HATHAWAY Class B","currentShortPositionQuantity":15232424,"changePreviousNumber":1463169,"settlementDate":"2026-10-30","marketClassCode":"NYSE","symbolCode":"BRKB","daysToCoverQuantity":3.42,"revisionFlag":null,"changePercent":10.63,"stockSplitFlag":null}]""");
            } else {
                send(ex, 404, "{}");
            }
            return;
        }
        if (p.equals("/cdn/equity/regsho/daily/CNMSshvol20261113.txt")) {
            send(ex, 200, """
                    Date|Symbol|ShortVolume|ShortExemptVolume|TotalVolume|Market
                    20261113|A|316826.314547|259|553450.606448|B,Q,N
                    20261113|AAL|12481230.7|1000|16000000|B,Q,N
                    20261113|BRK/B|825360.417974|367|1981230.754409|B,Q,N
                    20261113|TINY|900|0|1000|Q
                    20261113|ZZZ|1500000|0|2000000|Q,N
                    3
                    """);
            return;
        }
        send(ex, 403, "<?xml version=\"1.0\" encoding=\"UTF-8\"?><Error><Code>AccessDenied</Code><Message>Access Denied</Message></Error>");
    }

    void send(HttpExchange ex, int status, String body) throws IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", body.startsWith("[") || body.startsWith("{") ? "application/json" : "text/plain");
        ex.sendResponseHeaders(status, b.length);
        ex.getResponseBody().write(b);
        ex.close();
    }

    @Test
    @SuppressWarnings("unchecked")
    void shortVolume_sumsFacilitiesPerDay_newestFirst_andMapsClassShares() throws Exception {
        Map<String, Object> out = (Map<String, Object>) finra.shortVolume(ctx(null, null), om.readTree("{\"ticker\":\"BRK.B\",\"days\":5}"));
        JsonNode body = bodies.get(0);
        assertThat(body.path("compareFilters").path(0).path("fieldValue").asText()).isEqualTo("BRK/B");
        assertThat(body.path("dateRangeFilters").path(0).path("endDate").asText()).isEqualTo("2026-11-14");
        assertThat(auths).isEmpty();
        List<Map<String, Object>> days = (List<Map<String, Object>>) out.get("days");
        assertThat(days).extracting(d -> d.get("date")).containsExactly("2026-11-13", "2026-11-12");
        assertThat(days.get(0)).containsEntry("short_volume", 400000L).containsEntry("short_ratio_pct", 40.0);
        assertThat(days.get(1)).containsEntry("short_volume", 4723124L).containsEntry("total_volume", 8906008L)
                .containsEntry("short_exempt_volume", 27381L).containsEntry("short_ratio_pct", 53.0);
    }

    @Test
    @SuppressWarnings("unchecked")
    void shortVolumeMarket_walksBackToTheLatestFile_andRanksLiquidNames() throws Exception {
        Map<String, Object> out = (Map<String, Object>) finra.shortVolumeMarket(ctx(null, null), om.readTree("{\"limit\":2}"));
        assertThat(out).containsEntry("date", "2026-11-13").containsEntry("eligible_symbols", 3);
        assertThat(paths).noneMatch(p -> p.contains("20261114"));   // Saturday is skipped without a request
        List<Map<String, Object>> rows = (List<Map<String, Object>>) out.get("rows");
        assertThat(rows).extracting(r -> r.get("ticker")).containsExactly("AAL", "ZZZ");

        Map<String, Object> picked = (Map<String, Object>) finra.shortVolumeMarket(ctx(null, null),
                om.readTree("{\"date\":\"2026-11-13\",\"tickers\":[\"brk.b\",\"tiny\"]}"));
        assertThat((List<Map<String, Object>>) picked.get("rows")).extracting(r -> r.get("ticker")).containsExactly("BRK/B", "TINY");

        assertThatThrownBy(() -> finra.shortVolumeMarket(ctx(null, null), om.readTree("{\"date\":\"2026-11-14\"}")))
                .hasMessageContaining("no short-volume file for 2026-11-14")
                .satisfies(e -> assertThat(((ConnectorException) e).kind()).isEqualTo(ConnectorException.Kind.BAD_REQUEST));
    }

    @Test
    @SuppressWarnings("unchecked")
    void shortInterest_newestFirst_withDaysToCover() throws Exception {
        Map<String, Object> out = (Map<String, Object>) finra.shortInterest(ctx(null, null), om.readTree("{\"ticker\":\"BRK-B\",\"periods\":1}"));
        assertThat(bodies.get(0).path("compareFilters").path(0).path("fieldValue").asText()).isEqualTo("BRKB");
        List<Map<String, Object>> rows = (List<Map<String, Object>>) out.get("periods");
        assertThat(rows).singleElement().satisfies(r -> assertThat(r)
                .containsEntry("settlement_date", "2026-10-30").containsEntry("short_interest", 15232424)
                .containsEntry("days_to_cover", 3.42).doesNotContainKey("revised"));
        assertThat(out).containsEntry("issue", "BERKSHIRE HATHAWAY Class B");
        assertThat(out.get("note")).asString().contains("never use settlement_date");
    }

    @Test
    void credentials_fetchABearerToken_once_andBadOnesAreAuth() throws Exception {
        assertThat(finra.test(ctx("cid", "secret"))).contains("your API client credentials");
        finra.shortInterest(ctx("cid", "secret"), om.readTree("{\"ticker\":\"BRKB\"}"));
        assertThat(paths.stream().filter(p -> p.equals("/token"))).hasSize(1);
        assertThat(auths).contains("Bearer tok123");

        FinraConnector fresh = new FinraConnector(
                "http://127.0.0.1:" + server.getAddress().getPort() + "/api", "x",
                "http://127.0.0.1:" + server.getAddress().getPort() + "/token?grant_type=client_credentials", Clock.systemUTC());
        assertThatThrownBy(() -> fresh.test(ctx("cid", "wrong")))
                .isInstanceOf(ConnectorException.class)
                .satisfies(e -> assertThat(((ConnectorException) e).kind()).isEqualTo(ConnectorException.Kind.AUTH));
        assertThatThrownBy(() -> fresh.test(ctx("cid", null)))
                .satisfies(e -> assertThat(((ConnectorException) e).kind()).isEqualTo(ConnectorException.Kind.NOT_LINKED));
        assertThat(finra.test(ctx(null, null))).contains("anonymous");
    }

    @Test
    @SuppressWarnings("unchecked")
    void noRows_is204_andBecomesAWarning() throws Exception {
        Map<String, Object> out = (Map<String, Object>) finra.shortVolume(ctx(null, null), om.readTree("{\"ticker\":\"NODATA\"}"));
        assertThat((List<?>) out.get("days")).isEmpty();
        assertThat(out.get("warning")).asString().contains("check the ticker");
    }

    @Test
    void badTicker_isBadRequest() {
        assertThatThrownBy(() -> finra.shortVolume(ctx(null, null), om.readTree("{\"ticker\":\"$$$\"}")))
                .satisfies(e -> assertThat(((ConnectorException) e).kind()).isEqualTo(ConnectorException.Kind.BAD_REQUEST));
        assertThat(paths).isEmpty();
    }
}
