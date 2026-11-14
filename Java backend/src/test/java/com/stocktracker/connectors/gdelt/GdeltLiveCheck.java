package com.stocktracker.connectors.gdelt;

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
 * Opt-in live check (CONNECTORS_LIVE=1): every Gdelt tool against the real service, through the
 * production rate limiter. Never runs in the normal suite (not a *Test class).
 */
@EnabledIfEnvironmentVariable(named = "CONNECTORS_LIVE", matches = "1")
class GdeltLiveCheck {

    static final String CONTACT = "Ananke live check contact@example.com";

    final ObjectMapper om = new ObjectMapper();
    final GdeltConnector c = new GdeltConnector();
    final RateLimiter limiter = RateLimiter.system();

    ConnectorContext ctx() {
        return new ConnectorContext(c.spec(), k -> k.equals("contact") ? Optional.of(CONTACT) : Optional.empty(),
                HttpClient.newHttpClient(), om, () -> limiter.acquire(c.spec()));
    }

    @Test
    @SuppressWarnings("unchecked")
    void everyToolAnswersFromTheRealService() throws Exception {
        System.out.println("test: " + c.test(ctx()));
        Thread.sleep(7000);
        Map<String, Object> arts = (Map<String, Object>) c.articles(ctx(), om.readTree("{\"query\":\"Nvidia\",\"timespan\":\"3d\",\"limit\":3}"));
        System.out.println("articles: " + arts);
        assertThat((List<?>) arts.get("articles")).isNotEmpty();
        Thread.sleep(7000);
        Map<String, Object> neg = (Map<String, Object>) c.articles(ctx(), om.readTree("{\"query\":\"Nvidia\",\"timespan\":\"1w\",\"tone\":\"negative\",\"english_only\":true,\"limit\":2}"));
        System.out.println("negative: " + neg);
        Thread.sleep(7000);
        Map<String, Object> vol = (Map<String, Object>) c.timeline(ctx(), om.readTree("{\"query\":\"Nvidia\",\"timespan\":\"2d\",\"max_points\":10}"));
        System.out.println("volume: " + vol);
        assertThat((List<?>) vol.get("points")).isNotEmpty();
        Thread.sleep(7000);
        Map<String, Object> tone = (Map<String, Object>) c.timeline(ctx(), om.readTree("{\"query\":\"Nvidia\",\"measure\":\"tone\",\"timespan\":\"1w\",\"max_points\":10}"));
        System.out.println("tone: " + tone);
        assertThat((List<?>) tone.get("points")).isNotEmpty();
    }
}
