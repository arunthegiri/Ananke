package com.stocktracker.connectors.cftc_cot;

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
 * Opt-in live check (CONNECTORS_LIVE=1): every CFTC COT tool against the real API, through the
 * production rate limiter. Never runs in the normal suite (not a *Test class).
 */
@EnabledIfEnvironmentVariable(named = "CONNECTORS_LIVE", matches = "1")
class CftcCotLiveCheck {

    final ObjectMapper om = new ObjectMapper();
    final CftcCotConnector c = new CftcCotConnector();
    final RateLimiter limiter = RateLimiter.system();

    ConnectorContext ctx() {
        return new ConnectorContext(c.spec(), k -> Optional.empty(), HttpClient.newHttpClient(), om,
                () -> limiter.acquire(c.spec()));
    }

    @Test
    @SuppressWarnings("unchecked")
    void everyToolAnswersFromTheRealApi() throws Exception {
        System.out.println("test: " + c.test(ctx()));

        Map<String, Object> m = (Map<String, Object>) c.markets(ctx(), om.readTree("{\"query\":\"gold\",\"limit\":5}"));
        System.out.println("markets: " + m);
        assertThat((List<?>) m.get("markets")).isNotEmpty();
        Map<String, Object> gold = (Map<String, Object>) c.report(ctx(), om.readTree("{\"market\":\"gold\",\"report\":\"disaggregated\",\"weeks\":2}"));
        System.out.println("disaggregated gold: " + gold);
        assertThat(gold.get("code")).isEqualTo("088691");
        Map<String, Object> es = (Map<String, Object>) c.report(ctx(), om.readTree("{\"market\":\"13874A\",\"report\":\"tff\",\"combined\":true,\"weeks\":2}"));
        System.out.println("tff e-mini: " + es);
        assertThat((List<?>) es.get("weeks")).hasSize(2);
        Map<String, Object> wti = (Map<String, Object>) c.report(ctx(), om.readTree("{\"market\":\"067651\",\"weeks\":1}"));
        System.out.println("legacy wti: " + wti);
        assertThat((List<?>) wti.get("weeks")).hasSize(1);
    }
}
