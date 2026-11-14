package com.stocktracker.connectors.massive;

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
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Massive against a fake API with bodies from its documented sample responses. */
class MassiveConnectorTest {

    static final String KEY = "massive-key";

    final ObjectMapper om = new ObjectMapper();
    final AtomicInteger permits = new AtomicInteger();
    FakeRestServer server;
    MassiveConnector massive;

    @BeforeEach
    void start() throws Exception {
        server = new FakeRestServer("Authorization");
        server.route = r -> {
            if (!("Bearer " + KEY).equals(r.headers().get("Authorization"))) {
                return new Response(401, "{\"status\":\"ERROR\",\"request_id\":\"96eb6d37\",\"error\":\"Unknown API Key\"}");
            }
            if (r.path().equals("/v3/reference/tickers/AAPL")) return Response.ok("""
                    {"request_id":"31d59dda","status":"OK","results":{"ticker":"AAPL","name":"Apple Inc.","market":"stocks","locale":"us",
                     "primary_exchange":"XNAS","type":"CS","active":true,"currency_name":"usd","cik":"0000320193","composite_figi":"BBG000B9XRY4",
                     "market_cap":3.8e12,"sic_code":"3571","sic_description":"ELECTRONIC COMPUTERS","list_date":"1980-12-12",
                     "address":{"address1":"ONE APPLE PARK WAY","city":"CUPERTINO","state":"CA"},"description":"Apple designs…",
                     "branding":{"logo_url":"https://api.massive.com/x.svg"},"total_employees":166000}}""");
            if (r.path().startsWith("/v2/aggs/ticker/AAPL/range/1/day/2026-11-03/2026-11-07")) return Response.ok("""
                    {"ticker":"AAPL","adjusted":true,"queryCount":3,"resultsCount":3,"status":"OK","request_id":"x","results":[
                     {"v":5.1e7,"vw":254.1,"o":253.0,"c":255.0,"h":256.2,"l":252.1,"t":1762146000000,"n":610000},
                     {"v":4.9e7,"vw":255.5,"o":255.2,"c":256.0,"h":257.0,"l":254.0,"t":1762232400000,"n":590000},
                     {"v":4.0e7,"vw":256.4,"o":256.1,"c":257.3,"h":258.0,"l":255.5,"t":1762318800000,"n":540000}]}""");
            if (r.path().equals("/v2/reference/news")) return Response.ok("""
                    {"count":1,"next_url":"https://api.massive.com:443/v2/reference/news?cursor=abc","request_id":"831afdb0","status":"OK","results":[
                     {"amp_url":"https://m.uk.investing.com/x","article_url":"https://uk.investing.com/x","author":"Sam Boughedda",
                      "description":"UBS analysts warn that markets are underestimating the extent of future interest rate cuts.",
                      "id":"8ec638","insights":[{"sentiment":"positive","sentiment_reasoning":"UBS analysts are providing a bullish outlook.","ticker":"UBS"}],
                      "keywords":["Federal Reserve","interest rates"],"published_utc":"2024-06-24T18:33:53Z",
                      "publisher":{"name":"Investing.com","homepage_url":"https://www.investing.com/"},"tickers":["UBS"],
                      "title":"Markets are underestimating Fed cuts: UBS"}]}""");
            if (r.path().equals("/v2/snapshot/locale/us/markets/stocks/tickers/AAPL")) return new Response(403,
                    "{\"status\":\"NOT_AUTHORIZED\",\"request_id\":\"x\",\"message\":\"You are not entitled to this data. Please upgrade your plan at https://massive.com/pricing\"}");
            if (r.path().equals("/v2/snapshot/locale/us/markets/stocks/tickers/MSFT")) return Response.ok("""
                    {"request_id":"657e430f","status":"OK","ticker":{"day":{"c":120.4229,"h":120.53,"l":118.81,"o":119.62,"v":28727868,"vw":119.725},
                     "lastQuote":{"P":120.47,"S":4,"p":120.46,"s":8,"t":1605195918507251700},
                     "lastTrade":{"c":[14,41],"i":"4046","p":120.47,"s":236,"t":1605195918306274000,"x":10},
                     "min":{"c":120.4201,"h":120.468,"l":120.37,"n":762,"o":120.435,"t":1684428720000,"v":270796,"vw":120.4129},
                     "prevDay":{"c":119.49,"h":119.63,"l":116.44,"o":117.19,"v":110597265,"vw":118.4998},
                     "ticker":"MSFT","todaysChange":0.98,"todaysChangePerc":0.82,"updated":1605195918306274000}}""");
            return new Response(404, "{\"status\":\"NOT_FOUND\"}");
        };
        massive = new MassiveConnector(server.url());
    }

    @AfterEach
    void stop() {
        server.close();
    }

    ConnectorContext ctx(String key) {
        return new ConnectorContext(massive.spec(), k -> k.equals("api_key") ? Optional.ofNullable(key) : Optional.empty(),
                HttpClient.newHttpClient(), om, permits::incrementAndGet);
    }

    @Test
    void test_bearerHeader_noKeyInUrl_badKeyIsAuth() {
        assertThat(massive.test(ctx(KEY))).contains("Apple Inc.").contains("XNAS");
        assertThat(server.last().query()).isEmpty();
        assertThat(permits.get()).isEqualTo(1);
        assertThatThrownBy(() -> massive.test(ctx("nope")))
                .isInstanceOfSatisfying(ConnectorException.class, e -> assertThat(e.kind()).isEqualTo(Kind.AUTH))
                .hasMessageContaining("Unknown API Key").hasMessageNotContaining("nope");
        assertThat(massive.tools()).extracting(ConnectorTool::name).allMatch(n -> n.startsWith("massive_"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void aggregates_keepNewestBars_withUtcTimes() throws Exception {
        Map<String, Object> out = (Map<String, Object>) massive.aggregates(ctx(KEY),
                om.readTree("{\"ticker\":\"aapl\",\"from\":\"2026-11-03\",\"to\":\"2026-11-07\",\"bars\":2}"));
        assertThat(server.last().query()).containsEntry("adjusted", "true").containsEntry("sort", "asc");
        List<Map<String, Object>> bars = (List<Map<String, Object>>) out.get("bars");
        assertThat(bars).hasSize(2);
        assertThat(bars.get(1)).containsEntry("t", "2025-11-05T05:00:00Z").containsEntry("c", 257.3);
        assertThat(out).containsEntry("bars_total", 3).containsEntry("partial", false);
        assertThatThrownBy(() -> massive.aggregates(ctx(KEY), om.readTree("{\"ticker\":\"AAPL\",\"from\":\"2026-11-07\",\"to\":\"2026-11-03\"}")))
                .isInstanceOfSatisfying(ConnectorException.class, e -> assertThat(e.kind()).isEqualTo(Kind.BAD_REQUEST));
    }

    @Test
    @SuppressWarnings("unchecked")
    void news_carriesPublishedUtcAndInsights() throws Exception {
        Map<String, Object> out = (Map<String, Object>) massive.news(ctx(KEY), om.readTree("{\"ticker\":\"UBS\",\"published_since\":\"2024-06-01\",\"limit\":5}"));
        assertThat(server.last().query()).containsEntry("published_utc.gte", "2024-06-01").containsEntry("order", "desc")
                .containsEntry("sort", "published_utc").containsEntry("limit", "5");
        Map<String, Object> a = ((List<Map<String, Object>>) out.get("articles")).get(0);
        assertThat(a).containsEntry("published_utc", "2024-06-24T18:33:53Z").containsEntry("publisher", "Investing.com");
        assertThat((List<Map<String, Object>>) a.get("insights")).singleElement().satisfies(i -> assertThat(i).containsEntry("sentiment", "positive"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void snapshot_convertsNanosecondTimes_andPlanGateIsAuthWithMassivesMessage() throws Exception {
        Map<String, Object> out = (Map<String, Object>) massive.snapshot(ctx(KEY), om.readTree("{\"ticker\":\"MSFT\"}"));
        assertThat(out).containsEntry("updated", "2020-11-12T15:45:18.306274Z").containsEntry("change_pct", 0.82);
        assertThat((Map<String, Object>) out.get("last_quote")).containsEntry("bid", 120.46).containsEntry("ask", 120.47);
        assertThatThrownBy(() -> massive.snapshot(ctx(KEY), om.readTree("{\"ticker\":\"AAPL\"}")))
                .isInstanceOfSatisfying(ConnectorException.class, e -> assertThat(e.kind()).isEqualTo(Kind.AUTH))
                .hasMessageContaining("not entitled");
    }
}
