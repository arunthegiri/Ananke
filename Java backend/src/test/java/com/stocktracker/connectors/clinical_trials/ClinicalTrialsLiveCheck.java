package com.stocktracker.connectors.clinical_trials;

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
 * Opt-in live check (CONNECTORS_LIVE=1): every ClinicalTrials.gov tool against the real API, keyless, through the
 * production rate limiter. Never runs in the normal suite (not a *Test class).
 */
@EnabledIfEnvironmentVariable(named = "CONNECTORS_LIVE", matches = "1")
class ClinicalTrialsLiveCheck {

    final ObjectMapper om = new ObjectMapper();
    final ClinicalTrialsConnector c = new ClinicalTrialsConnector();
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
        Map<String, Object> s = call("search", c.search(ctx(), om.readTree("{\"sponsor\":\"Vertex\",\"phases\":[\"PHASE3\"],\"limit\":3}")));
        List<Map<String, Object>> studies = (List<Map<String, Object>>) s.get("studies");
        assertThat(studies).isNotEmpty();
        Map<String, Object> r = call("readouts", c.readouts(ctx(), om.readTree("{\"sponsor\":\"Eli Lilly\",\"limit\":5}")));
        assertThat((List<?>) r.get("studies")).isNotEmpty();
        call("study", c.study(ctx(), om.readTree("{\"nct_id\":\"" + studies.get(0).get("nct_id") + "\"}")));
    }
}
