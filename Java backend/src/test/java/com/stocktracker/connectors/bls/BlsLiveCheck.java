package com.stocktracker.connectors.bls;

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
 * Opt-in live check (CONNECTORS_LIVE=1): every BLS tool against the real API, through the
 * production rate limiter. Never runs in the normal suite (not a *Test class).
 */
@EnabledIfEnvironmentVariable(named = "CONNECTORS_LIVE", matches = "1")
class BlsLiveCheck {

    final ObjectMapper om = new ObjectMapper();
    final BlsConnector c = new BlsConnector();
    final RateLimiter limiter = RateLimiter.system();

    ConnectorContext ctx() {
        return new ConnectorContext(c.spec(), k -> Optional.ofNullable(System.getenv("BLS_API_KEY")), HttpClient.newHttpClient(), om,
                () -> limiter.acquire(c.spec()));
    }

    @Test
    @SuppressWarnings("unchecked")
    void everyToolAnswersFromTheRealApi() throws Exception {
        System.out.println("test: " + c.test(ctx()));

        Map<String, Object> out = (Map<String, Object>) c.series(ctx(), om.readTree("{\"series_ids\":[\"CUSR0000SA0\",\"CES0000000001\",\"LNS14000000\"],\"limit\":3}"));
        System.out.println("series: " + out);
        List<Map<String, Object>> s = (List<Map<String, Object>>) out.get("series");
        assertThat(s).hasSize(3);
        assertThat((List<?>) s.get(0).get("observations")).hasSize(3);
    }
}
