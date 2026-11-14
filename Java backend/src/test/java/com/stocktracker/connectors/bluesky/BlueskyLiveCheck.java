package com.stocktracker.connectors.bluesky;

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
 * Opt-in live check (CONNECTORS_LIVE=1): every Bluesky tool against the real service, through the
 * production rate limiter. Never runs in the normal suite (not a *Test class).
 */
@EnabledIfEnvironmentVariable(named = "CONNECTORS_LIVE", matches = "1")
class BlueskyLiveCheck {

    static final String CONTACT = "Ananke live check contact@example.com";

    final ObjectMapper om = new ObjectMapper();
    final BlueskyConnector c = new BlueskyConnector();
    final RateLimiter limiter = RateLimiter.system();

    ConnectorContext ctx() {
        return new ConnectorContext(c.spec(), k -> k.equals("contact") ? Optional.of(CONTACT) : Optional.empty(),
                HttpClient.newHttpClient(), om, () -> limiter.acquire(c.spec()));
    }

    @Test
    @SuppressWarnings("unchecked")
    void everyToolAnswersFromTheRealService() throws Exception {
        System.out.println("test: " + c.test(ctx()));
        Map<String, Object> out = (Map<String, Object>) c.searchPosts(ctx(), om.readTree("{\"query\":\"$NVDA\",\"limit\":3}"));
        System.out.println("search: " + out);
        assertThat((List<?>) out.get("posts")).isNotEmpty();
        Map<String, Object> top = (Map<String, Object>) c.searchPosts(ctx(), om.readTree("{\"query\":\"nvidia\",\"sort\":\"top\",\"lang\":\"en\",\"limit\":2}"));
        System.out.println("top: " + top);
    }
}
