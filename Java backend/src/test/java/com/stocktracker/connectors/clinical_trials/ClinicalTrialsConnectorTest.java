package com.stocktracker.connectors.clinical_trials;

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

/** ClinicalTrials.gov connector against a fake API v2 (responses shaped like recorded live ones). */
class ClinicalTrialsConnectorTest {

    static final String STUDY = """
            {"protocolSection":{"identificationModule":{"nctId":"NCT07232719","briefTitle":"A Study of Retatrutide (LY3437943) in Participants With Obesity or Overweight",
              "officialTitle":"A Phase 3b Study to Investigate the Efficacy and Safety of Retatrutide","acronym":"TRIUMPH-8"},
             "statusModule":{"overallStatus":"ACTIVE_NOT_RECRUITING","startDateStruct":{"date":"2025-11-17"},
              "primaryCompletionDateStruct":{"date":"2027-06","type":"ESTIMATED"},"completionDateStruct":{"date":"2028-06","type":"ESTIMATED"},
              "studyFirstPostDateStruct":{"date":"2025-11-18"},"lastUpdatePostDateStruct":{"date":"2026-11-10"}},
             "sponsorCollaboratorsModule":{"leadSponsor":{"name":"Eli Lilly and Company"}},
             "descriptionModule":{"briefSummary":"The purpose of this study is to evaluate retatrutide."},
             "conditionsModule":{"conditions":["Obesity","Overweight"]},
             "designModule":{"phases":["PHASE3"],"designInfo":{"allocation":"RANDOMIZED","primaryPurpose":"TREATMENT"},"enrollmentInfo":{"count":250,"type":"ESTIMATED"}},
             "armsInterventionsModule":{"interventions":[{"type":"DRUG","name":"Retatrutide"},{"type":"DRUG","name":"Placebo"}]},
             "outcomesModule":{"primaryOutcomes":[{"measure":"Percent Change from Baseline in Body Weight","timeFrame":"Baseline, Week 56"}]}},
             "hasResults":false}""";

    final ObjectMapper om = new ObjectMapper();
    final List<String> requests = new CopyOnWriteArrayList<>();
    HttpServer server;
    ClinicalTrialsConnector ct;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.start();
        ct = new ClinicalTrialsConnector("http://127.0.0.1:" + server.getAddress().getPort() + "/api/v2",
                Clock.fixed(Instant.parse("2026-11-14T18:00:00Z"), ZoneOffset.UTC));
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    ConnectorContext ctx() {
        return new ConnectorContext(ct.spec(), k -> Optional.empty(), HttpClient.newHttpClient(), om, null);
    }

    void handle(HttpExchange ex) throws IOException {
        String p = ex.getRequestURI().getPath();
        String q = ex.getRequestURI().getRawQuery() == null ? "" : URLDecoder.decode(ex.getRequestURI().getRawQuery(), StandardCharsets.UTF_8);
        requests.add(p + "?" + q);
        switch (p) {
            case "/api/v2/version" -> send(ex, 200, "{\"apiVersion\":\"2.0.5\",\"dataTimestamp\":\"2026-11-13T10:00:06\"}");
            case "/api/v2/studies" -> send(ex, 200, "{\"totalCount\":2,\"studies\":[" + STUDY + "],\"nextPageToken\":\"abc\"}");
            case "/api/v2/studies/NCT07232719" -> send(ex, 200, STUDY);
            default -> {
                ex.getResponseHeaders().add("Content-Type", "text/plain");
                send(ex, 404, "NCT number not found");
            }
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
    void test_readsTheVersion() throws Exception {
        assertThat(ct.test(ctx())).contains("2.0.5").contains("2026-11-13T10:00:06");
    }

    @Test
    @SuppressWarnings("unchecked")
    void search_passesFilters_andRowsCarryPostingDates() throws Exception {
        Map<String, Object> out = (Map<String, Object>) ct.search(ctx(), om.readTree(
                "{\"sponsor\":\"Eli Lilly\",\"condition\":\"obesity\",\"phases\":[\"phase 3\"],\"statuses\":\"recruiting, active_not_recruiting\"}"));
        assertThat(requests.get(0)).contains("query.spons=Eli Lilly").contains("query.cond=obesity")
                .contains("filter.overallStatus=RECRUITING,ACTIVE_NOT_RECRUITING").contains("filter.advanced=AREA[Phase](PHASE3)")
                .contains("sort=LastUpdatePostDate:desc").contains("countTotal=true");
        assertThat(out).containsEntry("total", 2);
        Map<String, Object> s = ((List<Map<String, Object>>) out.get("studies")).get(0);
        assertThat(s).containsEntry("nct_id", "NCT07232719").containsEntry("acronym", "TRIUMPH-8").containsEntry("enrollment", 250)
                .containsEntry("primary_completion", "2027-06").containsEntry("primary_completion_type", "ESTIMATED")
                .containsEntry("first_posted", "2025-11-18").containsEntry("last_updated", "2026-11-10")
                .containsEntry("url", "https://clinicaltrials.gov/study/NCT07232719");
        assertThat((List<String>) s.get("interventions")).containsExactly("Retatrutide (drug)", "Placebo (drug)");
    }

    @Test
    @SuppressWarnings("unchecked")
    void readouts_defaultToTheNextSixMonthsOfPhase2And3_soonestFirst() throws Exception {
        Map<String, Object> out = (Map<String, Object>) ct.readouts(ctx(), om.readTree("{\"intervention\":\"retatrutide\"}"));
        assertThat(requests.get(0)).contains("filter.advanced=AREA[PrimaryCompletionDate]RANGE[2026-11-14,2027-05-13] AND AREA[Phase](PHASE2 OR PHASE3)")
                .contains("sort=PrimaryCompletionDate:asc").contains("query.intr=retatrutide");
        assertThat(out).containsEntry("from", "2026-11-14").containsEntry("to", "2027-05-13");
    }

    @Test
    @SuppressWarnings("unchecked")
    void study_detail_andUnknownIdIsBadRequest() throws Exception {
        Map<String, Object> s = (Map<String, Object>) ct.study(ctx(), om.readTree("{\"nct_id\":\"nct07232719\"}"));
        assertThat(s).containsEntry("allocation", "RANDOMIZED").containsEntry("completion_type", "ESTIMATED");
        assertThat(((List<Map<String, Object>>) s.get("primary_outcomes")).get(0)).containsEntry("time_frame", "Baseline, Week 56");
        assertThatThrownBy(() -> ct.study(ctx(), om.readTree("{\"nct_id\":\"NCT99999999\"}")))
                .extracting("kind").isEqualTo(ConnectorException.Kind.BAD_REQUEST);
    }

    @Test
    void badArguments_areBadRequests() {
        for (String args : List.of("{}", "{\"sponsor\":\"x\",\"phases\":[\"PHASE9\"]}", "{\"sponsor\":\"x\",\"statuses\":[\"OPEN\"]}",
                "{\"sponsor\":\"x\",\"sort\":\"random\"}")) {
            assertThatThrownBy(() -> ct.search(ctx(), om.readTree(args)))
                    .as(args).isInstanceOf(ConnectorException.class).extracting("kind").isEqualTo(ConnectorException.Kind.BAD_REQUEST);
        }
        assertThatThrownBy(() -> ct.readouts(ctx(), om.readTree("{\"sponsor\":\"x\",\"from\":\"2026-01-01\",\"to\":\"2030-01-01\"}")))
                .extracting("kind").isEqualTo(ConnectorException.Kind.BAD_REQUEST);
        assertThatThrownBy(() -> ct.study(ctx(), om.readTree("{\"nct_id\":\"12345\"}")))
                .extracting("kind").isEqualTo(ConnectorException.Kind.BAD_REQUEST);
        assertThat(requests).isEmpty();
    }
}
