package com.stocktracker.connectors.wikipedia;

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
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.stocktracker.connectors.ToolArgs.date;
import static com.stocktracker.connectors.ToolArgs.enc;
import static com.stocktracker.connectors.ToolArgs.intArg;
import static com.stocktracker.connectors.ToolArgs.integer;
import static com.stocktracker.connectors.ToolArgs.object;
import static com.stocktracker.connectors.ToolArgs.optStr;
import static com.stocktracker.connectors.ToolArgs.props;
import static com.stocktracker.connectors.ToolArgs.requireStr;
import static com.stocktracker.connectors.ToolArgs.str;
import static com.stocktracker.connectors.ToolArgs.url;

/**
 * Wikipedia pageviews (Wikimedia Analytics REST API): daily human views of an article — a
 * company, product or person — as a retail-attention gauge, plus the day's most-viewed articles
 * with their change from the previous day (top movers). Keyless; Wikimedia requires a
 * User-Agent with contact details.
 *
 * <p>Point-in-time: a day's counts are published after that UTC day ends (typically within a
 * few hours to a day); every point carries its UTC date.
 */
@Component
public class WikipediaConnector implements Connector {

    static final String ID = "wikipedia";
    static final String REST_URL = "https://wikimedia.org/api/rest_v1";
    /** {@code %s} = project host prefix, e.g. {@code en.wikipedia}. */
    static final String SITE_URL = "https://%s.org";

    private static final DateTimeFormatter DAY = DateTimeFormatter.BASIC_ISO_DATE;

    private static final ConnectorSpec SPEC = new ConnectorSpec(
            ID,
            "Wikipedia pageviews",
            ConnectorSpec.Category.ALT_DATA,
            ConnectorSpec.Licence.FREE_PUBLIC,
            "Daily Wikipedia pageviews for a company, product or person (human traffic only) with spike statistics, "
                    + "and each day's most-viewed articles with their change from the day before — an attention signal "
                    + "that often leads news flow.",
            "Pageview data: Wikimedia Foundation (CC0)",
            "https://doc.wikimedia.org/generated-data-platform/aqs/analytics-api/reference/page-views.html",
            List.of(new Field("contact", "Contact (optional)",
                    "Wikimedia asks API clients to include contact details in the User-Agent, e.g. an email.",
                    false, true, "jane@example.com")),
            // Wikimedia asks clients to stay well below 100 requests/s.
            List.of(RateLimit.perSecond(10)));

    private final Clock clock;
    private final String rest;
    private final String site;

    public WikipediaConnector() {
        this(Clock.systemUTC(), REST_URL, SITE_URL);
    }

    /** Test seam: point at a fake Wikimedia; {@code site} is a format string taking the project. */
    WikipediaConnector(Clock clock, String rest, String site) {
        this.clock = clock;
        this.rest = rest;
        this.site = site;
    }

    @Override
    public ConnectorSpec spec() {
        return SPEC;
    }

    @Override
    public String test(ConnectorContext ctx) {
        LocalDate d = yesterday().minusDays(1);
        JsonNode j = ctx.getJson(URI.create(rest + "/metrics/pageviews/per-article/en.wikipedia/all-access/user/Main_Page/daily/"
                + DAY.format(d) + "/" + DAY.format(d)), headers(ctx));
        long v = j.path("items").path(0).path("views").asLong();
        return "Connected — Wikimedia answered (Main Page had " + v + " views on " + d + ").";
    }

    @Override
    public List<ConnectorTool> tools() {
        return List.of(
                new ConnectorTool(ID + "_pageviews",
                        "Daily human pageviews of a Wikipedia article (redirects such as NVIDIA → Nvidia are followed) "
                                + "over the last N days, with the average, median, peak and the latest day versus the median "
                                + "of the days before it — a spike ratio. Use the article title as on Wikipedia, e.g. "
                                + "\"Nvidia\", \"Jensen Huang\", \"GLP-1 receptor agonist\".",
                        object(props(
                                "article", str("Article title, e.g. Nvidia"),
                                "days", integer("Window in days ending yesterday (7-365, default 60)"),
                                "end", str("Last day YYYY-MM-DD (default yesterday UTC)"),
                                "project", str("Wiki, e.g. en.wikipedia (default), de.wikipedia, ja.wikipedia"),
                                "max_points", integer("Return only the last N daily points (7-120, default 60); stats use the whole window")), List.of("article")),
                        Duration.ofHours(3), this::pageviews,
                        a -> "Reading Wikipedia pageviews for " + a.path("article").asText("") + "…"),
                new ConnectorTool(ID + "_top",
                        "Most-viewed Wikipedia articles on a day (navigation pages removed), each with its views the "
                                + "day before and the change — what the public is suddenly reading about.",
                        object(props(
                                "date", str("Day YYYY-MM-DD (default yesterday UTC)"),
                                "project", str("Wiki, e.g. en.wikipedia (default)"),
                                "sort", Map.of("type", "string", "enum", List.of("views", "change"),
                                        "description", "views (default) = most viewed; change = biggest jump vs the day before"),
                                "limit", integer("Max articles (1-50, default 25)")), List.of()),
                        Duration.ofHours(6), this::top,
                        a -> "Reading top Wikipedia articles…"));
    }

    // ── tools ────────────────────────────────────────────────────────────────

    Object pageviews(ConnectorContext ctx, JsonNode args) {
        String project = project(args);
        String article = requireStr(args, "article", 250).replace(' ', '_');
        int days = intArg(args, "days", 60, 7, 365);
        int maxPoints = intArg(args, "max_points", 60, 7, 120);
        LocalDate end = date(args, "end");
        if (end == null) end = yesterday();
        if (end.isAfter(yesterday())) throw ConnectorException.badRequest("end must be yesterday (UTC) or earlier; today's views are not published yet");
        LocalDate start = end.minusDays(days - 1L);

        String title = resolve(ctx, project, article);
        JsonNode j;
        try {
            j = ctx.getJson(URI.create(rest + "/metrics/pageviews/per-article/" + project + "/all-access/user/"
                    + pathSeg(title) + "/daily/" + DAY.format(start) + "/" + DAY.format(end)), headers(ctx));
        } catch (ConnectorException e) {
            if (e.kind() == ConnectorException.Kind.BAD_REQUEST) {
                throw ConnectorException.badRequest("No pageviews for \"" + title + "\" on " + project
                        + " in that window — check the exact article title.");
            }
            throw e;
        }
        Map<LocalDate, Long> byDay = new HashMap<>();
        for (JsonNode it : j.path("items")) {
            String ts = it.path("timestamp").asText("");
            if (ts.length() >= 8) byDay.put(LocalDate.parse(ts.substring(0, 8), DAY), it.path("views").asLong());
        }
        // The API omits days with zero views; fill them so the series is complete.
        List<Long> all = new ArrayList<>();
        List<Map<String, Object>> pts = new ArrayList<>();
        for (LocalDate d = start; !d.isAfter(end); d = d.plusDays(1)) {
            long v = byDay.getOrDefault(d, 0L);
            all.add(v);
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("date", d.toString());
            p.put("views", v);
            pts.add(p);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("project", project);
        out.put("article", title);
        if (!title.equals(article)) out.put("requested", article);
        out.put("from", start.toString());
        out.put("to", end.toString());
        long sum = all.stream().mapToLong(Long::longValue).sum();
        out.put("total", sum);
        out.put("average", Math.round((double) sum / all.size()));
        List<Long> before = all.subList(0, all.size() - 1);
        long median = median(before);
        out.put("median_before_latest", median);
        int peakIdx = 0;
        for (int i = 1; i < all.size(); i++) if (all.get(i) > all.get(peakIdx)) peakIdx = i;
        out.put("peak", pts.get(peakIdx));
        long latest = all.get(all.size() - 1);
        out.put("latest", pts.get(pts.size() - 1));
        if (median > 0) out.put("latest_vs_median", Math.round((double) latest / median * 100) / 100.0);
        out.put("points", pts.size() > maxPoints ? new ArrayList<>(pts.subList(pts.size() - maxPoints, pts.size())) : pts);
        return out;
    }

    Object top(ConnectorContext ctx, JsonNode args) {
        String project = project(args);
        LocalDate day = date(args, "date");
        if (day == null) day = yesterday();
        if (day.isAfter(yesterday())) throw ConnectorException.badRequest("date must be yesterday (UTC) or earlier");
        String sort = optStr(args, "sort") == null ? "views" : optStr(args, "sort");
        if (!sort.equals("views") && !sort.equals("change")) throw ConnectorException.badRequest("sort must be views or change");
        int limit = intArg(args, "limit", 25, 1, 50);

        List<JsonNode> today = topList(ctx, project, day);
        Map<String, Long> prev = new HashMap<>();
        for (JsonNode a : topList(ctx, project, day.minusDays(1))) prev.put(a.path("article").asText(), a.path("views").asLong());

        List<Map<String, Object>> rows = new ArrayList<>();
        for (JsonNode a : today) {
            String name = a.path("article").asText("");
            if (navigation(name)) continue;
            long v = a.path("views").asLong();
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("article", name);
            r.put("rank", a.path("rank").asInt());
            r.put("views", v);
            Long p = prev.get(name);
            r.put("views_prev_day", p);   // null = not in the previous day's top 1000
            if (p != null && p > 0) r.put("change", Math.round((double) v / p * 100) / 100.0);
            rows.add(r);
        }
        if (sort.equals("change")) {
            rows.sort((x, y) -> Double.compare(changeKey(y), changeKey(x)));
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("project", project);
        out.put("date", day.toString());
        out.put("sort", sort);
        out.put("articles", rows.size() > limit ? new ArrayList<>(rows.subList(0, limit)) : rows);
        return out;
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private static double changeKey(Map<String, Object> r) {
        Object c = r.get("change");
        if (c != null) return (double) c;
        // New entrant to the top 1000: rank it as a large jump, scaled by its views.
        return r.get("views_prev_day") == null ? 1000.0 + ((Number) r.get("views")).doubleValue() / 1e9 : 0;
    }

    private List<JsonNode> topList(ConnectorContext ctx, String project, LocalDate d) {
        JsonNode j = ctx.getJson(URI.create(rest + "/metrics/pageviews/top/" + project + "/all-access/"
                + d.getYear() + "/" + String.format("%02d", d.getMonthValue()) + "/" + String.format("%02d", d.getDayOfMonth())), headers(ctx));
        List<JsonNode> out = new ArrayList<>();
        j.path("items").path(0).path("articles").forEach(out::add);
        return out;
    }

    static boolean navigation(String name) {
        return name.equals("Main_Page") || name.equals("-") || name.contains(":")
                && name.matches("^(Special|Wikipedia|Portal|Help|File|Category|Template|Talk|User|Spécial|Spezial|Especial|Speciale|Служебная|特別|特殊):.*");
    }

    /** Follows redirects (NVIDIA → Nvidia) via the wiki's action API; falls back to the title as given. */
    private String resolve(ConnectorContext ctx, String project, String article) {
        try {
            JsonNode j = ctx.getJson(URI.create(url(String.format(site, project) + "/w/api.php",
                    "action", "query", "titles", article.replace('_', ' '), "redirects", 1, "format", "json", "formatversion", 2)), headers(ctx));
            JsonNode page = j.path("query").path("pages").path(0);
            if (page.path("missing").asBoolean(false)) {
                throw ConnectorException.badRequest("No Wikipedia article titled \"" + article.replace('_', ' ') + "\" on " + project + ".");
            }
            String t = page.path("title").asText("");
            return t.isEmpty() ? article : t.replace(' ', '_');
        } catch (ConnectorException e) {
            if (e.kind() == ConnectorException.Kind.BAD_REQUEST || e.kind() == ConnectorException.Kind.RATE_LIMITED) throw e;
            return article;
        }
    }

    private static String project(JsonNode args) {
        String p = optStr(args, "project");
        if (p == null) return "en.wikipedia";
        p = p.toLowerCase().replaceFirst("\\.org$", "");
        if (!p.matches("^[a-z][a-z-]{1,15}\\.wikipedia$")) throw ConnectorException.badRequest("project must look like en.wikipedia");
        return p;
    }

    private LocalDate yesterday() {
        return LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC).minusDays(1);
    }

    private static Map<String, String> headers(ConnectorContext ctx) {
        String ua = ctx.field("contact").map(c -> "Ananke/1 (" + c.replaceAll("[\\r\\n]", " ") + ")")
                .orElse("Ananke/1 (self-hosted research terminal)");
        return Map.of("User-Agent", ua);
    }

    static String pathSeg(String title) {
        return enc(title).replace("+", "%20");
    }

    static long median(List<Long> xs) {
        if (xs.isEmpty()) return 0;
        List<Long> s = new ArrayList<>(xs);
        s.sort(Long::compare);
        int n = s.size();
        return n % 2 == 1 ? s.get(n / 2) : (s.get(n / 2 - 1) + s.get(n / 2)) / 2;
    }
}
