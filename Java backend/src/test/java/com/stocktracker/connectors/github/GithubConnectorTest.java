package com.stocktracker.connectors.github;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stocktracker.connectors.ConnectorContext;
import com.stocktracker.connectors.ConnectorException;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.StringJoiner;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** GitHub connector against a fake GitHub REST API (payloads shaped like api.github.com's). */
class GithubConnectorTest {

    static final Instant NOW = Instant.parse("2026-11-14T20:00:00Z");

    final ObjectMapper om = new ObjectMapper();
    final List<String> calls = new CopyOnWriteArrayList<>();
    final List<String> auths = new CopyOnWriteArrayList<>();
    final Map<String, String> fields = new HashMap<>();
    HttpServer server;
    GithubConnector gh;
    volatile boolean computing;
    volatile boolean exhausted;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.start();
        gh = new GithubConnector(Clock.fixed(NOW, ZoneOffset.UTC), "http://127.0.0.1:" + server.getAddress().getPort());
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    ConnectorContext ctx() {
        return new ConnectorContext(gh.spec(), k -> Optional.ofNullable(fields.get(k)), HttpClient.newHttpClient(), om, () -> { });
    }

    void handle(HttpExchange ex) throws IOException {
        String p = ex.getRequestURI().getPath();
        String q = ex.getRequestURI().getRawQuery();
        calls.add(p + (q == null ? "" : "?" + URLDecoder.decode(q, StandardCharsets.UTF_8)) + " accept=" + ex.getRequestHeaders().getFirst("Accept"));
        String auth = ex.getRequestHeaders().getFirst("Authorization");
        auths.add(String.valueOf(auth));
        if (auth != null && !auth.equals("Bearer good")) {
            send(ex, 401, "{\"message\":\"Bad credentials\",\"documentation_url\":\"https://docs.github.com/rest\",\"status\":\"401\"}");
            return;
        }
        if (exhausted) {
            send(ex, 403, "{\"message\":\"API rate limit exceeded for 1.2.3.4. (But here's the good news: Authenticated requests get a higher rate limit.)\"}");
            return;
        }
        switch (p) {
            case "/rate_limit" -> send(ex, 200, "{\"resources\":{\"core\":{\"limit\":" + (auth == null ? 60 : 5000) + ",\"remaining\":59}}}");
            case "/repos/vercel/next.js" -> send(ex, 200, """
                    {"full_name":"vercel/next.js","description":"The React Framework","stargazers_count":250,"forks_count":32804,
                     "subscribers_count":1620,"open_issues_count":3976,"language":"JavaScript","license":{"key":"mit","spdx_id":"MIT"},
                     "topics":["react","nextjs"],"created_at":"2016-10-05T23:32:51Z","pushed_at":"2026-11-14T17:05:24Z",
                     "archived":false,"fork":false,"homepage":"https://nextjs.org"}""");
            case "/repos/vercel/next.js/releases" -> send(ex, 200, """
                    [{"tag_name":"v16.5.0-canary.50","name":"v16.5.0-canary.50","published_at":"2026-11-14T08:36:50Z","prerelease":true,"draft":false,
                      "html_url":"https://github.com/vercel/next.js/releases/tag/v16.5.0-canary.50","body":"### Core Changes\\n\\n- fix: something"},
                     {"tag_name":"v16.4.0","name":"v16.4.0","published_at":"2026-11-01T10:00:00Z","prerelease":false,"draft":false,
                      "html_url":"https://github.com/vercel/next.js/releases/tag/v16.4.0","body":""}]""");
            case "/repos/vercel/next.js/stats/commit_activity" -> {
                if (computing) {
                    send(ex, 202, "{}");
                } else {
                    StringJoiner j = new StringJoiner(",", "[", "]");
                    long week = Instant.parse("2025-11-16T00:00:00Z").getEpochSecond();
                    for (int i = 0; i < 52; i++) j.add("{\"days\":[0,0,0,0,0,0,0],\"total\":" + (i >= 48 ? 200 : 100) + ",\"week\":" + (week + i * 604800L) + "}");
                    send(ex, 200, j.toString());
                }
            }
            case "/repos/vercel/next.js/stargazers" -> {
                if (auth == null) {
                    send(ex, 401, "{\"message\":\"Requires authentication\"}");
                } else {
                    send(ex, 200, """
                            [{"starred_at":"2026-11-12T00:00:00Z","user":{"login":"a"}},{"starred_at":"2026-11-13T00:00:00Z","user":{"login":"b"}},
                             {"starred_at":"2026-11-14T00:00:00Z","user":{"login":"c"}},{"starred_at":"2026-11-14T12:00:00Z","user":{"login":"d"}}]""");
                }
            }
            case "/search/repositories" -> send(ex, 200, """
                    {"total_count":593,"incomplete_results":false,"items":[
                      {"full_name":"DataDog/datadog-agent","stargazers_count":3200,"forks_count":1300,"language":"Go","pushed_at":"2026-11-14T10:00:00Z","archived":false,"description":"Main repository for Datadog Agent"}]}""");
            default -> send(ex, 404, "{\"message\":\"Not Found\"}");
        }
    }

    void send(HttpExchange ex, int status, String body) throws IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(status, b.length);
        ex.getResponseBody().write(b);
        ex.close();
    }

    @Test
    @SuppressWarnings("unchecked")
    void repo_snapshot_includesTheLatestRelease_andAcceptsGithubUrls() throws Exception {
        Map<String, Object> out = (Map<String, Object>) gh.repo(ctx(), om.readTree("{\"repo\":\"https://github.com/vercel/next.js/\"}"));
        assertThat(out).containsEntry("repo", "vercel/next.js").containsEntry("stars", 250L).containsEntry("forks", 32804L)
                .containsEntry("watchers", 1620L).containsEntry("license", "MIT").containsEntry("pushed_at", "2026-11-14T17:05:24Z");
        assertThat((Map<String, Object>) out.get("latest_release")).containsEntry("tag", "v16.5.0-canary.50")
                .containsEntry("published_at", "2026-11-14T08:36:50Z");
        assertThat(auths).containsOnly("null");   // keyless by default
        assertThat(calls.get(0)).endsWith("accept=application/vnd.github+json");
    }

    @Test
    @SuppressWarnings("unchecked")
    void commitActivity_reportsMomentum_andComputingIsAnUncachedError() throws Exception {
        Map<String, Object> out = (Map<String, Object>) gh.commitActivity(ctx(), om.readTree("{\"repo\":\"vercel/next.js\",\"weeks\":8}"));
        assertThat(out).containsEntry("avg_last_4_weeks", 200.0).containsEntry("avg_prior_12_weeks", 100.0)
                .containsEntry("momentum", 2.0).containsEntry("total_52_weeks", 5600);
        List<Map<String, Object>> weeks = (List<Map<String, Object>>) out.get("weeks");
        assertThat(weeks).hasSize(8);
        assertThat(weeks.get(7)).containsEntry("week_start", "2026-11-08");

        computing = true;
        assertThatThrownBy(() -> gh.commitActivity(ctx(), om.readTree("{\"repo\":\"vercel/next.js\"}")))
                .hasMessageContaining("still computing").extracting("kind").isEqualTo(ConnectorException.Kind.UPSTREAM);
    }

    @Test
    @SuppressWarnings("unchecked")
    void releases_canSkipPrereleases() throws Exception {
        Map<String, Object> out = (Map<String, Object>) gh.releases(ctx(), om.readTree("{\"repo\":\"vercel/next.js\",\"include_prereleases\":false}"));
        assertThat((List<Map<String, Object>>) out.get("releases")).singleElement()
                .satisfies(r -> assertThat(r).containsEntry("tag", "v16.4.0").containsEntry("published_at", "2026-11-01T10:00:00Z").doesNotContainKey("notes_excerpt"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void starVelocity_needsAToken_andReadsTheLastStargazerPage() throws Exception {
        assertThatThrownBy(() -> gh.starVelocity(ctx(), om.readTree("{\"repo\":\"vercel/next.js\"}")))
                .extracting("kind").isEqualTo(ConnectorException.Kind.NOT_LINKED);
        fields.put("token", "good");
        Map<String, Object> out = (Map<String, Object>) gh.starVelocity(ctx(), om.readTree("{\"repo\":\"vercel/next.js\"}"));
        assertThat(out).containsEntry("available", true).containsEntry("sample", 4).containsEntry("stars_per_day", 1.6)
                .containsEntry("from", "2026-11-12T00:00:00Z").containsEntry("days_since_last_star", 0.33);
        assertThat(calls).anyMatch(c -> c.startsWith("/repos/vercel/next.js/stargazers?per_page=100&page=3")
                && c.endsWith("accept=application/vnd.github.star+json"));
        assertThat(auths).contains("Bearer good");
    }

    @Test
    @SuppressWarnings("unchecked")
    void orgRepos_searchesByStars() throws Exception {
        Map<String, Object> out = (Map<String, Object>) gh.orgRepos(ctx(), om.readTree("{\"org\":\"DataDog\",\"limit\":5}"));
        assertThat(out).containsEntry("public_repos", 593L);
        assertThat((List<Map<String, Object>>) out.get("repos")).singleElement().satisfies(r -> assertThat(r).containsEntry("stars", 3200L));
        assertThat(calls.get(0)).startsWith("/search/repositories?q=user:DataDog fork:false&sort=stars&order=desc&per_page=5");
    }

    @Test
    void badToken_isAuth_butRateLimitIsRateLimited() throws Exception {
        fields.put("token", "bad");
        assertThatThrownBy(() -> gh.test(ctx())).extracting("kind").isEqualTo(ConnectorException.Kind.AUTH);
        fields.remove("token");
        assertThat(gh.test(ctx())).contains("no token").contains("59/60");
        exhausted = true;
        assertThatThrownBy(() -> gh.repo(ctx(), om.readTree("{\"repo\":\"vercel/next.js\"}")))
                .hasMessageContaining("60 requests/hour").extracting("kind").isEqualTo(ConnectorException.Kind.RATE_LIMITED);
    }

    @Test
    void badArgs_areBadRequest() {
        assertThatThrownBy(() -> gh.repo(ctx(), om.readTree("{\"repo\":\"just-a-name\"}")))
                .extracting("kind").isEqualTo(ConnectorException.Kind.BAD_REQUEST);
        assertThatThrownBy(() -> gh.repo(ctx(), om.readTree("{\"repo\":\"../../etc/passwd\"}")))
                .extracting("kind").isEqualTo(ConnectorException.Kind.BAD_REQUEST);
        assertThatThrownBy(() -> gh.orgRepos(ctx(), om.readTree("{\"org\":\"bad org\"}")))
                .extracting("kind").isEqualTo(ConnectorException.Kind.BAD_REQUEST);
        assertThat(calls).isEmpty();
    }
}
