package com.stocktracker.connectors.senate_lda;

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

/** LDA connector against a fake lda.gov API (filings shaped like recorded live ones). */
class SenateLdaConnectorTest {

    static final String KEY = "good-key";

    final ObjectMapper om = new ObjectMapper();
    final List<String> requests = new CopyOnWriteArrayList<>();
    final List<String> auth = new CopyOnWriteArrayList<>();
    HttpServer server;
    String base;
    SenateLdaConnector lda;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort() + "/api/v1";
        lda = new SenateLdaConnector(base, Clock.fixed(Instant.parse("2026-11-14T18:00:00Z"), ZoneOffset.UTC));
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    ConnectorContext ctx(String key) {
        return new ConnectorContext(lda.spec(), k -> k.equals("api_key") ? Optional.ofNullable(key) : Optional.empty(),
                HttpClient.newHttpClient(), om, null);
    }

    static String filing(String type, String period, int year, String posted, int registrantId, String registrant,
                         String income, String expenses, String... codes) {
        StringBuilder acts = new StringBuilder();
        for (String c : codes) {
            if (acts.length() > 0) acts.append(',');
            acts.append("""
                    {"general_issue_code":"%s","general_issue_code_display":"%s","description":"S 4874/HR 8800 - National Defense Authorization Act",
                     "lobbyists":[{"lobbyist":{"id":1}},{"lobbyist":{"id":2}}],
                     "government_entities":[{"id":25,"name":"Defense, Dept of (DOD)"},{"id":1,"name":"SENATE"}]}""".formatted(c, c.equals("DEF") ? "Defense" : "Budget/Appropriations"));
        }
        return """
                {"filing_uuid":"u-%s-%s","filing_type":"%s","filing_type_display":"%s","filing_year":%d,"filing_period":"%s",
                 "filing_document_url":"https://lda.gov/filings/public/filing/u/print/","income":%s,"expenses":%s,"dt_posted":"%s",
                 "termination_date":null,"registrant":{"id":%d,"name":"%s"},"client":{"id":129850,"name":"LOCKHEED MARTIN CORPORATION"},
                 "lobbying_activities":[%s]}""".formatted(registrantId, posted, type, type, year, period,
                income == null ? "null" : "\"" + income + "\"", expenses == null ? "null" : "\"" + expenses + "\"", posted,
                registrantId, registrant, acts);
    }

    void handle(HttpExchange ex) throws IOException {
        String p = ex.getRequestURI().getPath();
        String q = ex.getRequestURI().getRawQuery() == null ? "" : URLDecoder.decode(ex.getRequestURI().getRawQuery(), StandardCharsets.UTF_8);
        requests.add(p + "?" + q);
        String a = ex.getRequestHeaders().getFirst("Authorization");
        auth.add(a == null ? "" : a);
        if (a != null && !a.equals("Token " + KEY)) {
            send(ex, 401, "{\"detail\":\"Invalid token.\"}");
            return;
        }
        if (!p.equals("/api/v1/filings/")) {
            send(ex, 404, "{}");
            return;
        }
        String in2026 = String.join(",",
                filing("Q2", "second_quarter", 2026, "2026-07-20T16:51:53-04:00", 1, "LOCKHEED MARTIN CORPORATION", null, "4180000.00", "DEF", "BUD"),
                filing("Q2", "second_quarter", 2026, "2026-07-20T15:26:30-04:00", 2, "MICHAEL BEST STRATEGIES LLC", "80000.00", null, "DEF"),
                filing("RR", "first_quarter", 2026, "2026-03-17T16:07:39-04:00", 3, "FEROX STRATEGIES", null, null, "DEF"));
        if (q.contains("filing_year=2026") && q.contains("page=2")) {
            // Page 2: Q1 report and its later amendment (the amendment wins).
            send(ex, 200, "{\"count\":5,\"next\":null,\"results\":[" + String.join(",",
                    filing("Q1", "first_quarter", 2026, "2026-04-20T10:00:00-04:00", 1, "LOCKHEED MARTIN CORPORATION", null, "3000000.00", "DEF"),
                    filing("1A", "first_quarter", 2026, "2026-05-01T10:00:00-04:00", 1, "LOCKHEED MARTIN CORPORATION", null, "3500000.00", "DEF")) + "]}");
        } else if (q.contains("filing_year=2026")) {
            send(ex, 200, "{\"count\":5,\"next\":\"" + base + "/filings/?page=2\",\"results\":[" + in2026 + "]}");
        } else if (q.contains("filing_year=2025")) {
            send(ex, 200, "{\"count\":1,\"next\":null,\"results\":[" + filing("Q4", "fourth_quarter", 2025, "2026-01-20T10:00:00-05:00", 1,
                    "LOCKHEED MARTIN CORPORATION", null, "4000000.00", "TAX") + "]}");
        } else {
            send(ex, 200, "{\"count\":0,\"next\":null,\"results\":[]}");
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
    void spec_keyIsOptionalAndSecret() {
        assertThat(lda.spec().keyless()).isTrue();
        assertThat(lda.spec().fields()).singleElement().satisfies(f -> {
            assertThat(f.secret()).isTrue();
            assertThat(f.optional()).isTrue();
        });
    }

    @Test
    void test_worksAnonymously_sendsTheTokenWhenGiven_andABadKeyIsAuth() throws Exception {
        assertThat(lda.test(ctx(null))).contains("anonymous");
        assertThat(auth.get(0)).isEmpty();
        assertThat(lda.test(ctx(KEY))).contains("with your API key");
        assertThat(auth.get(1)).isEqualTo("Token " + KEY);
        assertThatThrownBy(() -> lda.test(ctx("wrong")))
                .isInstanceOf(ConnectorException.class).extracting("kind").isEqualTo(ConnectorException.Kind.AUTH);
    }

    @Test
    @SuppressWarnings("unchecked")
    void filings_newestFirst_reportsOnly_withPostedTimeAndAmount() throws Exception {
        Map<String, Object> out = (Map<String, Object>) lda.filings(ctx(null), om.readTree("{\"client\":\"Lockheed\",\"issue\":\"NDAA\"}"));
        assertThat(requests.get(0)).contains("client_name=Lockheed").contains("filing_year=2026").contains("ordering=-dt_posted")
                .contains("filing_specific_lobbying_issues=NDAA").contains("page_size=25");
        List<Map<String, Object>> rows = (List<Map<String, Object>>) out.get("filings");
        assertThat(rows).hasSize(2);   // the registration is left out by default
        assertThat(rows.get(0)).containsEntry("posted_at", "2026-07-20T16:51:53-04:00").containsEntry("amount", 4180000.0)
                .containsEntry("registrant", "LOCKHEED MARTIN CORPORATION");
        assertThat(rows.get(1)).containsEntry("amount", 80000.0);
        Map<String, Object> act = ((List<Map<String, Object>>) rows.get(0).get("activities")).get(0);
        assertThat(act).containsEntry("code", "DEF").containsEntry("lobbyists", 2);
        assertThat((List<String>) act.get("contacted")).contains("Defense, Dept of (DOD)");

        Map<String, Object> bud = (Map<String, Object>) lda.filings(ctx(null), om.readTree("{\"client\":\"Lockheed\",\"issue_code\":\"bud\"}"));
        assertThat((List<?>) bud.get("filings")).hasSize(1);
        Map<String, Object> regs = (Map<String, Object>) lda.filings(ctx(null), om.readTree("{\"client\":\"Lockheed\",\"kind\":\"registrations\"}"));
        assertThat((List<Map<String, Object>>) regs.get("filings")).singleElement().satisfies(r -> assertThat(r).containsEntry("registrant", "FEROX STRATEGIES"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void spend_pages_keepsTheLatestAmendment_andSplitsByYearAndFirm() throws Exception {
        Map<String, Object> out = (Map<String, Object>) lda.spend(ctx(null), om.readTree("{\"client\":\"Lockheed\"}"));
        assertThat(requests).hasSize(3);   // 2025 (one page) + 2026 (two pages)
        List<Map<String, Object>> years = (List<Map<String, Object>>) out.get("years");
        assertThat(years).extracting(y -> y.get("year")).containsExactly(2025, 2026);
        assertThat(years.get(1)).containsEntry("total", 3500000.0 + 4180000.0 + 80000.0);
        assertThat((Map<String, Object>) years.get(1).get("by_period")).containsExactly(
                Map.entry("first_quarter", 3500000.0), Map.entry("second_quarter", 4260000.0));
        List<Map<String, Object>> firms = (List<Map<String, Object>>) out.get("by_registrant");
        assertThat(firms.get(0)).containsEntry("registrant", "LOCKHEED MARTIN CORPORATION").containsEntry("amount", 11680000.0);
        assertThat(((List<Map<String, Object>>) out.get("top_issues")).get(0)).containsEntry("code", "DEF");
        assertThat(out).containsEntry("reports_counted", 4).containsEntry("truncated", false)
                .containsEntry("latest_posted_at", "2026-07-20T16:51:53-04:00");
    }

    @Test
    void badArguments_areBadRequests() {
        for (String args : List.of("{}", "{\"client\":\"L\"}", "{\"client\":\"Lockheed\",\"year\":2031}",
                "{\"client\":\"Lockheed\",\"issue_code\":\"DEFENSE\"}", "{\"client\":\"Lockheed\",\"period\":\"q5\"}")) {
            assertThatThrownBy(() -> lda.filings(ctx(null), om.readTree(args)))
                    .as(args).isInstanceOf(ConnectorException.class).extracting("kind").isEqualTo(ConnectorException.Kind.BAD_REQUEST);
        }
        assertThat(requests).isEmpty();
    }
}
