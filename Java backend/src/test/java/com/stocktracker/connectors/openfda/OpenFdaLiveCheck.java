package com.stocktracker.connectors.openfda;

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
 * Opt-in live check (CONNECTORS_LIVE=1): every openFDA tool against the real API, keyless, through the
 * production rate limiter. Never runs in the normal suite (not a *Test class).
 */
@EnabledIfEnvironmentVariable(named = "CONNECTORS_LIVE", matches = "1")
class OpenFdaLiveCheck {

    final ObjectMapper om = new ObjectMapper();
    final OpenFdaConnector c = new OpenFdaConnector();
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
        Map<String, Object> a = call("approvals", c.approvals(ctx(), om.readTree("{\"company\":\"Eli Lilly\",\"since\":\"2025-01-01\",\"limit\":5}")));
        assertThat((List<?>) a.get("approvals")).isNotEmpty();
        call("approvals (drug)", c.approvals(ctx(), om.readTree("{\"drug\":\"Trikafta\",\"since\":\"2015-01-01\",\"limit\":3}")));
        Map<String, Object> r = call("recalls", c.recalls(ctx(), om.readTree("{\"company\":\"Pfizer\",\"limit\":3}")));
        assertThat(r.get("count")).isNotNull();
        Map<String, Object> e = call("adverse_events", c.adverseEvents(ctx(), om.readTree("{\"drug\":\"Mounjaro\",\"since\":\"2026-01-01\",\"limit\":5}")));
        assertThat((Long) e.get("reports")).isPositive();
        call("adverse_events (company)", c.adverseEvents(ctx(), om.readTree("{\"company\":\"Eli Lilly\",\"since\":\"2026-06-01\",\"limit\":3}")));
    }
}
