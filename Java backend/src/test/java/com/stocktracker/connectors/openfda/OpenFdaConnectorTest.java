package com.stocktracker.connectors.openfda;

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

/** openFDA connector against a fake api.fda.gov (responses shaped like recorded live ones). */
class OpenFdaConnectorTest {

    static final String META = "\"meta\":{\"last_updated\":\"2026-11-13\",\"results\":{\"skip\":0,\"limit\":100,\"total\":%d}}";
    static final String NOT_FOUND = "{\"error\":{\"code\":\"NOT_FOUND\",\"message\":\"No matches found!\"}}";

    final ObjectMapper om = new ObjectMapper();
    final List<String> requests = new CopyOnWriteArrayList<>();
    HttpServer server;
    OpenFdaConnector fda;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.start();
        fda = new OpenFdaConnector("http://127.0.0.1:" + server.getAddress().getPort(),
                Clock.fixed(Instant.parse("2026-11-14T18:00:00Z"), ZoneOffset.UTC));
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    ConnectorContext ctx(String key) {
        return new ConnectorContext(fda.spec(), k -> k.equals("api_key") ? Optional.ofNullable(key) : Optional.empty(),
                HttpClient.newHttpClient(), om, null);
    }

    void handle(HttpExchange ex) throws IOException {
        String p = ex.getRequestURI().getPath();
        String q = ex.getRequestURI().getRawQuery() == null ? "" : URLDecoder.decode(ex.getRequestURI().getRawQuery(), StandardCharsets.UTF_8);
        requests.add(p + "?" + q);
        if (q.contains("api_key=bad")) {
            send(ex, 403, "{\"error\":{\"code\":\"API_KEY_INVALID\",\"message\":\"An invalid api_key was supplied.\"}}");
            return;
        }
        if (q.contains("NOBODY")) {
            send(ex, 404, NOT_FOUND);
            return;
        }
        switch (p) {
            case "/drug/drugsfda.json" -> send(ex, 200, "{" + META.formatted(1) + """
                    ,"results":[{"application_number":"NDA217439","sponsor_name":"PFIZER",
                      "openfda":{"brand_name":["TALZENNA"],"generic_name":["TALAZOPARIB"]},
                      "products":[{"brand_name":"TALZENNA"}],
                      "submissions":[
                       {"submission_type":"SUPPL","submission_number":"3","submission_status":"AP","submission_status_date":"20250612",
                        "review_priority":"PRIORITY","submission_class_code":"EFFICACY","submission_class_code_description":"Efficacy"},
                       {"submission_type":"SUPPL","submission_number":"4","submission_status":"AP","submission_status_date":"20261026",
                        "review_priority":"PRIORITY","submission_class_code":"EFFICACY","submission_class_code_description":"Efficacy",
                        "application_docs":[{"url":"https://www.accessdata.fda.gov/label/217439s004lbl.pdf","date":"20261027","type":"Label"},
                                            {"url":"https://www.accessdata.fda.gov/appletter/217439Orig1s004ltr.pdf","date":"20261028","type":"Letter"}]},
                       {"submission_type":"SUPPL","submission_number":"5","submission_status":"AP","submission_status_date":"20260301",
                        "submission_class_code":"LABELING","submission_class_code_description":"Labeling"},
                       {"submission_type":"ORIG","submission_number":"1","submission_status":"AP","submission_status_date":"20240307",
                        "review_priority":"STANDARD","submission_class_code":"TYPE 5"}]}]}""");
            case "/drug/enforcement.json" -> send(ex, 200, "{" + META.formatted(9) + """
                    ,"results":[{"status":"Ongoing","classification":"Class II","recalling_firm":"Pfizer Laboratories Div Pfizer Inc",
                      "product_description":"Levoxyl (levothyroxine sodium) tablets","reason_for_recall":"Subpotent drug",
                      "report_date":"20261022","recall_initiation_date":"20261001","recall_number":"D-0001-2027",
                      "voluntary_mandated":"Voluntary: Firm initiated","distribution_pattern":"Nationwide","product_quantity":"1000 bottles",
                      "openfda":{"brand_name":["LEVOXYL"]}}]}""");
            case "/drug/event.json" -> {
                if (q.contains("count=receivedate")) send(ex, 200, """
                        {"results":[{"time":"20260101","count":17},{"time":"20260102","count":66},{"time":"20260203","count":17}],
                         "meta":{"last_updated":"2026-10-28"}}""");
                else if (q.contains("count=serious")) send(ex, 200, "{\"results\":[{\"term\":1,\"count\":40},{\"term\":2,\"count\":60}]}");
                else send(ex, 200, "{\"results\":[{\"term\":\"NAUSEA\",\"count\":1894},{\"term\":\"DIARRHOEA\",\"count\":1474}]}");
            }
            default -> send(ex, 404, NOT_FOUND);
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
    void test_keyIsOptional_sentWhenGiven_andABadKeyIsAuth() throws Exception {
        assertThat(fda.spec().keyless()).isTrue();
        assertThat(fda.test(ctx(null))).contains("2026-11-13").contains("keyless");
        assertThat(requests.get(0)).doesNotContain("api_key");
        assertThat(fda.test(ctx("goodkey"))).contains("with your API key");
        assertThat(requests.get(1)).contains("api_key=goodkey");
        assertThatThrownBy(() -> fda.test(ctx("bad")))
                .isInstanceOf(ConnectorException.class).extracting("kind").isEqualTo(ConnectorException.Kind.AUTH);
    }

    @Test
    @SuppressWarnings("unchecked")
    void approvals_onlyActionsInTheWindow_newestFirst_withLetterLinks() throws Exception {
        Map<String, Object> out = (Map<String, Object>) fda.approvals(ctx(null), om.readTree("{\"company\":\"Pfizer\"}"));
        String q = requests.get(0);
        assertThat(q).contains("sponsor_name:PFIZER*").contains("openfda.manufacturer_name:\"Pfizer\"")
                .contains("(application_number:NDA* OR application_number:BLA*)")
                .contains("submissions.submission_status_date:[20251114 TO 20261114]");
        List<Map<String, Object>> rows = (List<Map<String, Object>>) out.get("approvals");
        assertThat(rows).extracting(r -> r.get("action_date")).containsExactly("2026-10-26", "2026-03-01");
        assertThat(rows.get(0)).containsEntry("brand", "TALZENNA").containsEntry("class", "Efficacy").containsEntry("docs_posted", "2026-10-27")
                .containsEntry("letter_url", "https://www.accessdata.fda.gov/appletter/217439Orig1s004ltr.pdf");
        assertThat(out).containsEntry("data_updated", "2026-11-13");

        Map<String, Object> eff = (Map<String, Object>) fda.approvals(ctx(null), om.readTree("{\"drug\":\"Talzenna\",\"kind\":\"efficacy\",\"since\":\"2020-01-01\"}"));
        assertThat((List<Map<String, Object>>) eff.get("approvals")).extracting(r -> r.get("submission")).containsExactly("SUPPL 4", "SUPPL 3", "ORIG 1");
    }

    @Test
    @SuppressWarnings("unchecked")
    void approvals_multiWordSponsorsArePrefixMatched_andNoMatchesIsEmpty() throws Exception {
        Map<String, Object> out = (Map<String, Object>) fda.approvals(ctx(null), om.readTree("{\"company\":\"Nobody Bio\"}"));
        assertThat(requests.get(0)).contains("sponsor_name:NOBODY\\ BIO*");
        assertThat(out).containsEntry("count", 0).containsEntry("applications_matched", 0);
    }

    @Test
    @SuppressWarnings("unchecked")
    void recalls_carryReportDates() throws Exception {
        Map<String, Object> out = (Map<String, Object>) fda.recalls(ctx(null), om.readTree("{\"company\":\"Pfizer\",\"since\":\"2025-01-01\"}"));
        assertThat(requests.get(0)).contains("/drug/enforcement.json").contains("recalling_firm:\"Pfizer\"")
                .contains("report_date:[20250101 TO 20261114]").contains("sort=report_date:desc");
        Map<String, Object> r = ((List<Map<String, Object>>) out.get("recalls")).get(0);
        assertThat(r).containsEntry("report_date", "2026-10-22").containsEntry("initiated", "2026-10-01")
                .containsEntry("classification", "Class II").containsEntry("brand", "LEVOXYL");
        assertThat(out).containsEntry("total", 9);
    }

    @Test
    @SuppressWarnings("unchecked")
    void adverseEvents_totalsMonthlyTrendSeriousShareAndTopReactions() throws Exception {
        Map<String, Object> out = (Map<String, Object>) fda.adverseEvents(ctx(null), om.readTree("{\"drug\":\"Mounjaro\",\"since\":\"2026-01-01\"}"));
        assertThat(out).containsEntry("reports", 100L).containsEntry("serious", 40L).containsEntry("serious_share", 0.4)
                .containsEntry("data_updated", "2026-10-28");
        assertThat((Map<String, Long>) out.get("by_month")).containsExactly(Map.entry("2026-01", 83L), Map.entry("2026-02", 17L));
        assertThat(((List<Map<String, Object>>) out.get("top_reactions")).get(0)).containsEntry("reaction", "NAUSEA");
        assertThat(requests.get(0)).contains("patient.drug.openfda.brand_name:\"Mounjaro\"").contains("receivedate:[20260101 TO 20261114]");
    }

    @Test
    void badArguments_areBadRequests() {
        assertThatThrownBy(() -> fda.approvals(ctx(null), om.readTree("{}"))).extracting("kind").isEqualTo(ConnectorException.Kind.BAD_REQUEST);
        assertThatThrownBy(() -> fda.recalls(ctx(null), om.readTree("{\"company\":\"\\\"\\\"\"}"))).extracting("kind").isEqualTo(ConnectorException.Kind.BAD_REQUEST);
        assertThatThrownBy(() -> fda.adverseEvents(ctx(null), om.readTree("{\"drug\":\"x1\",\"since\":\"2015-01-01\"}"))).extracting("kind").isEqualTo(ConnectorException.Kind.BAD_REQUEST);
        assertThatThrownBy(() -> fda.approvals(ctx(null), om.readTree("{\"drug\":\"x1\",\"kind\":\"weird\"}"))).extracting("kind").isEqualTo(ConnectorException.Kind.BAD_REQUEST);
        assertThat(requests).isEmpty();
    }

    @Test
    void queryInjectionIsNeutralised() throws Exception {
        fda.recalls(ctx(null), om.readTree("{\"company\":\"Pfizer\\\" OR recalling_firm:*\"}"));
        assertThat(requests.get(0)).contains("recalling_firm:\"Pfizer OR recalling firm\"");
    }
}
