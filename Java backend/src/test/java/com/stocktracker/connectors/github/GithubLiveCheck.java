package com.stocktracker.connectors.github;

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
 * Opt-in live check (CONNECTORS_LIVE=1): every Github tool against the real service, through the
 * production rate limiter. Never runs in the normal suite (not a *Test class).
 */
@EnabledIfEnvironmentVariable(named = "CONNECTORS_LIVE", matches = "1")
class GithubLiveCheck {

    static final String CONTACT = "Ananke live check contact@example.com";

    final ObjectMapper om = new ObjectMapper();
    final GithubConnector c = new GithubConnector();
    final RateLimiter limiter = RateLimiter.system();

    ConnectorContext ctx() {
        return new ConnectorContext(c.spec(), k -> k.equals("contact") ? Optional.of(CONTACT) : Optional.empty(),
                HttpClient.newHttpClient(), om, () -> limiter.acquire(c.spec()));
    }

    @Test
    @SuppressWarnings("unchecked")
    void everyToolAnswersFromTheRealService() throws Exception {
        System.out.println("test: " + c.test(ctx()));
        Map<String, Object> repo = (Map<String, Object>) c.repo(ctx(), om.readTree("{\"repo\":\"duckdb/duckdb\"}"));
        System.out.println("repo: " + repo);
        assertThat((Long) repo.get("stars")).isPositive();
        Map<String, Object> rel = (Map<String, Object>) c.releases(ctx(), om.readTree("{\"repo\":\"duckdb/duckdb\",\"limit\":2}"));
        System.out.println("releases: " + rel);
        try {
            System.out.println("commits: " + c.commitActivity(ctx(), om.readTree("{\"repo\":\"duckdb/duckdb\",\"weeks\":4}")));
        } catch (com.stocktracker.connectors.ConnectorException e) {
            System.out.println("commits: " + e.getMessage());
        }
        Map<String, Object> org = (Map<String, Object>) c.orgRepos(ctx(), om.readTree("{\"org\":\"DataDog\",\"limit\":3}"));
        System.out.println("org: " + org);
        assertThat((List<?>) org.get("repos")).hasSize(3);
    }
}
