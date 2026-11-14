package com.stocktracker.connectors.reddit;

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
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import static com.stocktracker.connectors.ToolArgs.enumStr;
import static com.stocktracker.connectors.ToolArgs.intArg;
import static com.stocktracker.connectors.ToolArgs.integer;
import static com.stocktracker.connectors.ToolArgs.object;
import static com.stocktracker.connectors.ToolArgs.oneOf;
import static com.stocktracker.connectors.ToolArgs.props;
import static com.stocktracker.connectors.ToolArgs.requireStr;
import static com.stocktracker.connectors.ToolArgs.str;
import static com.stocktracker.connectors.ToolArgs.strArray;
import static com.stocktracker.connectors.ToolArgs.strSet;
import static com.stocktracker.connectors.ToolArgs.url;

/**
 * Reddit (Data API, OAuth "application-only" client credentials): searches the user's chosen
 * investing subreddits for a ticker or company and measures mention velocity. The user registers
 * their own "script" app; Reddit's free tier is non-commercial, so the licence is BYO_PERSONAL.
 * Reddit requires a unique, descriptive User-Agent — the user supplies it.
 *
 * <p>Point-in-time: every post carries {@code created_at} (Reddit's {@code created_utc}).
 */
@Component
public class RedditConnector implements Connector {

    static final String ID = "reddit";
    static final String AUTH_URL = "https://www.reddit.com";
    static final String API_URL = "https://oauth.reddit.com";
    static final List<String> DEFAULT_SUBS = List.of("wallstreetbets", "stocks", "investing", "options", "StockMarket", "pennystocks");

    private static final ConnectorSpec SPEC = new ConnectorSpec(
            ID,
            "Reddit",
            ConnectorSpec.Category.SOCIAL,
            ConnectorSpec.Licence.BYO_PERSONAL,
            "Search investing subreddits (wallstreetbets, stocks, investing, options…) for a ticker, cashtag or "
                    + "company, and count mentions per day to spot accelerating retail attention. Uses your own Reddit "
                    + "app (free tier: personal, non-commercial use).",
            "Data from Reddit (reddit.com), via the Reddit Data API",
            "https://support.reddithelp.com/hc/en-us/articles/16160319875092-Reddit-Data-API-Wiki",
            List.of(
                    Field.secret("client_id", "Client ID",
                            "From reddit.com/prefs/apps → create a \"script\" app; the ID is shown under its name."),
                    Field.secret("client_secret", "Client secret", "Shown as \"secret\" on the same app."),
                    Field.text("user_agent", "User-Agent",
                            "Reddit requires a unique, descriptive User-Agent naming your app and your Reddit username.",
                            "ananke:research:v1 (by /u/yourname)")),
            // Reddit: 100 queries per minute per OAuth client, averaged over 10 minutes.
            List.of(RateLimit.perMinute(60)));

    private final Clock clock;
    private final String auth;
    private final String api;
    private volatile Token token;

    record Token(String key, String value, Instant expires) { }

    public RedditConnector() {
        this(Clock.systemUTC(), AUTH_URL, API_URL);
    }

    /** Test seam: point at a fake Reddit. */
    RedditConnector(Clock clock, String auth, String api) {
        this.clock = clock;
        this.auth = auth;
        this.api = api;
    }

    @Override
    public ConnectorSpec spec() {
        return SPEC;
    }

    @Override
    public String test(ConnectorContext ctx) {
        userAgent(ctx);
        token = null;
        JsonNode j = ctx.getJson(URI.create(url(api + "/r/stocks/about")), headers(ctx));
        return "Connected — Reddit answered (r/" + j.path("data").path("display_name").asText("stocks") + ", "
                + j.path("data").path("subscribers").asLong() + " subscribers).";
    }

    @Override
    public List<ConnectorTool> tools() {
        return List.of(
                new ConnectorTool(ID + "_search",
                        "Search Reddit posts in investing subreddits for a ticker, cashtag or company. Returns title, "
                                + "trimmed text, subreddit, score, upvote ratio, comment count, flair and created time. "
                                + "Default subreddits: " + String.join(", ", DEFAULT_SUBS) + ".",
                        object(props(
                                "query", str("Search text, e.g. NVDA, $NVDA or \"Super Micro\""),
                                "subreddits", strArray("Subreddit names without r/, e.g. [\"wallstreetbets\",\"stocks\"]. Omit for the default set."),
                                "sort", enumStr("new (default), top, relevance or comments", List.of("new", "top", "relevance", "comments")),
                                "window", enumStr("Look-back window (default week)", List.of("hour", "day", "week", "month", "year")),
                                "limit", integer("Max posts (1-50, default 25)")), List.of("query")),
                        Duration.ofMinutes(5), this::search,
                        a -> "Searching Reddit for " + a.path("query").asText("") + "…"),
                new ConnectorTool(ID + "_mentions",
                        "Mention velocity: posts matching a ticker/company in the chosen subreddits, counted per UTC day "
                                + "over the last N days, with total score and comments per day and the latest day versus the "
                                + "daily average. Counts are of search hits (Reddit search returns at most ~250 posts per "
                                + "query), so read them as a relative attention gauge, not a census.",
                        object(props(
                                "query", str("Ticker or company, e.g. NVDA"),
                                "subreddits", strArray("Subreddits without r/. Omit for the default set."),
                                "days", integer("Window in days (1-30, default 7)")), List.of("query")),
                        Duration.ofMinutes(15), this::mentions,
                        a -> "Counting Reddit mentions of " + a.path("query").asText("") + "…"));
    }

    // ── tools ────────────────────────────────────────────────────────────────

    Object search(ConnectorContext ctx, JsonNode args) {
        String query = requireStr(args, "query", 200);
        List<String> subs = subs(args);
        String sort = oneOf(args, "sort", "new", List.of("new", "top", "relevance", "comments"));
        String window = oneOf(args, "window", "week", List.of("hour", "day", "week", "month", "year"));
        int limit = intArg(args, "limit", 25, 1, 50);
        JsonNode j = ctx.getJson(URI.create(url(api + "/r/" + String.join("+", subs) + "/search",
                "q", query, "restrict_sr", "on", "sort", sort, "t", window, "limit", limit, "raw_json", 1)), headers(ctx));

        List<Map<String, Object>> rows = new ArrayList<>();
        for (JsonNode c : j.path("data").path("children")) {
            if (rows.size() >= limit) break;
            JsonNode d = c.path("data");
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("created_at", created(d));
            r.put("subreddit", d.path("subreddit").asText(null));
            r.put("title", trim(d.path("title").asText(""), 300));
            String self = trim(d.path("selftext").asText(""), 400);
            if (!self.isEmpty()) r.put("text", self);
            r.put("flair", d.path("link_flair_text").isTextual() ? d.path("link_flair_text").asText() : null);
            r.put("score", d.path("score").asInt());
            r.put("upvote_ratio", d.path("upvote_ratio").isNumber() ? d.path("upvote_ratio").asDouble() : null);
            r.put("comments", d.path("num_comments").asInt());
            r.put("author", d.path("author").asText(null));
            r.put("url", "https://www.reddit.com" + d.path("permalink").asText(""));
            rows.add(r);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("query", query);
        out.put("subreddits", subs);
        out.put("sort", sort);
        out.put("window", window);
        out.put("count", rows.size());
        out.put("posts", rows);
        return out;
    }

    Object mentions(ConnectorContext ctx, JsonNode args) {
        String query = requireStr(args, "query", 200);
        List<String> subs = subs(args);
        int days = intArg(args, "days", 7, 1, 30);
        String window = days <= 1 ? "day" : days <= 7 ? "week" : "month";
        LocalDate today = LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC);
        LocalDate first = today.minusDays(days - 1L);
        long cutoff = first.atStartOfDay().toEpochSecond(ZoneOffset.UTC);

        TreeMap<LocalDate, long[]> perDay = new TreeMap<>();   // [posts, score, comments]
        for (LocalDate d = first; !d.isAfter(today); d = d.plusDays(1)) perDay.put(d, new long[3]);
        String after = null;
        int pages = 0;
        boolean truncated = false;
        do {
            JsonNode j = ctx.getJson(URI.create(url(api + "/r/" + String.join("+", subs) + "/search",
                    "q", query, "restrict_sr", "on", "sort", "new", "t", window, "limit", 100, "after", after, "raw_json", 1)), headers(ctx));
            boolean older = false;
            for (JsonNode c : j.path("data").path("children")) {
                JsonNode d = c.path("data");
                long t = (long) d.path("created_utc").asDouble();
                if (t < cutoff) { older = true; continue; }
                long[] v = perDay.get(LocalDate.ofInstant(Instant.ofEpochSecond(t), ZoneOffset.UTC));
                if (v == null) continue;
                v[0]++;
                v[1] += d.path("score").asLong();
                v[2] += d.path("num_comments").asLong();
            }
            after = j.path("data").path("after").isTextual() ? j.path("data").path("after").asText() : null;
            pages++;
            if (older) after = null;
            if (after != null && pages >= 3) { truncated = true; after = null; }
        } while (after != null);

        List<Map<String, Object>> series = new ArrayList<>();
        long total = 0;
        for (Map.Entry<LocalDate, long[]> e : perDay.entrySet()) {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("date", e.getKey().toString());
            r.put("posts", e.getValue()[0]);
            r.put("score", e.getValue()[1]);
            r.put("comments", e.getValue()[2]);
            series.add(r);
            total += e.getValue()[0];
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("query", query);
        out.put("subreddits", subs);
        out.put("days", days);
        out.put("total_posts", total);
        double avg = (double) total / days;
        out.put("daily_average", Math.round(avg * 100) / 100.0);
        long latest = perDay.lastEntry().getValue()[0];
        out.put("latest_day_posts", latest);
        if (avg > 0) out.put("latest_vs_average", Math.round(latest / avg * 100) / 100.0);
        out.put("note", "Today (UTC) is a partial day." + (truncated ? " Hit the 300-post read cap; earliest days may be undercounted." : ""));
        out.put("per_day", series);
        return out;
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private static List<String> subs(JsonNode args) {
        Set<String> s = strSet(args, "subreddits", false);
        if (s.isEmpty()) return DEFAULT_SUBS;
        if (s.size() > 15) throw ConnectorException.badRequest("subreddits: at most 15");
        List<String> out = new ArrayList<>();
        for (String x : s) {
            String n = x.replaceFirst("^/?r/", "");
            if (!n.matches("^[A-Za-z0-9_]{2,21}$")) throw ConnectorException.badRequest("subreddits: \"" + x + "\" is not a subreddit name");
            out.add(n);
        }
        return out;
    }

    private static String created(JsonNode d) {
        JsonNode c = d.path("created_utc");
        return c.isNumber() ? Instant.ofEpochSecond((long) c.asDouble()).toString() : null;
    }

    private static String userAgent(ConnectorContext ctx) {
        String ua = ctx.require("user_agent");
        if (ua.length() < 8 || ua.toLowerCase(Locale.ROOT).matches(".*(mozilla|curl|python-requests|java).*")) {
            throw ConnectorException.badRequest("Reddit needs a unique, descriptive User-Agent like \"ananke:research:v1 (by /u/yourname)\".");
        }
        return ua;
    }

    private Map<String, String> headers(ConnectorContext ctx) {
        return Map.of("Authorization", "Bearer " + token(ctx), "User-Agent", userAgent(ctx));
    }

    /** App-only bearer token, reused until shortly before it expires (Reddit issues 24h tokens). */
    String token(ConnectorContext ctx) {
        String id = ctx.require("client_id");
        String secret = ctx.require("client_secret");
        String key = hash(id + "\u0000" + secret);
        Instant now = clock.instant();
        Token t = token;
        if (t != null && t.key().equals(key) && now.isBefore(t.expires())) return t.value();

        String basic = Base64.getEncoder().encodeToString((id + ":" + secret).getBytes(StandardCharsets.UTF_8));
        JsonNode r = ctx.postForm(URI.create(auth + "/api/v1/access_token"),
                Map.of("Authorization", "Basic " + basic, "User-Agent", userAgent(ctx)),
                Map.of("grant_type", "client_credentials"));
        String value = r.path("access_token").asText("");
        if (value.isEmpty()) {
            // Reddit answers some bad credentials with HTTP 200 {"error": "invalid_grant"|"unsupported_grant_type"}.
            String err = r.path("error").asText("");
            throw new ConnectorException(ConnectorException.Kind.AUTH,
                    "Reddit did not issue a token" + (err.isEmpty() ? "" : " (" + err + ")") + ". Check the client ID and secret in Settings → Connectors.");
        }
        long ttl = Math.max(60, r.path("expires_in").asLong(3600) - 300);
        token = new Token(key, value, now.plusSeconds(ttl));
        return value;
    }

    private static String hash(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    static String trim(String s, int max) {
        String t = s == null ? "" : s.replaceAll("\\s+", " ").strip();
        return t.length() > max ? t.substring(0, max) + "…" : t;
    }
}
