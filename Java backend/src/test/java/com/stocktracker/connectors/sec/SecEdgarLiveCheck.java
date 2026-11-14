package com.stocktracker.connectors.sec;

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
 * Opt-in live check (SEC_LIVE=1, identity in SEC_IDENTITY): every SEC EDGAR tool against the real
 * EDGAR, through the production rate limiter. Never runs in the normal suite (not a *Test class).
 */
@EnabledIfEnvironmentVariable(named = "SEC_LIVE", matches = "1")
class SecEdgarLiveCheck {

    final ObjectMapper om = new ObjectMapper();
    final SecEdgarConnector sec = new SecEdgarConnector();
    final RateLimiter limiter = RateLimiter.system();
    final String identity = Optional.ofNullable(System.getenv("SEC_IDENTITY")).orElse("Ananke live check contact@example.com");

    ConnectorContext ctx() {
        return new ConnectorContext(sec.spec(), k -> Optional.of(identity), HttpClient.newHttpClient(), om,
                () -> limiter.acquire(sec.spec()));
    }

    @Test
    @SuppressWarnings("unchecked")
    void everyToolAnswersFromTheRealEdgar() throws Exception {
        System.out.println("test: " + sec.test(ctx()));

        Map<String, Object> profile = (Map<String, Object>) sec.companyProfile(ctx(), om.readTree("{\"ticker\":\"NVDA\"}"));
        System.out.println("company: " + profile);
        assertThat(profile.get("name")).asString().containsIgnoringCase("nvidia");

        Map<String, Object> filings = (Map<String, Object>) sec.filings(ctx(), om.readTree("{\"ticker\":\"AAPL\",\"forms\":[\"8-K\",\"10-Q\",\"10-K\"],\"limit\":3}"));
        System.out.println("filings: " + filings);
        assertThat((List<?>) filings.get("filings")).isNotEmpty();

        Map<String, Object> fin = (Map<String, Object>) sec.financials(ctx(), om.readTree("{\"ticker\":\"MSFT\",\"concept\":\"NetIncomeLoss\",\"period\":\"annual\",\"limit\":3}"));
        System.out.println("financials: " + fin);
        assertThat((List<?>) fin.get("values")).isNotEmpty();

        Map<String, Object> ins = (Map<String, Object>) sec.insiderTrades(ctx(), om.readTree("{\"ticker\":\"AAPL\",\"limit\":3}"));
        System.out.println("insiders: " + ins.get("transactions"));
        assertThat(ins.get("filings_read")).isEqualTo(3);

        Map<String, Object> hits = (Map<String, Object>) sec.search(ctx(), om.readTree(
                "{\"query\":\"\\\"tariff\\\"\",\"forms\":[\"8-K\"],\"from\":\"2026-10-01\",\"ticker\":\"AAPL\",\"limit\":3}"));
        System.out.println("search (AAPL only): " + hits);
        Map<String, Object> wide = (Map<String, Object>) sec.search(ctx(), om.readTree(
                "{\"query\":\"\\\"tariff\\\"\",\"forms\":[\"8-K\"],\"from\":\"2026-10-01\",\"limit\":3}"));
        System.out.println("search: " + wide);
        assertThat((List<?>) wide.get("hits")).isNotEmpty();
    }
}
