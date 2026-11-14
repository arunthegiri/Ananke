package com.stocktracker.connectors.wikipedia;

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
 * Opt-in live check (CONNECTORS_LIVE=1): every Wikipedia tool against the real service, through the
 * production rate limiter. Never runs in the normal suite (not a *Test class).
 */
@EnabledIfEnvironmentVariable(named = "CONNECTORS_LIVE", matches = "1")
class WikipediaLiveCheck {

    static final String CONTACT = "Ananke live check contact@example.com";

    final ObjectMapper om = new ObjectMapper();
    final WikipediaConnector c = new WikipediaConnector();
    final RateLimiter limiter = RateLimiter.system();

    ConnectorContext ctx() {
        return new ConnectorContext(c.spec(), k -> k.equals("contact") ? Optional.of(CONTACT) : Optional.empty(),
                HttpClient.newHttpClient(), om, () -> limiter.acquire(c.spec()));
    }

    @Test
    @SuppressWarnings("unchecked")
    void everyToolAnswersFromTheRealService() throws Exception {
        System.out.println("test: " + c.test(ctx()));
        Map<String, Object> pv = (Map<String, Object>) c.pageviews(ctx(), om.readTree("{\"article\":\"NVIDIA\",\"days\":14,\"max_points\":7}"));
        System.out.println("pageviews: " + pv);
        assertThat(pv.get("article")).isEqualTo("Nvidia");
        Map<String, Object> top = (Map<String, Object>) c.top(ctx(), om.readTree("{\"sort\":\"change\",\"limit\":5}"));
        System.out.println("top movers: " + top);
        assertThat((List<?>) top.get("articles")).hasSize(5);
    }
}
