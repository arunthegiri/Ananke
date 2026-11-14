package com.stocktracker.connectors.alpha_vantage;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stocktracker.connectors.ConnectorContext;
import com.stocktracker.connectors.ConnectorException;
import com.stocktracker.connectors.ConnectorException.Kind;
import com.stocktracker.connectors.ConnectorTool;
import com.stocktracker.connectors.remote.FakeRestServer;
import com.stocktracker.connectors.remote.FakeRestServer.Response;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Alpha Vantage against a fake /query, with bodies shaped like the real (demo-key) answers. */
class AlphaVantageConnectorTest {

    static final String KEY = "AVKEY123";
    static final String RATE = """
            {"Information": "Thank you for using Alpha Vantage! Please consider spreading out your free API requests more sparingly (1 request per second). You may subscribe to any of the premium plans at https://www.alphavantage.co/premium/ to lift the free key rate limit (25 requests per day), raise the per-second burst limit, and instantly unlock all premium endpoints"}""";

    final ObjectMapper om = new ObjectMapper();
    final AtomicInteger permits = new AtomicInteger();
    FakeRestServer server;
    AlphaVantageConnector av;

    @BeforeEach
    void start() throws Exception {
        server = new FakeRestServer();
        server.route = r -> switch (r.query().getOrDefault("function", "")) {
            case "MARKET_STATUS" -> Response.ok("""
                    {"endpoint":"Global Market Open & Close Status","markets":[{"market_type":"Equity","region":"United States",
                     "primary_exchanges":"NASDAQ, NYSE, AMEX, BATS","local_open":"09:30","local_close":"16:15","current_status":"closed","notes":""}]}""");
            case "NEWS_SENTIMENT" -> Response.ok("""
                    {"items":"2","sentiment_score_definition":"x <= -0.35: Bearish","relevance_score_definition":"0 < x <= 1","feed":[
                     {"title":"What's Going On With Skyworks Solutions (SWKS)?","url":"https://simplywall.st/x","time_published":"20261114T121802",
                      "authors":["Al Bentley"],"summary":"Skyworks Solutions (SWKS) is gaining attention.","banner_image":null,"source":"Simply Wall St",
                      "category_within_source":"General","source_domain":"Simply Wall St",
                      "topics":[{"topic":"earnings","relevance_score":"0.905841"},{"topic":"technology","relevance_score":"0.836922"}],
                      "overall_sentiment_score":0.061377,"overall_sentiment_label":"Neutral",
                      "ticker_sentiment":[{"ticker":"SWKS","relevance_score":"1.000000","ticker_sentiment_score":"-0.081937","ticker_sentiment_label":"Neutral"},
                                          {"ticker":"AAPL","relevance_score":"0.622498","ticker_sentiment_score":"0.136619","ticker_sentiment_label":"Neutral"}]},
                     {"title":"Second","url":"https://fool.com/y","time_published":"20261114T102028","summary":"s","source":"The Motley Fool",
                      "topics":[],"overall_sentiment_score":0.21387,"overall_sentiment_label":"Somewhat-Bullish","ticker_sentiment":[]}]}""");
            case "EARNINGS_CALL_TRANSCRIPT" -> Response.ok("""
                    {"symbol":"IBM","quarter":"2024Q1","transcript":[
                     {"speaker":"Olympia McNerney","title":"Global Head of Investor Relations","content":"%s","sentiment":"0.6"},
                     {"speaker":"Arvind Krishna","title":"CEO","content":"%s","sentiment":"0.7"},
                     {"speaker":"James Kavanaugh","title":"CFO","content":"Thank you, Arvind.","sentiment":"0.5"}]}"""
                    .formatted("a".repeat(1500), "b".repeat(1500)));
            case "INSIDER_TRANSACTIONS" -> Response.ok("""
                    {"data":[
                     {"transaction_date":"2026-03-01","ticker":"IBM","executive":"OLD, ONE","executive_title":"Director","security_type":"Common Stock","acquisition_or_disposal":"D","shares":"100.0","share_price":"200.0"},
                     {"transaction_date":"2026-09-30","ticker":"IBM","executive":"MIEBACH, MICHAEL","executive_title":"Director","security_type":"Promised Fee Share","acquisition_or_disposal":"A","shares":"415.0","share_price":"219.93"}]}""");
            case "CPI" -> Response.ok("""
                    {"name":"Consumer Price Index for all Urban Consumers","interval":"monthly","unit":"index 1982-1984=100",
                     "data":[{"date":"2026-10-01","value":"337.511"},{"date":"2026-09-01","value":"336.9"},{"date":"2026-08-01","value":"."}]}""");
            case "EARNINGS" -> Response.ok(RATE);
            case "OVERVIEW" -> Response.ok("""
                    {"Information": "Thank you for using Alpha Vantage! This is a premium endpoint. You may subscribe to any of the premium plans at https://www.alphavantage.co/premium/ to instantly unlock all premium endpoints"}""");
            case "EARNINGS_ESTIMATES" -> Response.ok("""
                    {"Error Message": "the parameter apikey is invalid or missing. Please claim your free API key on (https://www.alphavantage.co/support/#api-key). It should take less than 20 seconds."}""");
            default -> Response.ok("{\"Error Message\": \"Invalid API call. Please retry or visit the documentation (https://www.alphavantage.co/documentation/) for TIME_SERIES_DAILY.\"}");
        };
        av = new AlphaVantageConnector(Clock.fixed(Instant.parse("2026-11-14T12:00:00Z"), ZoneOffset.UTC), server.url());
    }

    @AfterEach
    void stop() {
        server.close();
    }

    ConnectorContext ctx(String key) {
        return new ConnectorContext(av.spec(), k -> k.equals("api_key") ? Optional.ofNullable(key) : Optional.empty(),
                HttpClient.newHttpClient(), om, permits::incrementAndGet);
    }

    @Test
    void test_isOneCall_withTheKeyAsQueryParam() throws Exception {
        assertThat(av.test(ctx(KEY))).contains("1 markets");
        assertThat(server.requests).hasSize(1);
        assertThat(server.last().query()).containsEntry("apikey", KEY).containsEntry("function", "MARKET_STATUS");
        assertThat(permits.get()).isEqualTo(1);
        assertThat(av.tools()).extracting(ConnectorTool::name).allMatch(n -> n.startsWith("alpha_vantage_") && n.matches("[a-z0-9_]{1,64}"));
    }

    @Test
    void twoHundredWithErrorBodies_mapToKinds() {
        assertThatThrownBy(() -> av.estimates(ctx(KEY), om.readTree("{\"symbol\":\"IBM\"}")))
                .isInstanceOfSatisfying(ConnectorException.class, e -> assertThat(e.kind()).isEqualTo(Kind.AUTH))
                .hasMessageNotContaining(KEY);
        assertThatThrownBy(() -> av.earnings(ctx(KEY), om.readTree("{\"symbol\":\"IBM\"}")))
                .isInstanceOfSatisfying(ConnectorException.class, e -> assertThat(e.kind()).isEqualTo(Kind.RATE_LIMITED));
        assertThatThrownBy(() -> av.overview(ctx(KEY), om.readTree("{\"symbol\":\"IBM\"}")))
                .isInstanceOfSatisfying(ConnectorException.class, e -> assertThat(e.kind()).isEqualTo(Kind.AUTH))
                .hasMessageContaining("premium");
        assertThatThrownBy(() -> AlphaVantageConnector.check(om.readTree(
                "{\"Information\":\"The **demo** API key is for demo purposes only. Please claim your free API key\"}")))
                .isInstanceOfSatisfying(ConnectorException.class, e -> assertThat(e.kind()).isEqualTo(Kind.AUTH));
        assertThatThrownBy(() -> AlphaVantageConnector.check(om.readTree("{\"Error Message\":\"Invalid API call.\"}")))
                .isInstanceOfSatisfying(ConnectorException.class, e -> assertThat(e.kind()).isEqualTo(Kind.BAD_REQUEST));
        assertThatThrownBy(() -> av.test(ctx(null)))
                .isInstanceOfSatisfying(ConnectorException.class, e -> assertThat(e.kind()).isEqualTo(Kind.NOT_LINKED));
    }

    @Test
    @SuppressWarnings("unchecked")
    void news_carriesPublicationTime_andOnlyRequestedTickerSentiment() throws Exception {
        Map<String, Object> out = (Map<String, Object>) av.news(ctx(KEY), om.readTree(
                "{\"tickers\":[\"aapl\"],\"topics\":[\"earnings\"],\"time_from\":\"2026-11-01\",\"limit\":1}"));
        assertThat(server.last().query()).containsEntry("tickers", "AAPL").containsEntry("topics", "earnings")
                .containsEntry("time_from", "20261101T0000").containsEntry("limit", "1");
        List<Map<String, Object>> a = (List<Map<String, Object>>) out.get("articles");
        assertThat(a).hasSize(1);
        assertThat(a.get(0)).containsEntry("published", "2026-11-14T12:18:02").containsEntry("sentiment", 0.061377)
                .containsEntry("topics", List.of("earnings", "technology"));
        assertThat((List<Map<String, Object>>) a.get(0).get("tickers")).singleElement()
                .satisfies(t -> assertThat(t).containsEntry("ticker", "AAPL").containsEntry("sentiment", 0.136619));

        assertThatThrownBy(() -> av.news(ctx(KEY), om.readTree("{\"topics\":[\"sports\"]}")))
                .isInstanceOfSatisfying(ConnectorException.class, e -> assertThat(e.kind()).isEqualTo(Kind.BAD_REQUEST));
    }

    @Test
    @SuppressWarnings("unchecked")
    void transcript_isCutToMaxChars() throws Exception {
        Map<String, Object> out = (Map<String, Object>) av.transcript(ctx(KEY), om.readTree(
                "{\"symbol\":\"ibm\",\"quarter\":\"2024q1\",\"max_chars\":2000}"));
        assertThat(server.last().query()).containsEntry("symbol", "IBM").containsEntry("quarter", "2024Q1");
        assertThat(out).containsEntry("truncated", true).containsEntry("turns_total", 3).containsEntry("turns_returned", 2);
        List<Map<String, Object>> turns = (List<Map<String, Object>>) out.get("turns");
        assertThat(turns.get(0)).containsEntry("speaker", "Olympia McNerney").containsEntry("sentiment", 0.6);
        assertThatThrownBy(() -> av.transcript(ctx(KEY), om.readTree("{\"symbol\":\"IBM\",\"quarter\":\"Q1 2024\"}")))
                .isInstanceOf(ConnectorException.class);
    }

    @Test
    @SuppressWarnings("unchecked")
    void insiders_defaultToOneYear_newestFirst() throws Exception {
        Map<String, Object> out = (Map<String, Object>) av.insiders(ctx(KEY), om.readTree("{\"symbol\":\"IBM\"}"));
        assertThat(server.last().query()).containsEntry("from", "2025-11-14");
        List<Map<String, Object>> rows = (List<Map<String, Object>>) out.get("transactions");
        assertThat(rows.get(0)).containsEntry("transaction_date", "2026-09-30").containsEntry("shares", 415.0)
                .containsEntry("acquired_or_disposed", "A");
    }

    @Test
    @SuppressWarnings("unchecked")
    void indicator_validatesInterval_andParsesValues() throws Exception {
        Map<String, Object> out = (Map<String, Object>) av.indicator(ctx(KEY), om.readTree("{\"indicator\":\"cpi\",\"interval\":\"monthly\",\"limit\":3}"));
        List<Map<String, Object>> data = (List<Map<String, Object>>) out.get("data");
        assertThat(data).hasSize(3);
        assertThat(data.get(0)).containsEntry("date", "2026-10-01").containsEntry("value", 337.511);
        assertThat(data.get(2).get("value")).isNull();
        int before = server.requests.size();
        assertThatThrownBy(() -> av.indicator(ctx(KEY), om.readTree("{\"indicator\":\"CPI\",\"interval\":\"daily\"}")))
                .isInstanceOfSatisfying(ConnectorException.class, e -> assertThat(e.kind()).isEqualTo(Kind.BAD_REQUEST));
        assertThatThrownBy(() -> av.indicator(ctx(KEY), om.readTree("{\"indicator\":\"UNEMPLOYMENT\",\"interval\":\"monthly\"}")))
                .isInstanceOf(ConnectorException.class);
        assertThat(server.requests).hasSize(before);
    }
}
