package com.stocktracker.connectors.kalshi;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stocktracker.connectors.ConnectorContext;
import com.stocktracker.connectors.RateLimiter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.net.http.HttpClient;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Opt-in live check (CONNECTORS_LIVE=1): every Kalshi tool against the real API, keyless, through the
 * production rate limiter. Never runs in the normal suite (not a *Test class).
 */
@EnabledIfEnvironmentVariable(named = "CONNECTORS_LIVE", matches = "1")
class KalshiLiveCheck {

    final ObjectMapper om = new ObjectMapper();
    final KalshiConnector c = new KalshiConnector();
    final RateLimiter limiter = RateLimiter.system();

    ConnectorContext ctx() {
        return new ConnectorContext(c.spec(), k -> Optional.empty(), HttpClient.newHttpClient(), om, () -> limiter.acquire(c.spec()));
    }

    @SuppressWarnings("unchecked")
    Map<String, Object> call(String label, Object result) {
        String s = String.valueOf(result);
        System.out.println(label + ": " + (s.length() > 1500 ? s.substring(0, 1500) + "…" : s));
        return (Map<String, Object>) result;
    }

    @Test
    void everyToolAnswersFromTheRealApi() throws Exception {
        System.out.println("test: " + c.test(ctx()));
        Map<String, Object> s = call("search", c.search(ctx(), om.readTree("{\"query\":\"fed rate\",\"category\":\"Economics\",\"limit\":5}")));
        assertThat((List<?>) s.get("series")).isNotEmpty();
        Map<String, Object> e = call("events", c.events(ctx(), om.readTree("{\"series_ticker\":\"KXFED\",\"limit\":2,\"markets_per_event\":5}")));
        List<Map<String, Object>> events = (List<Map<String, Object>>) e.get("events");
        assertThat(events).isNotEmpty();
        String ticker = (String) ((List<Map<String, Object>>) events.get(0).get("markets")).get(0).get("ticker");
        call("market", c.market(ctx(), om.readTree("{\"ticker\":\"" + ticker + "\"}")));
        Map<String, Object> h = call("history", c.history(ctx(), om.readTree("{\"ticker\":\"" + ticker + "\",\"days\":10}")));
        assertThat((List<?>) h.get("candles")).isNotEmpty();
    }
}
