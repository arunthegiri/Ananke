package com.stocktracker.connectors.usaspending;

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
 * Opt-in live check (CONNECTORS_LIVE=1): every USAspending tool against the real API, keyless, through the
 * production rate limiter. Never runs in the normal suite (not a *Test class).
 */
@EnabledIfEnvironmentVariable(named = "CONNECTORS_LIVE", matches = "1")
class UsaSpendingLiveCheck {

    final ObjectMapper om = new ObjectMapper();
    final UsaSpendingConnector c = new UsaSpendingConnector();
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
        Map<String, Object> a = call("awards", c.awards(ctx(), om.readTree("{\"recipient\":\"Palantir\",\"limit\":3}")));
        assertThat((List<?>) a.get("awards")).isNotEmpty();
        Map<String, Object> t = call("transactions", c.transactions(ctx(), om.readTree("{\"recipient\":\"Palantir\",\"sort\":\"recent\",\"limit\":3}")));
        assertThat((List<?>) t.get("transactions")).isNotEmpty();
        Map<String, Object> g = call("agencies", c.agencies(ctx(), om.readTree("{\"recipient\":\"Palantir\",\"limit\":3}")));
        assertThat((List<?>) g.get("agencies")).isNotEmpty();
        Map<String, Object> r = call("recipients", c.recipients(ctx(), om.readTree("{\"recipient\":\"Lockheed Martin\",\"limit\":3}")));
        assertThat((List<?>) r.get("recipients")).isNotEmpty();
    }
}
