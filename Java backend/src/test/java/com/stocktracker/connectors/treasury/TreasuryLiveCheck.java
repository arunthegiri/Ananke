package com.stocktracker.connectors.treasury;

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
 * Opt-in live check (CONNECTORS_LIVE=1): every U.S. Treasury tool against the real API, through the
 * production rate limiter. Never runs in the normal suite (not a *Test class).
 */
@EnabledIfEnvironmentVariable(named = "CONNECTORS_LIVE", matches = "1")
class TreasuryLiveCheck {

    final ObjectMapper om = new ObjectMapper();
    final TreasuryConnector c = new TreasuryConnector();
    final RateLimiter limiter = RateLimiter.system();

    ConnectorContext ctx() {
        return new ConnectorContext(c.spec(), k -> Optional.empty(), HttpClient.newHttpClient(), om,
                () -> limiter.acquire(c.spec()));
    }

    @Test
    @SuppressWarnings("unchecked")
    void everyToolAnswersFromTheRealApi() throws Exception {
        System.out.println("test: " + c.test(ctx()));

        Map<String, Object> curve = (Map<String, Object>) c.yieldCurve(ctx(), om.readTree("{\"limit\":3}"));
        System.out.println("yield_curve: " + curve);
        assertThat((List<?>) curve.get("rows")).isNotEmpty();
        Map<String, Object> real = (Map<String, Object>) c.yieldCurve(ctx(), om.readTree("{\"curve\":\"real\",\"start\":\"2025-12-20\",\"end\":\"2026-01-10\",\"tenors\":[\"10Y\"]}"));
        System.out.println("real (spans years): " + real);
        assertThat((List<?>) real.get("rows")).isNotEmpty();
        Map<String, Object> results = (Map<String, Object>) c.auctions(ctx(), om.readTree("{\"security\":\"note\",\"limit\":3}"));
        System.out.println("auctions: " + results);
        assertThat((List<?>) results.get("auctions")).isNotEmpty();
        System.out.println("upcoming: " + c.auctions(ctx(), om.readTree("{\"status\":\"upcoming\",\"limit\":3}")));
        Map<String, Object> tga = (Map<String, Object>) c.cashBalance(ctx(), om.readTree("{\"days\":3}"));
        System.out.println("cash_balance: " + tga);
        assertThat((List<?>) tga.get("rows")).isNotEmpty();
        Map<String, Object> debt = (Map<String, Object>) c.debt(ctx(), om.readTree("{\"days\":2}"));
        System.out.println("debt: " + debt);
        assertThat((List<?>) debt.get("rows")).hasSize(2);
        Map<String, Object> avg = (Map<String, Object>) c.avgInterestRates(ctx(), om.readTree("{\"months\":1}"));
        System.out.println("avg_interest_rates: " + avg);
        assertThat((List<?>) avg.get("months")).hasSize(1);
    }
}
