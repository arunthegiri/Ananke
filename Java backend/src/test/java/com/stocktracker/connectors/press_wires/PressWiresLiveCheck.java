package com.stocktracker.connectors.press_wires;

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
 * Opt-in live check (CONNECTORS_LIVE=1): every PressWires tool against the real service, through the
 * production rate limiter. Never runs in the normal suite (not a *Test class).
 */
@EnabledIfEnvironmentVariable(named = "CONNECTORS_LIVE", matches = "1")
class PressWiresLiveCheck {

    static final String CONTACT = "Ananke live check contact@example.com";

    final ObjectMapper om = new ObjectMapper();
    final PressWiresConnector c = new PressWiresConnector();
    final RateLimiter limiter = RateLimiter.system();

    ConnectorContext ctx() {
        return new ConnectorContext(c.spec(), k -> k.equals("contact") ? Optional.of(CONTACT) : Optional.empty(),
                HttpClient.newHttpClient(), om, () -> limiter.acquire(c.spec()));
    }

    @Test
    @SuppressWarnings("unchecked")
    void everyToolAnswersFromTheRealService() throws Exception {
        System.out.println("test: " + c.test(ctx()));
        Map<String, Object> out = (Map<String, Object>) c.latest(ctx(), om.readTree("{\"limit\":8}"));
        System.out.println("latest: " + out);
        assertThat(out).doesNotContainKey("unavailable");
        assertThat((List<?>) out.get("items")).hasSize(8);
        for (String s : List.of("prnewswire", "businesswire", "fed", "sec")) {
            Map<String, Object> one = (Map<String, Object>) c.latest(ctx(), om.readTree("{\"sources\":[\"" + s + "\"],\"limit\":2}"));
            System.out.println(s + ": " + one);
            List<Map<String, Object>> items = (List<Map<String, Object>>) one.get("items");
            assertThat(items).isNotEmpty().allSatisfy(i -> assertThat(String.valueOf(i.get("published_at"))).matches("\\d{4}-.*Z"));
        }
        Map<String, Object> hits = (Map<String, Object>) c.search(ctx(), om.readTree("{\"terms\":[\"Inc.\",\"announces\"],\"limit\":3}"));
        System.out.println("search: " + hits);
    }
}
