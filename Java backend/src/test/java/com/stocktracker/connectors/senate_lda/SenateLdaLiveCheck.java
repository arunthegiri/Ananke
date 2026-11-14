package com.stocktracker.connectors.senate_lda;

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
 * Opt-in live check (CONNECTORS_LIVE=1): every Lobbying Disclosure tool against the real API, keyless, through the
 * production rate limiter. Never runs in the normal suite (not a *Test class).
 */
@EnabledIfEnvironmentVariable(named = "CONNECTORS_LIVE", matches = "1")
class SenateLdaLiveCheck {

    final ObjectMapper om = new ObjectMapper();
    final SenateLdaConnector c = new SenateLdaConnector();
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
        Map<String, Object> f = call("filings", c.filings(ctx(), om.readTree("{\"client\":\"Lockheed\",\"limit\":2}")));
        assertThat((List<?>) f.get("filings")).isNotEmpty();
        Map<String, Object> s = call("spend", c.spend(ctx(), om.readTree("{\"client\":\"Palantir\",\"years\":1}")));
        assertThat((List<?>) s.get("years")).isNotEmpty();
    }
}
