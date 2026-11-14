package com.stocktracker.connectors.gdelt;

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

/** GDELT DOC 2.0 connector against a fake GDELT (responses shaped like the real API's). */
class GdeltConnectorTest {

    final ObjectMapper om = new ObjectMapper();
    final List<String> queries = new CopyOnWriteArrayList<>();
    HttpServer server;
    GdeltConnector gdelt;
    volatile String nextBody;
    volatile int nextStatus = 200;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.start();
        gdelt = new GdeltConnector("http://127.0.0.1:" + server.getAddress().getPort() + "/api/v2/doc/doc");
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    ConnectorContext ctx() {
        return new ConnectorContext(gdelt.spec(), k -> Optional.empty(), HttpClient.newHttpClient(), om, () -> { });
    }

    void handle(HttpExchange ex) throws IOException {
        queries.add(URLDecoder.decode(ex.getRequestURI().getRawQuery(), StandardCharsets.UTF_8));
        String q = ex.getRequestURI().getRawQuery();
        String body = nextBody != null ? nextBody
                : q.contains("mode=artlist") ? ARTLIST
                : q.contains("mode=timelinetone") ? TONE
                : VOLUME;
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        ex.sendResponseHeaders(nextStatus, b.length);
        ex.getResponseBody().write(b);
        ex.close();
    }

    static final String ARTLIST = """
            {"articles": [ { "url": "https://www.dailygazette.com/news/national/nvidia-stock-crash.html", "url_mobile": "",
              "title": "Finance guru shares golden investing rule amid Nvidia stock crash", "seendate": "20261113T180000Z",
              "socialimage": "https://example.com/x.jpg", "domain": "dailygazette.com", "language": "English", "sourcecountry": "United States" },
             { "url": "https://www.finanznachrichten.de/nachrichten-2026-11/70128043.htm", "url_mobile": "",
              "title": "Bitcoin Falls Below $69 , 000 After Nvidia CEO Exit . Why ? ", "seendate": "20261113T174500Z",
              "socialimage": "", "domain": "finanznachrichten.de", "language": "English", "sourcecountry": "Germany" } ] }""";

    static final String VOLUME = """
            {"query_details": {"title": "nvidia", "date_resolution": "15m"}, "timeline": [ { "series": "Volume Intensity", "data": [
             { "date": "20261112T003000Z", "value": 1.0 },{ "date": "20261112T004500Z", "value": 1.0 },
             { "date": "20261112T010000Z", "value": 4.0 },{ "date": "20261112T011500Z", "value": 2.0 }] }] }""";

    static final String TONE = """
            {"query_details": {"title": "nvidia", "date_resolution": "15m"}, "timeline": [ { "series": "Average Tone", "data": [
             { "date": "20261112T003000Z", "value": -1.5 },{ "date": "20261112T004500Z", "value": -6.25 },{ "date": "20261112T010000Z", "value": 2.0 }] }] }""";

    @Test
    void spec_isKeylessNews_withGdeltsOneRequestPer5sLimit() {
        assertThat(gdelt.spec().keyless()).isTrue();
        assertThat(gdelt.spec().limits().get(0).permits()).isEqualTo(1);
        assertThat(gdelt.spec().limits().get(0).window().getSeconds()).isGreaterThanOrEqualTo(5);
        assertThat(gdelt.tools()).allSatisfy(t -> assertThat(t.name()).startsWith("gdelt_"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void articles_carrySeenAtAsIsoInstant_andPassToneAndSort() throws Exception {
        Map<String, Object> out = (Map<String, Object>) gdelt.articles(ctx(), om.readTree(
                "{\"query\":\"Nvidia\",\"tone\":\"negative\",\"sort\":\"most_negative\",\"english_only\":true,\"limit\":1}"));
        List<Map<String, Object>> rows = (List<Map<String, Object>>) out.get("articles");
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0)).containsEntry("seen_at", "2026-11-13T18:00:00Z")
                .containsEntry("domain", "dailygazette.com").containsEntry("source_country", "United States");
        assertThat(queries.get(0)).contains("query=Nvidia tone<-5 sourcelang:english", "mode=artlist", "sort=toneasc",
                "maxrecords=1", "timespan=1d", "format=json");
    }

    @Test
    @SuppressWarnings("unchecked")
    void volumeTimeline_reportsPeakAndLatestVersusAverage() throws Exception {
        Map<String, Object> out = (Map<String, Object>) gdelt.timeline(ctx(), om.readTree("{\"query\":\"nvidia\",\"timespan\":\"1m\"}"));
        assertThat(out).containsEntry("resolution", "15m").containsEntry("average", 2.0).containsEntry("latest_vs_average", 1.0);
        assertThat((Map<String, Object>) out.get("peak")).containsEntry("t", "2026-11-12T01:00:00Z").containsEntry("v", 4.0);
        assertThat(queries.get(0)).contains("mode=timelinevol", "timespan=1months");
    }

    @Test
    @SuppressWarnings("unchecked")
    void toneTimeline_peakIsTheMostExtremeTone() throws Exception {
        Map<String, Object> out = (Map<String, Object>) gdelt.timeline(ctx(), om.readTree("{\"query\":\"nvidia\",\"measure\":\"tone\"}"));
        assertThat((Map<String, Object>) out.get("peak")).containsEntry("v", -6.25);
        assertThat(out).doesNotContainKey("latest_vs_average");
    }

    @Test
    void badArguments_areBadRequest_beforeAnyCall() {
        assertThatThrownBy(() -> gdelt.articles(ctx(), om.readTree("{}")))
                .isInstanceOf(ConnectorException.class).extracting("kind").isEqualTo(ConnectorException.Kind.BAD_REQUEST);
        assertThatThrownBy(() -> gdelt.articles(ctx(), om.readTree("{\"query\":\"ab\"}")))
                .isInstanceOf(ConnectorException.class).extracting("kind").isEqualTo(ConnectorException.Kind.BAD_REQUEST);
        assertThatThrownBy(() -> gdelt.timeline(ctx(), om.readTree("{\"query\":\"nvidia\",\"timespan\":\"5y\"}")))
                .isInstanceOf(ConnectorException.class).extracting("kind").isEqualTo(ConnectorException.Kind.BAD_REQUEST);
        assertThat(queries).isEmpty();
    }

    @Test
    void plainTextAnswers_mapToBadRequestOrRateLimited() {
        nextBody = "The specified phrase is too short.";
        assertThatThrownBy(() -> gdelt.articles(ctx(), om.readTree("{\"query\":\"abc\"}")))
                .isInstanceOf(ConnectorException.class).hasMessageContaining("too short")
                .extracting("kind").isEqualTo(ConnectorException.Kind.BAD_REQUEST);
        nextBody = "Please limit requests to one every 5 seconds or contact kalev.leetaru5@gmail.com for larger queries.";
        assertThatThrownBy(() -> gdelt.articles(ctx(), om.readTree("{\"query\":\"abc\"}")))
                .extracting("kind").isEqualTo(ConnectorException.Kind.RATE_LIMITED);
        nextStatus = 429;
        assertThatThrownBy(() -> gdelt.articles(ctx(), om.readTree("{\"query\":\"abc\"}")))
                .extracting("kind").isEqualTo(ConnectorException.Kind.RATE_LIMITED);
    }

    @Test
    @SuppressWarnings("unchecked")
    void emptyAnswer_isNoArticles() throws Exception {
        nextBody = "{}";
        Map<String, Object> out = (Map<String, Object>) gdelt.articles(ctx(), om.readTree("{\"query\":\"zzzqqq\"}"));
        assertThat(out).containsEntry("count", 0);
        assertThat(gdelt.test(ctx())).contains("0 articles");
    }
}
