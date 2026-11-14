package com.stocktracker.connectors.hacker_news;

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
 * Opt-in live check (CONNECTORS_LIVE=1): every HackerNews tool against the real service, through the
 * production rate limiter. Never runs in the normal suite (not a *Test class).
 */
@EnabledIfEnvironmentVariable(named = "CONNECTORS_LIVE", matches = "1")
class HackerNewsLiveCheck {

    static final String CONTACT = "Ananke live check contact@example.com";

    final ObjectMapper om = new ObjectMapper();
    final HackerNewsConnector c = new HackerNewsConnector();
    final RateLimiter limiter = RateLimiter.system();

    ConnectorContext ctx() {
        return new ConnectorContext(c.spec(), k -> k.equals("contact") ? Optional.of(CONTACT) : Optional.empty(),
                HttpClient.newHttpClient(), om, () -> limiter.acquire(c.spec()));
    }

    @Test
    @SuppressWarnings("unchecked")
    void everyToolAnswersFromTheRealService() throws Exception {
        System.out.println("test: " + c.test(ctx()));
        Map<String, Object> s = (Map<String, Object>) c.search(ctx(), om.readTree("{\"query\":\"Nvidia\",\"type\":\"all\",\"days\":7,\"limit\":3}"));
        System.out.println("search: " + s);
        assertThat((List<?>) s.get("hits")).isNotEmpty();
        Map<String, Object> m = (Map<String, Object>) c.mentions(ctx(), om.readTree("{\"query\":\"Nvidia\",\"days\":5}"));
        System.out.println("mentions: " + m);
        assertThat((List<?>) m.get("per_day")).hasSize(5);
    }
}
