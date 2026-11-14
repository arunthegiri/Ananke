package com.stocktracker.connectors.hacker_news;

import com.fasterxml.jackson.databind.JsonNode;
import com.stocktracker.connectors.Connector;
import com.stocktracker.connectors.ConnectorContext;
import com.stocktracker.connectors.ConnectorException;
import com.stocktracker.connectors.ConnectorSpec;
import com.stocktracker.connectors.ConnectorSpec.RateLimit;
import com.stocktracker.connectors.ConnectorTool;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.stocktracker.connectors.ToolArgs.enumStr;
import static com.stocktracker.connectors.ToolArgs.intArg;
import static com.stocktracker.connectors.ToolArgs.integer;
import static com.stocktracker.connectors.ToolArgs.object;
import static com.stocktracker.connectors.ToolArgs.oneOf;
import static com.stocktracker.connectors.ToolArgs.props;
import static com.stocktracker.connectors.ToolArgs.requireStr;
import static com.stocktracker.connectors.ToolArgs.str;
import static com.stocktracker.connectors.ToolArgs.url;

/**
 * Hacker News through the HN Search API by Algolia: stories and comments mentioning a company or
 * product, and daily mention counts — developer and tech-investor attention. Keyless; Algolia
 * allows 10,000 requests per hour per IP.
 *
 * <p>Point-in-time: every hit carries {@code created_at}.
 */
@Component
public class HackerNewsConnector implements Connector {

    static final String ID = "hacker_news";
    static final String BASE_URL = "https://hn.algolia.com/api/v1";

    private static final ConnectorSpec SPEC = new ConnectorSpec(
            ID,
            "Hacker News",
            ConnectorSpec.Category.SOCIAL,
            ConnectorSpec.Licence.FREE_PUBLIC,
            "Hacker News stories and comments mentioning a company, product or technology, with points, comment "
                    + "counts and timestamps, plus daily mention counts — a gauge of developer and tech-investor attention.",
            "Hacker News (news.ycombinator.com) via the HN Search API by Algolia",
            "https://hn.algolia.com/api",
            List.of(),
            // Algolia: 10,000 requests per hour per IP.
            List.of(RateLimit.perMinute(60)));

    private final Clock clock;
    private final String base;

    public HackerNewsConnector() {
        this(Clock.systemUTC(), BASE_URL);
    }

    /** Test seam: point at a fake Algolia. */
    HackerNewsConnector(Clock clock, String base) {
        this.clock = clock;
        this.base = base;
    }

    @Override
    public ConnectorSpec spec() {
        return SPEC;
    }

    @Override
    public String test(ConnectorContext ctx) {
        JsonNode j = ctx.getJson(URI.create(url(base + "/search_by_date", "tags", "story", "hitsPerPage", 1)));
        return "Connected — HN Search answered (latest story: " + trim(j.path("hits").path(0).path("title").asText("?"), 80) + ").";
    }

    @Override
    public List<ConnectorTool> tools() {
        return List.of(
                new ConnectorTool(ID + "_search",
                        "Search Hacker News stories and/or comments for a company, product or technology. Returns title "
                                + "or trimmed comment text, points, comment count, author, created time and links. Sort by "
                                + "date (newest) or relevance; optional minimum points and look-back window.",
                        object(props(
                                "query", str("Search text, e.g. Snowflake or \"Cloudflare Workers\""),
                                "type", enumStr("story (default), comment or all", List.of("story", "comment", "all")),
                                "sort", enumStr("date (default) or relevance", List.of("date", "relevance")),
                                "days", integer("Only the last N days (1-3650, default 30)"),
                                "min_points", integer("Only stories with at least this many points (default 0)"),
                                "limit", integer("Max hits (1-50, default 20)")), List.of("query")),
                        Duration.ofMinutes(10), this::search,
                        a -> "Searching Hacker News for " + a.path("query").asText("") + "…"),
                new ConnectorTool(ID + "_mentions",
                        "Daily count of Hacker News stories or comments mentioning a query over the last N UTC days, "
                                + "with the latest full day versus the average — an attention spike detector.",
                        object(props(
                                "query", str("Search text, e.g. Snowflake"),
                                "type", enumStr("story (default) or comment", List.of("story", "comment")),
                                "days", integer("Window in days (2-30, default 14)")), List.of("query")),
                        Duration.ofMinutes(30), this::mentions,
                        a -> "Counting Hacker News mentions of " + a.path("query").asText("") + "…"));
    }

    // ── tools ────────────────────────────────────────────────────────────────

    Object search(ConnectorContext ctx, JsonNode args) {
        String query = requireStr(args, "query", 200);
        String type = oneOf(args, "type", "story", List.of("story", "comment", "all"));
        String sort = oneOf(args, "sort", "date", List.of("date", "relevance"));
        int days = intArg(args, "days", 30, 1, 3650);
        int minPoints = intArg(args, "min_points", 0, 0, 100_000);
        int limit = intArg(args, "limit", 20, 1, 50);
        long since = clock.instant().minus(Duration.ofDays(days)).getEpochSecond();
        String filters = "created_at_i>" + since + (minPoints > 0 ? ",points>=" + minPoints : "");
        JsonNode j = ctx.getJson(URI.create(url(base + (sort.equals("date") ? "/search_by_date" : "/search"),
                "query", query,
                "tags", type.equals("all") ? "(story,comment)" : type,
                "numericFilters", filters,
                "hitsPerPage", limit)));

        List<Map<String, Object>> rows = new ArrayList<>();
        for (JsonNode h : j.path("hits")) {
            if (rows.size() >= limit) break;
            boolean comment = h.has("comment_text") && h.path("comment_text").isTextual();
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("created_at", h.path("created_at").asText(null));
            r.put("type", comment ? "comment" : "story");
            if (comment) {
                r.put("text", trim(html(h.path("comment_text").asText("")), 400));
                r.put("story_title", trim(h.path("story_title").asText(""), 200));
            } else {
                r.put("title", trim(h.path("title").asText(""), 200));
                r.put("points", h.path("points").isNumber() ? h.path("points").asInt() : null);
                r.put("comments", h.path("num_comments").isNumber() ? h.path("num_comments").asInt() : null);
                if (h.path("url").isTextual()) r.put("link", h.path("url").asText());
            }
            r.put("author", h.path("author").asText(null));
            r.put("hn_url", "https://news.ycombinator.com/item?id=" + h.path("objectID").asText(""));
            rows.add(r);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("query", query);
        out.put("type", type);
        out.put("days", days);
        out.put("total_hits", j.path("nbHits").asLong());
        out.put("count", rows.size());
        out.put("hits", rows);
        return out;
    }

    Object mentions(ConnectorContext ctx, JsonNode args) {
        String query = requireStr(args, "query", 200);
        String type = oneOf(args, "type", "story", List.of("story", "comment"));
        int days = intArg(args, "days", 14, 2, 30);
        LocalDate today = LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC);
        List<Map<String, Object>> series = new ArrayList<>();
        long total = 0, fullDays = 0, fullTotal = 0;
        for (LocalDate d = today.minusDays(days - 1L); !d.isAfter(today); d = d.plusDays(1)) {
            long from = d.atStartOfDay().toEpochSecond(ZoneOffset.UTC);
            long to = d.plusDays(1).atStartOfDay().toEpochSecond(ZoneOffset.UTC);
            JsonNode j = ctx.getJson(URI.create(url(base + "/search_by_date",
                    "query", query, "tags", type, "hitsPerPage", 0,
                    "numericFilters", "created_at_i>=" + from + ",created_at_i<" + to)));
            long n = j.path("nbHits").asLong();
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("date", d.toString());
            r.put("count", n);
            series.add(r);
            total += n;
            if (d.isBefore(today)) { fullDays++; fullTotal += n; }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("query", query);
        out.put("type", type);
        out.put("days", days);
        out.put("total", total);
        double avg = fullDays == 0 ? 0 : (double) fullTotal / fullDays;
        out.put("daily_average_full_days", Math.round(avg * 100) / 100.0);
        long yesterday = (long) series.get(series.size() - 2).get("count");
        out.put("latest_full_day", series.get(series.size() - 2));
        if (avg > 0) out.put("latest_full_day_vs_average", Math.round(yesterday / avg * 100) / 100.0);
        out.put("note", "Today (UTC, " + today + ") is a partial day.");
        out.put("per_day", series);
        return out;
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    static String html(String s) {
        return s.replaceAll("(?i)<p>", " ").replaceAll("<[^>]*>", " ")
                .replace("&#x27;", "'").replace("&#x2F;", "/").replace("&quot;", "\"").replace("&gt;", ">")
                .replace("&lt;", "<").replace("&amp;", "&");
    }

    static String trim(String s, int max) {
        String t = s == null ? "" : s.replaceAll("\\s+", " ").strip();
        return t.length() > max ? t.substring(0, max) + "…" : t;
    }
}
