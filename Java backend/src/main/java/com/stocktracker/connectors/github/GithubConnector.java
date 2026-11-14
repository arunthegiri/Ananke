package com.stocktracker.connectors.github;

import com.fasterxml.jackson.databind.JsonNode;
import com.stocktracker.connectors.Connector;
import com.stocktracker.connectors.ConnectorContext;
import com.stocktracker.connectors.ConnectorException;
import com.stocktracker.connectors.ConnectorSpec;
import com.stocktracker.connectors.ConnectorSpec.Field;
import com.stocktracker.connectors.ConnectorSpec.RateLimit;
import com.stocktracker.connectors.ConnectorTool;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.stocktracker.connectors.ToolArgs.bool;
import static com.stocktracker.connectors.ToolArgs.intArg;
import static com.stocktracker.connectors.ToolArgs.integer;
import static com.stocktracker.connectors.ToolArgs.object;
import static com.stocktracker.connectors.ToolArgs.props;
import static com.stocktracker.connectors.ToolArgs.requireStr;
import static com.stocktracker.connectors.ToolArgs.str;
import static com.stocktracker.connectors.ToolArgs.url;

/**
 * GitHub REST API as alternative data for companies with open-source or developer-tool exposure:
 * repository popularity (stars, forks, watchers, open issues), weekly commit activity, releases,
 * recent star velocity and an organisation's most-starred repositories. Works without a token
 * (60 requests/hour); an optional personal access token raises that to 5,000/hour and is required
 * for star velocity (GitHub's stargazer list needs authentication).
 *
 * <p>Point-in-time: repo stats are a snapshot taken at {@code observedAt}; releases carry
 * {@code published_at}; commit weeks their UTC start.
 */
@Component
public class GithubConnector implements Connector {

    static final String ID = "github";
    static final String API_URL = "https://api.github.com";
    private static final String REPO_RE = "^[A-Za-z0-9-]{1,39}/[A-Za-z0-9._-]{1,100}$";

    private static final ConnectorSpec SPEC = new ConnectorSpec(
            ID,
            "GitHub",
            ConnectorSpec.Category.ALT_DATA,
            ConnectorSpec.Licence.FREE_PUBLIC,
            "Open-source traction as alt data: repository stars, forks, watchers and open issues, weekly commit "
                    + "activity, releases, recent star velocity and an organisation's top repositories — for companies "
                    + "whose products are developer tools or open source.",
            "Data from GitHub (github.com) via the GitHub REST API",
            "https://docs.github.com/en/rest",
            List.of(new Field("token", "Personal access token (optional)",
                    "A fine-grained token with public-repository read access only (no scopes). Without one GitHub allows "
                            + "60 requests/hour and star velocity is unavailable.",
                    true, true, "github_pat_…")),
            // Unauthenticated: 60/hour, authenticated: 5,000/hour (search: 10 / 30 per minute). The cap here is the
            // per-minute burst; GitHub's own 403/429 rate-limit answers are mapped to RATE_LIMITED.
            List.of(RateLimit.perMinute(20)));

    private final Clock clock;
    private final String api;

    public GithubConnector() {
        this(Clock.systemUTC(), API_URL);
    }

    /** Test seam: point at a fake GitHub. */
    GithubConnector(Clock clock, String api) {
        this.clock = clock;
        this.api = api;
    }

    @Override
    public ConnectorSpec spec() {
        return SPEC;
    }

    @Override
    public String test(ConnectorContext ctx) {
        JsonNode j = get(ctx, api + "/rate_limit");
        JsonNode core = j.path("resources").path("core");
        String who = ctx.field("token").isPresent() ? "token accepted" : "no token";
        return "Connected — GitHub answered (" + who + "; " + core.path("remaining").asInt() + "/" + core.path("limit").asInt()
                + " requests left this hour).";
    }

    @Override
    public List<ConnectorTool> tools() {
        return List.of(
                new ConnectorTool(ID + "_repo",
                        "Snapshot of a GitHub repository: stars, forks, watchers, open issues + PRs, language, licence, "
                                + "topics, created / last-push times, archived flag, and the latest release.",
                        object(props("repo", str("owner/name, e.g. vercel/next.js")), List.of("repo")),
                        Duration.ofMinutes(30), this::repo,
                        a -> "Reading GitHub repo " + a.path("repo").asText("") + "…"),
                new ConnectorTool(ID + "_commit_activity",
                        "Weekly commit counts for a repository over the last N weeks (max 52), with the recent 4-week "
                                + "average versus the prior 12 weeks — development momentum.",
                        object(props(
                                "repo", str("owner/name"),
                                "weeks", integer("Weeks to return (4-52, default 26)")), List.of("repo")),
                        Duration.ofHours(6), this::commitActivity,
                        a -> "Reading commit activity for " + a.path("repo").asText("") + "…"),
                new ConnectorTool(ID + "_releases",
                        "Recent releases of a repository, newest first: tag, name, published time, pre-release flag "
                                + "and a short excerpt of the notes.",
                        object(props(
                                "repo", str("owner/name"),
                                "include_prereleases", bool("Include pre-releases (default true)"),
                                "limit", integer("Max releases (1-30, default 10)")), List.of("repo")),
                        Duration.ofMinutes(30), this::releases,
                        a -> "Reading releases of " + a.path("repo").asText("") + "…"),
                new ConnectorTool(ID + "_star_velocity",
                        "Recent star velocity: when the most recent ~100 stars were given, as stars per day and per-day "
                                + "counts. A proxy for star history. Needs a GitHub token (Settings → Connectors).",
                        object(props("repo", str("owner/name")), List.of("repo")),
                        Duration.ofHours(1), this::starVelocity,
                        a -> "Reading recent stars of " + a.path("repo").asText("") + "…"),
                new ConnectorTool(ID + "_org_repos",
                        "An organisation's or user's most-starred public repositories (e.g. DataDog, elastic, "
                                + "hashicorp): name, stars, forks, language, last push.",
                        object(props(
                                "org", str("Organisation or user login, e.g. DataDog"),
                                "limit", integer("Max repositories (1-30, default 10)")), List.of("org")),
                        Duration.ofHours(1), this::orgRepos,
                        a -> "Reading top repositories of " + a.path("org").asText("") + "…"));
    }

    // ── tools ────────────────────────────────────────────────────────────────

    Object repo(ConnectorContext ctx, JsonNode args) {
        String repo = repoArg(args);
        JsonNode r = get(ctx, api + "/repos/" + repo);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("repo", r.path("full_name").asText(repo));
        out.put("description", trim(r.path("description").asText(""), 200));
        out.put("stars", r.path("stargazers_count").asLong());
        out.put("forks", r.path("forks_count").asLong());
        out.put("watchers", r.path("subscribers_count").asLong());
        out.put("open_issues_and_prs", r.path("open_issues_count").asLong());
        out.put("language", r.path("language").isTextual() ? r.path("language").asText() : null);
        out.put("license", r.path("license").path("spdx_id").isTextual() ? r.path("license").path("spdx_id").asText() : null);
        List<String> topics = new ArrayList<>();
        r.path("topics").forEach(t -> { if (topics.size() < 15) topics.add(t.asText()); });
        out.put("topics", topics);
        out.put("created_at", r.path("created_at").asText(null));
        out.put("pushed_at", r.path("pushed_at").asText(null));
        out.put("archived", r.path("archived").asBoolean(false));
        out.put("fork", r.path("fork").asBoolean(false));
        out.put("homepage", r.path("homepage").isTextual() && !r.path("homepage").asText().isBlank() ? r.path("homepage").asText() : null);
        JsonNode rel = get(ctx, url(api + "/repos/" + repo + "/releases", "per_page", 1));
        if (rel.isArray() && !rel.isEmpty()) {
            JsonNode x = rel.get(0);
            Map<String, Object> lr = new LinkedHashMap<>();
            lr.put("tag", x.path("tag_name").asText(null));
            lr.put("published_at", x.path("published_at").asText(null));
            lr.put("prerelease", x.path("prerelease").asBoolean(false));
            out.put("latest_release", lr);
        } else {
            out.put("latest_release", null);
        }
        out.put("url", "https://github.com/" + r.path("full_name").asText(repo));
        return out;
    }

    Object commitActivity(ConnectorContext ctx, JsonNode args) {
        String repo = repoArg(args);
        int weeks = intArg(args, "weeks", 26, 4, 52);
        JsonNode j = get(ctx, api + "/repos/" + repo + "/stats/commit_activity");
        if (!j.isArray() || j.isEmpty()) {
            // GitHub answers 202 with {} while it computes repository statistics in the background.
            throw ConnectorException.upstream("GitHub is still computing commit statistics for " + repo + "; try again in a minute.");
        }
        List<Map<String, Object>> all = new ArrayList<>();
        for (JsonNode w : j) {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("week_start", LocalDate.ofInstant(Instant.ofEpochSecond(w.path("week").asLong()), ZoneOffset.UTC).toString());
            r.put("commits", w.path("total").asInt());
            all.add(r);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("repo", repo);
        int n = all.size();
        if (n >= 16) {
            double recent = avg(all.subList(n - 4, n));
            double prior = avg(all.subList(n - 16, n - 4));
            out.put("avg_last_4_weeks", round(recent));
            out.put("avg_prior_12_weeks", round(prior));
            if (prior > 0) out.put("momentum", round(recent / prior));
        }
        out.put("total_52_weeks", all.stream().mapToInt(m -> (int) m.get("commits")).sum());
        out.put("note", "The current week is partial.");
        out.put("weeks", n > weeks ? new ArrayList<>(all.subList(n - weeks, n)) : all);
        return out;
    }

    Object releases(ConnectorContext ctx, JsonNode args) {
        String repo = repoArg(args);
        int limit = intArg(args, "limit", 10, 1, 30);
        boolean pre = !args.has("include_prereleases") || args.path("include_prereleases").asBoolean(true);
        JsonNode j = get(ctx, url(api + "/repos/" + repo + "/releases", "per_page", pre ? limit : Math.min(100, limit * 3)));
        List<Map<String, Object>> rows = new ArrayList<>();
        for (JsonNode x : j) {
            if (rows.size() >= limit) break;
            if (x.path("draft").asBoolean(false)) continue;
            if (!pre && x.path("prerelease").asBoolean(false)) continue;
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("published_at", x.path("published_at").asText(null));
            r.put("tag", x.path("tag_name").asText(null));
            r.put("name", trim(x.path("name").asText(""), 120));
            r.put("prerelease", x.path("prerelease").asBoolean(false));
            String body = trim(x.path("body").asText(""), 300);
            if (!body.isEmpty()) r.put("notes_excerpt", body);
            r.put("url", x.path("html_url").asText(null));
            rows.add(r);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("repo", repo);
        out.put("count", rows.size());
        out.put("releases", rows);
        return out;
    }

    Object starVelocity(ConnectorContext ctx, JsonNode args) {
        String repo = repoArg(args);
        if (ctx.field("token").isEmpty()) {
            throw new ConnectorException(ConnectorException.Kind.NOT_LINKED,
                    "GitHub's stargazer list needs authentication: add a personal access token in Settings → Connectors.");
        }
        long stars = get(ctx, api + "/repos/" + repo).path("stargazers_count").asLong();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("repo", repo);
        out.put("stars", stars);
        if (stars == 0) {
            out.put("available", false);
            out.put("reason", "The repository has no stars.");
            return out;
        }
        // Stargazers are listed oldest first, so the newest stars are on the last page.
        long lastPage = (stars + 99) / 100;
        JsonNode page;
        try {
            page = getAccept(ctx, url(api + "/repos/" + repo + "/stargazers", "per_page", 100, "page", lastPage),
                    "application/vnd.github.star+json");
        } catch (ConnectorException e) {
            if (e.kind() != ConnectorException.Kind.UPSTREAM && e.kind() != ConnectorException.Kind.BAD_REQUEST) throw e;
            page = null;
        }
        List<Instant> times = new ArrayList<>();
        if (page != null) for (JsonNode s : page) {
            if (s.path("starred_at").isTextual()) times.add(Instant.parse(s.path("starred_at").asText()));
        }
        if (times.size() < 2) {
            out.put("available", false);
            out.put("reason", "GitHub did not return the newest stargazers (very large repositories cannot be paged to the end).");
            return out;
        }
        times.sort(Instant::compareTo);
        Instant first = times.get(0), last = times.get(times.size() - 1);
        double days = Math.max(1.0 / 24, ChronoUnit.SECONDS.between(first, last) / 86400.0);
        Map<String, Integer> perDay = new LinkedHashMap<>();
        for (Instant t : times) perDay.merge(LocalDate.ofInstant(t, ZoneOffset.UTC).toString(), 1, Integer::sum);
        List<Map<String, Object>> series = new ArrayList<>();
        perDay.forEach((d, n) -> {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("date", d);
            r.put("stars", n);
            series.add(r);
        });
        out.put("available", true);
        out.put("sample", times.size());
        out.put("from", first.toString());
        out.put("to", last.toString());
        out.put("stars_per_day", round(times.size() / days));
        out.put("days_since_last_star", round(ChronoUnit.SECONDS.between(last, clock.instant()) / 86400.0));
        out.put("per_day", series.size() > 60 ? new ArrayList<>(series.subList(series.size() - 60, series.size())) : series);
        return out;
    }

    Object orgRepos(ConnectorContext ctx, JsonNode args) {
        String org = requireStr(args, "org", 39);
        if (!org.matches("^[A-Za-z0-9-]{1,39}$")) throw ConnectorException.badRequest("org must be a GitHub login like DataDog");
        int limit = intArg(args, "limit", 10, 1, 30);
        JsonNode j = get(ctx, url(api + "/search/repositories", "q", "user:" + org + " fork:false", "sort", "stars", "order", "desc", "per_page", limit));
        List<Map<String, Object>> rows = new ArrayList<>();
        for (JsonNode r : j.path("items")) {
            if (rows.size() >= limit) break;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("repo", r.path("full_name").asText(null));
            m.put("stars", r.path("stargazers_count").asLong());
            m.put("forks", r.path("forks_count").asLong());
            m.put("language", r.path("language").isTextual() ? r.path("language").asText() : null);
            m.put("pushed_at", r.path("pushed_at").asText(null));
            m.put("archived", r.path("archived").asBoolean(false));
            m.put("description", trim(r.path("description").asText(""), 150));
            rows.add(m);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("org", org);
        out.put("public_repos", j.path("total_count").asLong());
        out.put("repos", rows);
        return out;
    }

    // ── transport ────────────────────────────────────────────────────────────

    private JsonNode get(ConnectorContext ctx, String u) {
        return getAccept(ctx, u, "application/vnd.github+json");
    }

    private JsonNode getAccept(ConnectorContext ctx, String u, String accept) {
        Map<String, String> h = new LinkedHashMap<>();
        h.put("X-GitHub-Api-Version", "2022-11-28");
        ctx.field("token").ifPresent(t -> h.put("Authorization", "Bearer " + t));
        String body;
        try {
            body = ctx.get(URI.create(u), h, accept);
        } catch (ConnectorException e) {
            // GitHub signals an exhausted rate limit with 403 (or 429) and a "rate limit" message.
            if (e.kind() == ConnectorException.Kind.AUTH && e.getMessage().toLowerCase().contains("rate limit")) {
                throw new ConnectorException(ConnectorException.Kind.RATE_LIMITED,
                        "GitHub rate limit reached" + (ctx.field("token").isPresent() ? "." : " (60 requests/hour without a token; add one in Settings → Connectors)."));
            }
            throw e;
        }
        try {
            return body == null || body.isBlank() ? ctx.mapper().createObjectNode() : ctx.mapper().readTree(body);
        } catch (Exception e) {
            throw ConnectorException.upstream("GitHub answered something that is not JSON.");
        }
    }

    private static String repoArg(JsonNode args) {
        String r = requireStr(args, "repo", 150).replaceFirst("^https?://github\\.com/", "").replaceFirst("/+$", "").replaceFirst("\\.git$", "");
        if (!r.matches(REPO_RE)) throw ConnectorException.badRequest("repo must be owner/name, e.g. vercel/next.js");
        return r;
    }

    private static double avg(List<Map<String, Object>> ws) {
        return ws.stream().mapToInt(m -> (int) m.get("commits")).average().orElse(0);
    }

    private static double round(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    static String trim(String s, int max) {
        String t = s == null ? "" : s.replaceAll("\\s+", " ").strip();
        return t.length() > max ? t.substring(0, max) + "…" : t;
    }
}
