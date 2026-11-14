package com.stocktracker.connectors.usaspending;

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

/** USAspending connector against a fake API (responses shaped like recorded live ones). */
class UsaSpendingConnectorTest {

    final ObjectMapper om = new ObjectMapper();
    final List<String> paths = new CopyOnWriteArrayList<>();
    final List<JsonNode> bodies = new CopyOnWriteArrayList<>();
    HttpServer server;
    UsaSpendingConnector usa;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.start();
        // 03:00 UTC on Nov 15 is still Nov 14 in New York.
        usa = new UsaSpendingConnector("http://127.0.0.1:" + server.getAddress().getPort(),
                Clock.fixed(Instant.parse("2026-11-15T03:00:00Z"), ZoneOffset.UTC));
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    ConnectorContext ctx() {
        return new ConnectorContext(usa.spec(), k -> Optional.empty(), HttpClient.newHttpClient(), om, null);
    }

    void handle(HttpExchange ex) throws IOException {
        String p = ex.getRequestURI().getPath();
        paths.add(p);
        byte[] in = ex.getRequestBody().readAllBytes();
        if (in.length > 0) bodies.add(om.readTree(in));
        switch (p) {
            case "/api/v2/awards/last_updated/" -> send(ex, 200, "{\"last_updated\":\"11/14/2026\"}");
            case "/api/v2/search/spending_by_award/" -> send(ex, 200, """
                    {"spending_level":"awards","limit":2,"results":[
                     {"internal_id":355423040,"Award ID":"W9128Z26FA001","Recipient Name":"PALANTIR USG INC","Award Amount":637552404.79,
                      "Total Outlays":0.0,"Description":"CDAO MSS TASK ORDER OFF OF CONTRACT W519TC25D0039","Start Date":"2025-10-15",
                      "End Date":"2029-05-31","Awarding Agency":"Department of Defense","Awarding Sub Agency":"Department of the Army",
                      "Contract Award Type":"DELIVERY ORDER","Last Modified Date":"2026-07-01 09:01:04","Base Obligation Date":"2025-11-21",
                      "generated_internal_id":"CONT_AWD_W9128Z26FA001_9700_W519TC25D0039_9700",
                      "NAICS":{"code":"541511","description":"CUSTOM COMPUTER PROGRAMMING SERVICES"},
                      "PSC":{"code":"7F20","description":"IT AND TELECOM - IT MANAGEMENT TOOLS"}}],
                     "page_metadata":{"page":1,"hasNext":true}}""");
            case "/api/v2/search/spending_by_transaction/" -> send(ex, 200, """
                    {"limit":2,"results":[{"Award ID":"36C10B25F0313","Recipient Name":"PALANTIR TECHNOLOGIES INC.","Action Date":"2026-09-29",
                      "Transaction Amount":32805784.08,"Awarding Agency":"Department of Veterans Affairs","Awarding Sub Agency":"Department of Veterans Affairs",
                      "Transaction Description":"NATIONAL CENTER FOR VETERANS ANALYSIS AND STATISTICS PLATFORM","Mod":"P00008","Award Type":"DELIVERY ORDER",
                      "generated_internal_id":"CONT_AWD_36C10B25F0313_3600_GS35F0086U_4730","internal_id":279971078}],
                     "page_metadata":{"page":1,"hasNext":false}}""");
            case "/api/v2/search/spending_by_category/awarding_agency/" -> send(ex, 200, """
                    {"category":"awarding_agency","results":[
                     {"id":1173,"code":"DOD","name":"Department of Defense","amount":750.0},
                     {"id":766,"code":"DHS","name":"Department of Homeland Security","amount":200.0},
                     {"id":95,"code":"USDA","name":"Department of Agriculture","amount":50.0}]}""");
            case "/api/v2/search/spending_by_category/recipient/" -> send(ex, 200, """
                    {"category":"recipient","results":[{"amount":17306924328.99,"recipient_id":"779b3e3e-364f-7cbc-28a4-a2b4484b86f4-C",
                      "name":"LOCKHEED MARTIN CORPORATION","code":"794571448","uei":"XFJMYSYFJEK4"}]}""");
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
    void test_reportsLastUpdated() throws Exception {
        assertThat(usa.test(ctx())).contains("11/14/2026");
    }

    @Test
    @SuppressWarnings("unchecked")
    void awards_defaultWindowIsTheLastYearInEasternTime_andRowsCarryDates() throws Exception {
        Map<String, Object> out = (Map<String, Object>) usa.awards(ctx(), om.readTree("{\"recipient\":\"Palantir\",\"limit\":2}"));
        JsonNode body = bodies.get(0);
        assertThat(body.path("filters").path("time_period").get(0).path("end_date").asText()).isEqualTo("2026-11-14");
        assertThat(body.path("filters").path("time_period").get(0).path("start_date").asText()).isEqualTo("2025-11-14");
        assertThat(body.path("filters").path("award_type_codes").toString()).isEqualTo("[\"A\",\"B\",\"C\",\"D\"]");
        assertThat(body.path("filters").path("recipient_search_text").get(0).asText()).isEqualTo("Palantir");
        assertThat(body.path("sort").asText()).isEqualTo("Award Amount");
        assertThat(body.path("limit").asInt()).isEqualTo(2);

        assertThat(out).containsEntry("has_more", true).containsEntry("count", 1);
        Map<String, Object> a = ((List<Map<String, Object>>) out.get("awards")).get(0);
        assertThat(a).containsEntry("amount", 637552404.79).containsEntry("agency", "Department of Defense")
                .containsEntry("naics", "541511 CUSTOM COMPUTER PROGRAMMING SERVICES")
                .containsEntry("base_obligation_date", "2025-11-21").containsEntry("last_modified", "2026-07-01 09:01:04")
                .containsEntry("url", "https://www.usaspending.gov/award/CONT_AWD_W9128Z26FA001_9700_W519TC25D0039_9700");
    }

    @Test
    @SuppressWarnings("unchecked")
    void transactions_sortRecentByActionDate() throws Exception {
        Map<String, Object> out = (Map<String, Object>) usa.transactions(ctx(),
                om.readTree("{\"recipient\":\"Palantir\",\"from\":\"2026-09-01\",\"to\":\"2026-11-14\",\"sort\":\"recent\",\"award_type\":\"idvs\"}"));
        assertThat(bodies.get(0).path("sort").asText()).isEqualTo("Action Date");
        assertThat(bodies.get(0).path("filters").path("award_type_codes").get(0).asText()).isEqualTo("IDV_A");
        Map<String, Object> t = ((List<Map<String, Object>>) out.get("transactions")).get(0);
        assertThat(t).containsEntry("action_date", "2026-09-29").containsEntry("modification", "P00008").containsEntry("amount", 32805784.08);
    }

    @Test
    @SuppressWarnings("unchecked")
    void agencies_rankWithShares_andRecipientsListEntities() throws Exception {
        Map<String, Object> out = (Map<String, Object>) usa.agencies(ctx(), om.readTree("{\"recipient\":\"Palantir\",\"limit\":2}"));
        assertThat(out).containsEntry("total_obligated", 1000.0).containsEntry("agency_count", 3);
        List<Map<String, Object>> rows = (List<Map<String, Object>>) out.get("agencies");
        assertThat(rows).hasSize(2);
        assertThat(rows.get(0)).containsEntry("code", "DOD").containsEntry("share", 0.75);

        Map<String, Object> rec = (Map<String, Object>) usa.recipients(ctx(), om.readTree("{\"recipient\":\"Lockheed\"}"));
        assertThat(((List<Map<String, Object>>) rec.get("recipients")).get(0)).containsEntry("uei", "XFJMYSYFJEK4");
    }

    @Test
    void badArguments_areBadRequests_withoutCallingUpstream() {
        for (String args : List.of("{}", "{\"recipient\":\"ab\"}", "{\"recipient\":\"Palantir\",\"from\":\"2001-01-01\"}",
                "{\"recipient\":\"Palantir\",\"from\":\"2026-02-01\",\"to\":\"2026-01-01\"}", "{\"recipient\":\"Palantir\",\"from\":\"last year\"}",
                "{\"recipient\":\"Palantir\",\"award_type\":\"loans\"}")) {
            assertThatThrownBy(() -> usa.awards(ctx(), om.readTree(args)))
                    .as(args).isInstanceOf(ConnectorException.class).extracting("kind").isEqualTo(ConnectorException.Kind.BAD_REQUEST);
        }
        assertThat(paths).isEmpty();
    }
}
