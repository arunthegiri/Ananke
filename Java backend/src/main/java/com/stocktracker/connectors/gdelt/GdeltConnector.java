package com.stocktracker.connectors.gdelt;

import com.fasterxml.jackson.databind.JsonNode;
import com.stocktracker.connectors.Connector;
import com.stocktracker.connectors.ConnectorContext;
import com.stocktracker.connectors.ConnectorException;
import com.stocktracker.connectors.ConnectorSpec;
import com.stocktracker.connectors.ConnectorSpec.RateLimit;
import com.stocktracker.connectors.ConnectorTool;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
 * GDELT DOC 2.0 (Connectors roadmap: news): worldwide online news monitored in 65 languages,
 * searchable over a rolling three months. Article lists for a company or topic, and 15-minute
 * volume / tone timelines — attention and sentiment spikes. Keyless; GDELT asks for at most one
 * request every five seconds.
 *
 * <p>Point-in-time: every article carries {@code seen_at}, when GDELT first saw it; every
 * timeline bucket its UTC start.
 */
@Component
public class GdeltConnector implements Connector {

    static final String ID = "gdelt";
    static final String BASE_URL = "https://api.gdeltproject.org/api/v2/doc/doc";

    private static final DateTimeFormatter GDELT_TS = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'");
    private static final List<String> TIMESPANS = List.of("1h", "6h", "12h", "1d", "2d", "3d", "1w", "2w", "1m", "3m");

    private static final ConnectorSpec SPEC = new ConnectorSpec(
            ID,
            "GDELT news",
            ConnectorSpec.Category.NEWS,
            ConnectorSpec.Licence.FREE_PUBLIC,
            "Worldwide online news from the GDELT Project (rolling 3 months, 65 languages): article search for a "
                    + "company or topic, and 15-minute coverage-volume and average-tone timelines to spot attention "
                    + "and sentiment spikes.",
            "Source: The GDELT Project (gdeltproject.org)",
            "https://blog.gdeltproject.org/gdelt-doc-2-0-api-debuts/",
            List.of(),
            // GDELT: "Please limit requests to one every 5 seconds"; keep a second of headroom.
            List.of(new RateLimit(1, Duration.ofSeconds(6))));

    private final String base;

    public GdeltConnector() {
        this(BASE_URL);
    }

    /** Test seam: point at a fake GDELT. */
    GdeltConnector(String base) {
        this.base = base;
    }

    @Override
    public ConnectorSpec spec() {
        return SPEC;
    }

    @Override
    public String test(ConnectorContext ctx) {
        JsonNode j = call(ctx, "artlist", "\"federal reserve\"", "1d", List.of("maxrecords", "1"));
        int n = j.path("articles").size();
        return "Connected — GDELT answered (" + n + " article" + (n == 1 ? "" : "s") + " in a test search).";
    }

    @Override
    public List<ConnectorTool> tools() {
        return List.of(
                new ConnectorTool(ID + "_articles",
                        "Recent news articles worldwide matching a query (company, product, person or topic), newest "
                                + "first: title, domain, source country, language and when GDELT first saw it. Use quotes for "
                                + "phrases (\"Jensen Huang\"), OR inside parentheses, -word to exclude. Optional tone filter keeps "
                                + "only strongly negative or positive coverage.",
                        object(props(
                                "query", str("Search query, e.g. \"Nvidia\" or (\"Super Micro\" OR SMCI)"),
                                "timespan", enumStr("Look-back window (default 1d)", TIMESPANS),
                                "tone", enumStr("Only strongly negative (tone < -5) or positive (tone > 5) articles",
                                        List.of("any", "negative", "positive")),
                                "sort", enumStr("Order (default newest)", List.of("newest", "oldest", "most_negative", "most_positive", "relevance")),
                                "english_only", Map.of("type", "boolean", "description", "Only English-language articles (default false)"),
                                "limit", integer("Max articles (1-50, default 20)")), List.of("query")),
                        Duration.ofMinutes(15), this::articles,
                        a -> "Searching GDELT news for " + a.path("query").asText("") + "…"),
                new ConnectorTool(ID + "_timeline",
                        "Coverage timeline for a query: 'volume' = share of all monitored news mentioning it (%), "
                                + "'tone' = average tone of that coverage (-10 very negative … +10 very positive). 15-minute "
                                + "buckets for short windows, hourly/daily for longer ones. Returns the series plus the peak and "
                                + "the latest value versus the window average — a spike detector.",
                        object(props(
                                "query", str("Search query, e.g. \"Nvidia\""),
                                "measure", enumStr("volume (default) or tone", List.of("volume", "tone")),
                                "timespan", enumStr("Window (default 3d)", TIMESPANS),
                                "max_points", integer("Keep only the most recent N buckets (10-200, default 96)")), List.of("query")),
                        Duration.ofMinutes(15), this::timeline,
                        a -> "Reading GDELT " + a.path("measure").asText("volume") + " timeline for " + a.path("query").asText("") + "…"));
    }

    // ── tools ────────────────────────────────────────────────────────────────

    Object articles(ConnectorContext ctx, JsonNode args) {
        String query = query(args);
        String timespan = timespan(args, "1d");
        String tone = oneOf(args, "tone", "any", List.of("any", "negative", "positive"));
        String sort = oneOf(args, "sort", "newest", List.of("newest", "oldest", "most_negative", "most_positive", "relevance"));
        boolean english = args.path("english_only").asBoolean(false);
        int limit = intArg(args, "limit", 20, 1, 50);

        String q = query;
        if (tone.equals("negative")) q += " tone<-5";
        if (tone.equals("positive")) q += " tone>5";
        if (english) q += " sourcelang:english";
        String gSort = switch (sort) {
            case "oldest" -> "dateasc";
            case "most_negative" -> "toneasc";
            case "most_positive" -> "tonedesc";
            case "relevance" -> "hybridrel";
            default -> "datedesc";
        };
        JsonNode j = call(ctx, "artlist", q, timespan, List.of("maxrecords", String.valueOf(limit), "sort", gSort));

        List<Map<String, Object>> rows = new ArrayList<>();
        for (JsonNode a : j.path("articles")) {
            if (rows.size() >= limit) break;
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("seen_at", ts(a.path("seendate").asText(null)));
            r.put("title", trim(a.path("title").asText(""), 300));
            r.put("domain", a.path("domain").asText(null));
            r.put("source_country", a.path("sourcecountry").asText(null));
            r.put("language", a.path("language").asText(null));
            r.put("url", a.path("url").asText(null));
            rows.add(r);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("query", query);
        out.put("timespan", timespan);
        out.put("tone_filter", tone);
        out.put("count", rows.size());
        out.put("articles", rows);
        return out;
    }

    Object timeline(ConnectorContext ctx, JsonNode args) {
        String query = query(args);
        String measure = oneOf(args, "measure", "volume", List.of("volume", "tone"));
        String timespan = timespan(args, "3d");
        int maxPoints = intArg(args, "max_points", 96, 10, 200);
        JsonNode j = call(ctx, measure.equals("tone") ? "timelinetone" : "timelinevol", query, timespan, List.of());

        JsonNode series = j.path("timeline").path(0);
        List<Map<String, Object>> pts = new ArrayList<>();
        for (JsonNode p : series.path("data")) {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("t", ts(p.path("date").asText(null)));
            r.put("v", round(p.path("value").asDouble()));
            pts.add(r);
        }
        if (pts.size() > maxPoints) pts = new ArrayList<>(pts.subList(pts.size() - maxPoints, pts.size()));

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("query", query);
        out.put("measure", measure);
        out.put("unit", measure.equals("tone") ? "average tone (-10 … +10)" : "% of all monitored articles");
        out.put("resolution", j.path("query_details").path("date_resolution").asText(null));
        out.put("timespan", timespan);
        if (!pts.isEmpty()) {
            double sum = 0;
            Map<String, Object> peak = pts.get(0);
            for (Map<String, Object> p : pts) {
                double v = (double) p.get("v");
                sum += v;
                boolean better = measure.equals("tone") ? Math.abs(v) > Math.abs((double) peak.get("v")) : v > (double) peak.get("v");
                if (better) peak = p;
            }
            double avg = sum / pts.size();
            Map<String, Object> last = pts.get(pts.size() - 1);
            out.put("average", round(avg));
            out.put("peak", peak);
            out.put("latest", last);
            if (measure.equals("volume") && avg > 0) out.put("latest_vs_average", round((double) last.get("v") / avg));
        }
        out.put("points", pts);
        return out;
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private static String query(JsonNode args) {
        String q = requireStr(args, "query", 300);
        if (q.replaceAll("[\"()]", "").strip().length() < 3) throw ConnectorException.badRequest("query is too short (GDELT needs at least 3 characters)");
        return q;
    }

    private static String timespan(JsonNode args, String dflt) {
        String t = oneOf(args, "timespan", dflt, TIMESPANS);
        // GDELT units: h, d, w, m (= months)
        return t.endsWith("m") ? t.replace("m", "months") : t;
    }

    private JsonNode call(ConnectorContext ctx, String mode, String query, String timespan, List<String> extra) {
        Object[] kv = new Object[8 + extra.size()];
        kv[0] = "query"; kv[1] = query;
        kv[2] = "mode"; kv[3] = mode;
        kv[4] = "format"; kv[5] = "json";
        kv[6] = "timespan"; kv[7] = timespan;
        for (int i = 0; i < extra.size(); i++) kv[8 + i] = extra.get(i);
        String body = ctx.get(URI.create(url(base, kv)), Map.of(), "application/json");
        String b = body == null ? "" : body.strip();
        if (b.isEmpty() || b.equals("{}")) return ctx.mapper().createObjectNode();
        if (!b.startsWith("{")) {
            // GDELT answers query problems (and its own throttle) as plain text with HTTP 200.
            String msg = b.length() > 200 ? b.substring(0, 200) + "…" : b;
            if (b.toLowerCase().contains("limit requests")) {
                throw new ConnectorException(ConnectorException.Kind.RATE_LIMITED, "GDELT rate limit reached (one request per 5 seconds). Try again shortly.");
            }
            throw ConnectorException.badRequest("GDELT rejected the query: " + msg);
        }
        try {
            return ctx.mapper().readTree(b);
        } catch (Exception e) {
            throw ConnectorException.upstream("GDELT answered something that is not valid JSON.");
        }
    }

    static String ts(String gdelt) {
        if (gdelt == null || gdelt.isBlank()) return null;
        try {
            return LocalDateTime.parse(gdelt, GDELT_TS).toInstant(ZoneOffset.UTC).toString();
        } catch (Exception e) {
            return gdelt;
        }
    }

    private static double round(double v) {
        return Math.round(v * 10000.0) / 10000.0;
    }

    static String trim(String s, int max) {
        String t = s == null ? "" : s.replaceAll("\\s+", " ").strip();
        return t.length() > max ? t.substring(0, max) + "…" : t;
    }
}
