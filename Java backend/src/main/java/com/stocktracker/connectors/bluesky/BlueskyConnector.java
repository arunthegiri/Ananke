package com.stocktracker.connectors.bluesky;

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
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static com.stocktracker.connectors.ToolArgs.date;
import static com.stocktracker.connectors.ToolArgs.enumStr;
import static com.stocktracker.connectors.ToolArgs.intArg;
import static com.stocktracker.connectors.ToolArgs.integer;
import static com.stocktracker.connectors.ToolArgs.object;
import static com.stocktracker.connectors.ToolArgs.oneOf;
import static com.stocktracker.connectors.ToolArgs.optStr;
import static com.stocktracker.connectors.ToolArgs.props;
import static com.stocktracker.connectors.ToolArgs.requireStr;
import static com.stocktracker.connectors.ToolArgs.str;
import static com.stocktracker.connectors.ToolArgs.url;

/**
 * Bluesky (AT Protocol) post search. The public AppView ({@code api.bsky.app}) still answers
 * {@code app.bsky.feed.searchPosts} without an account, so the connector is keyless. A handle plus
 * an app password are optional: when given, Ananke signs in ({@code createSession}) and searches
 * through the account's PDS, which proxies to the AppView with the account's own limits.
 *
 * <p>Point-in-time: every post carries {@code created_at} (author's clock) and {@code indexed_at}
 * (when the AppView saw it — the safer timestamp for backtests).
 */
@Component
public class BlueskyConnector implements Connector {

    static final String ID = "bluesky";
    static final String APPVIEW_URL = "https://api.bsky.app";
    static final String PDS_URL = "https://bsky.social";
    static final Duration SESSION_TTL = Duration.ofMinutes(60);

    private static final ConnectorSpec SPEC = new ConnectorSpec(
            ID,
            "Bluesky",
            ConnectorSpec.Category.SOCIAL,
            ConnectorSpec.Licence.FREE_PUBLIC,
            "Search public Bluesky posts for a cashtag ($NVDA), company or topic: text, author, likes, reposts, "
                    + "replies and timestamps. Works without an account; an optional handle + app password searches "
                    + "as your account.",
            "Posts from Bluesky (bsky.app), via the AT Protocol",
            "https://docs.bsky.app/docs/api/app-bsky-feed-search-posts",
            List.of(
                    new Field("handle", "Handle (optional)",
                            "Your Bluesky handle, e.g. jane.bsky.social. Only needed to search as your account.",
                            false, true, "jane.bsky.social"),
                    new Field("app_password", "App password (optional)",
                            "Create one in Bluesky → Settings → Privacy and security → App passwords. Never your main password.",
                            true, true, "xxxx-xxxx-xxxx-xxxx")),
            // Bluesky's documented per-IP limit is 3000 requests / 5 min; stay far below it.
            List.of(RateLimit.perMinute(60)));

    private final Clock clock;
    private final String appview;
    private final String pds;
    private volatile Session session;

    record Session(String key, String accessJwt, Instant expires) { }

    public BlueskyConnector() {
        this(Clock.systemUTC(), APPVIEW_URL, PDS_URL);
    }

    /** Test seam: point at a fake AppView / PDS. */
    BlueskyConnector(Clock clock, String appview, String pds) {
        this.clock = clock;
        this.appview = appview;
        this.pds = pds;
    }

    @Override
    public ConnectorSpec spec() {
        return SPEC;
    }

    @Override
    public String test(ConnectorContext ctx) {
        Optional<String> auth = bearer(ctx, true);
        JsonNode j = search(ctx, auth, url("/xrpc/app.bsky.feed.searchPosts", "q", "stocks", "limit", 1));
        String who = auth.isPresent() ? "signed in as " + ctx.field("handle").orElse("") : "public search, no account";
        return "Connected — Bluesky answered (" + who + "; " + j.path("posts").size() + " test post).";
    }

    @Override
    public List<ConnectorTool> tools() {
        return List.of(new ConnectorTool(ID + "_search_posts",
                "Search public Bluesky posts. Use a cashtag ($NVDA) or company/product name; quotes for phrases. "
                        + "Returns post text (trimmed), author handle, like/repost/reply/quote counts, created and indexed "
                        + "times, link, and the total hit count when Bluesky reports it.",
                object(props(
                        "query", str("Search text, e.g. $TSLA or \"Super Micro\""),
                        "sort", enumStr("latest (default) or top", List.of("latest", "top")),
                        "since", str("Only posts on/after this date, YYYY-MM-DD"),
                        "until", str("Only posts before this date, YYYY-MM-DD"),
                        "lang", str("Language code, e.g. en"),
                        "limit", integer("Max posts (1-50, default 25)")), List.of("query")),
                Duration.ofMinutes(5), this::searchPosts,
                a -> "Searching Bluesky for " + a.path("query").asText("") + "…"));
    }

    // ── tools ────────────────────────────────────────────────────────────────

    Object searchPosts(ConnectorContext ctx, JsonNode args) {
        String query = requireStr(args, "query", 200);
        String sort = oneOf(args, "sort", "latest", List.of("latest", "top"));
        LocalDate since = date(args, "since");
        LocalDate until = date(args, "until");
        if (since != null && until != null && !since.isBefore(until)) throw ConnectorException.badRequest("since must be before until");
        String lang = optStr(args, "lang");
        if (lang != null && !lang.matches("^[a-zA-Z]{2,3}(-[a-zA-Z0-9]{2,8})?$")) throw ConnectorException.badRequest("lang must be a language code like en");
        int limit = intArg(args, "limit", 25, 1, 50);

        String path = url("/xrpc/app.bsky.feed.searchPosts",
                "q", query, "sort", sort,
                "since", since == null ? null : since.atStartOfDay().toInstant(ZoneOffset.UTC).toString(),
                "until", until == null ? null : until.atStartOfDay().toInstant(ZoneOffset.UTC).toString(),
                "lang", lang, "limit", limit);
        JsonNode j = search(ctx, bearer(ctx, false), path);

        List<Map<String, Object>> rows = new ArrayList<>();
        for (JsonNode p : j.path("posts")) {
            if (rows.size() >= limit) break;
            JsonNode rec = p.path("record");
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("created_at", rec.path("createdAt").asText(null));
            r.put("indexed_at", p.path("indexedAt").asText(null));
            r.put("author", p.path("author").path("handle").asText(null));
            r.put("text", trim(rec.path("text").asText(""), 400));
            JsonNode ext = p.path("embed").path("external");
            if (ext.has("uri")) r.put("link", Map.of("url", ext.path("uri").asText(""), "title", trim(ext.path("title").asText(""), 150)));
            r.put("likes", p.path("likeCount").asInt());
            r.put("reposts", p.path("repostCount").asInt());
            r.put("replies", p.path("replyCount").asInt());
            r.put("quotes", p.path("quoteCount").asInt());
            r.put("url", webUrl(p.path("author").path("handle").asText(""), p.path("uri").asText("")));
            rows.add(r);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("query", query);
        out.put("sort", sort);
        if (j.has("hitsTotal")) out.put("hits_total", j.path("hitsTotal").asLong());
        out.put("count", rows.size());
        out.put("posts", rows);
        return out;
    }

    // ── transport ────────────────────────────────────────────────────────────

    private JsonNode search(ConnectorContext ctx, Optional<String> bearer, String pathAndQuery) {
        if (bearer.isPresent()) {
            try {
                return ctx.getJson(URI.create(pds + pathAndQuery), Map.of("Authorization", "Bearer " + bearer.get()));
            } catch (ConnectorException e) {
                // An expired or revoked session: forget it so the next call signs in again.
                if (e.kind() == ConnectorException.Kind.AUTH || e.getMessage().contains("HTTP 400")) session = null;
                throw e;
            }
        }
        return ctx.getJson(URI.create(appview + pathAndQuery));
    }

    /**
     * Access token when both handle and app password are set; empty for public search. Sessions
     * are reused for {@link #SESSION_TTL} (createSession has a tight daily limit); {@code fresh}
     * forces a new sign-in, used by {@link #test} so a changed password is really checked.
     */
    Optional<String> bearer(ConnectorContext ctx, boolean fresh) {
        Optional<String> handle = ctx.field("handle");
        Optional<String> pw = ctx.field("app_password");
        if (handle.isEmpty() && pw.isEmpty()) return Optional.empty();
        if (handle.isEmpty() || pw.isEmpty()) {
            throw ConnectorException.badRequest("Bluesky needs both the handle and an app password, or neither (public search).");
        }
        String key = hash(handle.get() + "\u0000" + pw.get());
        Session s = session;
        Instant now = clock.instant();
        if (!fresh && s != null && s.key().equals(key) && now.isBefore(s.expires())) return Optional.of(s.accessJwt());

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("identifier", handle.get().replaceFirst("^@", ""));
        body.put("password", pw.get());
        JsonNode r;
        try {
            r = ctx.postJson(URI.create(pds + "/xrpc/com.atproto.server.createSession"), Map.of(), body);
        } catch (ConnectorException e) {
            // Bluesky answers a wrong password with 401 AuthenticationRequired; 400 for a malformed handle.
            if (e.kind() == ConnectorException.Kind.AUTH || e.getMessage().contains("HTTP 400")) {
                throw new ConnectorException(ConnectorException.Kind.AUTH,
                        "Bluesky rejected the handle or app password. Check them in Settings → Connectors.");
            }
            throw e;
        }
        String jwt = r.path("accessJwt").asText("");
        if (jwt.isEmpty()) throw ConnectorException.upstream("Bluesky sign-in returned no session.");
        session = new Session(key, jwt, now.plus(SESSION_TTL));
        return Optional.of(jwt);
    }

    static String webUrl(String handle, String atUri) {
        // at://did:plc:xyz/app.bsky.feed.post/<rkey>  →  https://bsky.app/profile/<handle>/post/<rkey>
        int i = atUri.lastIndexOf('/');
        if (handle.isEmpty() || i < 0 || !atUri.contains("/app.bsky.feed.post/")) return null;
        return "https://bsky.app/profile/" + handle + "/post/" + atUri.substring(i + 1);
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
