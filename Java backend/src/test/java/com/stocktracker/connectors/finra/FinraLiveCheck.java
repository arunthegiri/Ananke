package com.stocktracker.connectors.finra;

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
 * Opt-in live check (CONNECTORS_LIVE=1): every FINRA tool against the real API, through the
 * production rate limiter. Never runs in the normal suite (not a *Test class).
 */
@EnabledIfEnvironmentVariable(named = "CONNECTORS_LIVE", matches = "1")
class FinraLiveCheck {

    final ObjectMapper om = new ObjectMapper();
    final FinraConnector c = new FinraConnector();
    final RateLimiter limiter = RateLimiter.system();

    ConnectorContext ctx() {
        return new ConnectorContext(c.spec(), k -> Optional.empty(), HttpClient.newHttpClient(), om,
                () -> limiter.acquire(c.spec()));
    }

    @Test
    @SuppressWarnings("unchecked")
    void everyToolAnswersFromTheRealApi() throws Exception {
        System.out.println("test: " + c.test(ctx()));

        Map<String, Object> v = (Map<String, Object>) c.shortVolume(ctx(), om.readTree("{\"ticker\":\"BRK.B\",\"days\":3}"));
        System.out.println("short_volume: " + v);
        assertThat((List<?>) v.get("days")).hasSize(3);
        Map<String, Object> mkt = (Map<String, Object>) c.shortVolumeMarket(ctx(), om.readTree("{\"limit\":5}"));
        System.out.println("short_volume_market: " + mkt);
        assertThat((List<?>) mkt.get("rows")).hasSize(5);
        Map<String, Object> picked = (Map<String, Object>) c.shortVolumeMarket(ctx(), om.readTree("{\"tickers\":[\"AAPL\",\"BRK.B\"]}"));
        System.out.println("short_volume_market picked: " + picked);
        assertThat((List<?>) picked.get("rows")).hasSize(2);
        Map<String, Object> si = (Map<String, Object>) c.shortInterest(ctx(), om.readTree("{\"ticker\":\"BRK.B\",\"periods\":3}"));
        System.out.println("short_interest: " + si);
        assertThat((List<?>) si.get("periods")).hasSize(3);
    }
}
