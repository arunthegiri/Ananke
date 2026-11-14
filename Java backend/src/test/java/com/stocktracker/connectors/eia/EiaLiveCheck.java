package com.stocktracker.connectors.eia;

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
 * Opt-in live check (CONNECTORS_LIVE=1): every EIA (DEMO_KEY unless EIA_API_KEY is set) tool against the real API, through the
 * production rate limiter. Never runs in the normal suite (not a *Test class).
 */
@EnabledIfEnvironmentVariable(named = "CONNECTORS_LIVE", matches = "1")
class EiaLiveCheck {

    final ObjectMapper om = new ObjectMapper();
    final EiaConnector c = new EiaConnector();
    final RateLimiter limiter = RateLimiter.system();

    ConnectorContext ctx() {
        return new ConnectorContext(c.spec(), k -> Optional.of(Optional.ofNullable(System.getenv("EIA_API_KEY")).orElse("DEMO_KEY")), HttpClient.newHttpClient(), om,
                () -> limiter.acquire(c.spec()));
    }

    @Test
    @SuppressWarnings("unchecked")
    void everyToolAnswersFromTheRealApi() throws Exception {
        System.out.println("test: " + c.test(ctx()));

        Map<String, Object> root = (Map<String, Object>) c.browse(ctx(), om.readTree("{}"));
        System.out.println("browse root: " + root);
        assertThat((List<?>) root.get("routes")).isNotEmpty();
        System.out.println("browse wstk: " + c.browse(ctx(), om.readTree("{\"route\":\"petroleum/stoc/wstk\"}")));
        Map<String, Object> d = (Map<String, Object>) c.data(ctx(), om.readTree("{\"route\":\"petroleum/pri/spt\",\"frequency\":\"daily\",\"facets\":{\"series\":[\"RWTC\"]},\"limit\":2}"));
        System.out.println("data: " + d);
        assertThat((List<?>) d.get("rows")).hasSize(2);
        Map<String, Object> inv = (Map<String, Object>) c.petroleumInventories(ctx(), om.readTree("{\"weeks\":2}"));
        System.out.println("petroleum: " + inv);
        ((List<Map<String, Object>>) inv.get("series")).forEach(s -> assertThat((List<?>) s.get("values")).hasSize(2));
        Map<String, Object> gas = (Map<String, Object>) c.gasStorage(ctx(), om.readTree("{\"weeks\":2}"));
        System.out.println("gas: " + gas);
        ((List<Map<String, Object>>) gas.get("series")).forEach(s -> assertThat((List<?>) s.get("values")).hasSize(2));
        Map<String, Object> spot = (Map<String, Object>) c.spotPrices(ctx(), om.readTree("{\"days\":2}"));
        System.out.println("spot: " + spot);
        ((List<Map<String, Object>>) spot.get("series")).forEach(s -> assertThat((List<?>) s.get("values")).hasSize(2));
    }
}
