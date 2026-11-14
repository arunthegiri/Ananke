package com.stocktracker.connectors.bea;

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

/** BEA connector against a fake BEA API (shapes from BEA's API user guide and live error responses). */
class BeaConnectorTest {

    static final String KEY = "11111111-2222-3333-4444-555555555555";

    final ObjectMapper om = new ObjectMapper();
    final List<String> queries = new CopyOnWriteArrayList<>();
    HttpServer server;
    BeaConnector bea;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.start();
        bea = new BeaConnector("http://127.0.0.1:" + server.getAddress().getPort() + "/api/data",
                Clock.fixed(Instant.parse("2026-11-14T15:00:00Z"), ZoneOffset.UTC));
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    ConnectorContext ctx(String key) {
        return new ConnectorContext(bea.spec(), k -> k.equals("api_key") ? Optional.ofNullable(key) : Optional.empty(),
                HttpClient.newHttpClient(), om, null);
    }

    void handle(HttpExchange ex) throws IOException {
        String q = URLDecoder.decode(ex.getRequestURI().getRawQuery() == null ? "" : ex.getRequestURI().getRawQuery(), StandardCharsets.UTF_8);
        queries.add(q);
        if (!q.contains("UserID=" + KEY)) {
            send(ex, """
                    {"BEAAPI":{"Request":{"RequestParam":[{"ParameterName":"USERID","ParameterValue":"BAD"}]},
                     "Results":{"Error":{"APIErrorCode":"1","APIErrorDescription":"Invalid Request - Invalid API UserId."}}}}""");
        } else if (q.contains("method=GETDATASETLIST")) {
            send(ex, """
                    {"BEAAPI":{"Results":{"Dataset":[{"DatasetName":"NIPA","DatasetDescription":"Standard NIPA tables"},
                     {"DatasetName":"GDPbyIndustry","DatasetDescription":"GDP by Industry"}]}}}""");
        } else if (q.contains("method=GetParameterValues")) {
            send(ex, """
                    {"BEAAPI":{"Results":{"ParamValue":[
                     {"TableName":"T10101","Description":"Table 1.1.1. Percent Change From Preceding Period in Real Gross Domestic Product (A) (Q)"},
                     {"TableName":"T20804","Description":"Table 2.8.4. Price Indexes for Personal Consumption Expenditures by Major Type of Product, Monthly (M)"},
                     {"TableName":"T61600D","Description":"Table 6.16D. Corporate Profits by Industry (A) (Q)"}]}}}""");
        } else if (q.contains("TableName=T99999")) {
            send(ex, """
                    {"BEAAPI":{"Results":{"Error":{"APIErrorCode":"201","APIErrorDescription":"Error retrieving NIPA/Fixed Assets data.",
                     "ErrorDetail":{"Description":"Invalid Table Name: T99999"}}}}}""");
        } else {
            send(ex, """
                    {"BEAAPI":{"Results":{"Statistic":"NIPA Table","UTCProductionTime":"2026-11-14T15:02:11.250",
                     "Dimensions":[{"Ordinal":"1","Name":"TableName","DataType":"string","IsValue":"0"}],
                     "Data":[
                      {"TableName":"T10101","SeriesCode":"A191RL","LineNumber":"1","LineDescription":"Gross domestic product","TimePeriod":"2026Q2","METRIC_NAME":"Fisher Quantity Index","CL_UNIT":"Percent change, annual rate","UNIT_MULT":"0","DataValue":"3.1"},
                      {"TableName":"T10101","SeriesCode":"A191RL","LineNumber":"1","LineDescription":"Gross domestic product","TimePeriod":"2026Q3","METRIC_NAME":"Fisher Quantity Index","CL_UNIT":"Percent change, annual rate","UNIT_MULT":"0","DataValue":"2.4"},
                      {"TableName":"T10101","SeriesCode":"A191RL","LineNumber":"1","LineDescription":"Gross domestic product","TimePeriod":"2025Q4","METRIC_NAME":"Fisher Quantity Index","CL_UNIT":"Percent change, annual rate","UNIT_MULT":"0","DataValue":"1,234.5"},
                      {"TableName":"T10101","SeriesCode":"DPCERL","LineNumber":"2","LineDescription":"Personal consumption expenditures","TimePeriod":"2026Q3","METRIC_NAME":"Fisher Quantity Index","CL_UNIT":"Percent change, annual rate","UNIT_MULT":"0","DataValue":"2.0"}],
                     "Notes":[{"NoteRef":"T10101","NoteText":"Table 1.1.1. Percent Change From Preceding Period in Real Gross Domestic Product - LastRevised: October 29, 2026"},
                              {"NoteRef":"T10101.1","NoteText":"Next Release Date: November 25, 2026"}]}}}""");
        }
    }

    void send(HttpExchange ex, String body) throws IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(200, b.length);
        ex.getResponseBody().write(b);
        ex.close();
    }

    @Test
    void badUserId_isAuth_eventThoughBeaAnswers200() {
        assertThatThrownBy(() -> bea.test(ctx("not-the-key")))
                .isInstanceOf(ConnectorException.class)
                .satisfies(e -> assertThat(((ConnectorException) e).kind()).isEqualTo(ConnectorException.Kind.AUTH));
        assertThat(bea.test(ctx(KEY))).contains("2 datasets");
    }

    @Test
    @SuppressWarnings("unchecked")
    void tables_filterByAllWords() throws Exception {
        Map<String, Object> out = (Map<String, Object>) bea.tables(ctx(KEY), om.readTree("{\"query\":\"price PERSONAL\"}"));
        assertThat((List<Map<String, Object>>) out.get("tables")).singleElement()
                .satisfies(t -> assertThat(t).containsEntry("table", "T20804"));
        assertThat(queries.get(0)).contains("DataSetName=NIPA").contains("ParameterName=TableName").contains("ResultFormat=JSON");
    }

    @Test
    @SuppressWarnings("unchecked")
    void data_pivotsByLine_newestPeriodFirst_withRevisionDates() throws Exception {
        Map<String, Object> out = (Map<String, Object>) bea.data(ctx(KEY), om.readTree("{\"table\":\"t10101\",\"periods\":2}"));
        assertThat(queries.get(0)).contains("TableName=T10101").contains("Frequency=Q").contains("Year=2024,2025,2026");
        assertThat((List<String>) out.get("periods")).containsExactly("2026Q3", "2026Q2");
        List<Map<String, Object>> lines = (List<Map<String, Object>>) out.get("lines");
        assertThat(lines).hasSize(2);
        assertThat(lines.get(0)).containsEntry("description", "Gross domestic product").containsEntry("unit", "Percent change, annual rate");
        assertThat((Map<String, Object>) lines.get(0).get("values")).containsExactly(Map.entry("2026Q3", 2.4), Map.entry("2026Q2", 3.1));
        assertThat(out).containsEntry("last_revised", "October 29, 2026").containsEntry("next_release", "November 25, 2026")
                .containsEntry("produced_at_utc", "2026-11-14T15:02:11.250");
        assertThat(out.get("title")).asString().startsWith("Table 1.1.1.").doesNotContain("LastRevised");

        Map<String, Object> one = (Map<String, Object>) bea.data(ctx(KEY), om.readTree("{\"table\":\"T10101\",\"lines\":[\"2\"]}"));
        assertThat((List<?>) one.get("lines")).hasSize(1);
    }

    @Test
    void data_errors() throws Exception {
        assertThatThrownBy(() -> bea.data(ctx(KEY), om.readTree("{\"table\":\"../x\"}"))).hasMessageContaining("NIPA table id");
        assertThatThrownBy(() -> bea.data(ctx(KEY), om.readTree("{\"table\":\"T10101\",\"frequency\":\"W\"}"))).hasMessageContaining("frequency");
        assertThat(queries).isEmpty();
        assertThatThrownBy(() -> bea.data(ctx(KEY), om.readTree("{\"table\":\"T99999\"}")))
                .hasMessageContaining("Invalid Table Name: T99999")
                .satisfies(e -> assertThat(((ConnectorException) e).kind()).isEqualTo(ConnectorException.Kind.BAD_REQUEST));
    }

    @Test
    void monthlyPeriodsSortNumerically() {
        assertThat(BeaConnector.comparePeriods("2026M9", "2026M10")).isNegative();
        assertThat(BeaConnector.comparePeriods("2025Q4", "2026Q1")).isNegative();
        assertThat(BeaConnector.num("1,234.5")).isEqualTo(1234.5);
        assertThat(BeaConnector.num("(NA)")).isNull();
    }
}
