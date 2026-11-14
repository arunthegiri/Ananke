package com.stocktracker.connectors.fred;

import com.fasterxml.jackson.databind.JsonNode;
import com.stocktracker.connectors.Connector;
import com.stocktracker.connectors.ConnectorContext;
import com.stocktracker.connectors.ConnectorException;
import com.stocktracker.connectors.ConnectorSpec;
import com.stocktracker.connectors.ConnectorSpec.Field;
import com.stocktracker.connectors.ConnectorSpec.RateLimit;
import com.stocktracker.connectors.ConnectorTool;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

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
 * FRED + ALFRED (Federal Reserve Bank of St. Louis): 800k+ US and international economic series,
 * every vintage of each, and the release calendar.
 *
 * <p>Point-in-time: FRED keeps each value's real-time period. {@code as_of} asks ALFRED for the
 * vintage that was current on that day, so a backtest sees the first-print GDP, not today's
 * revised number. Each observation carries {@code realtime_start} = the day that value became known.
 */
@Component
public class FredConnector implements Connector {

    static final String ID = "fred";
    static final String BASE_URL = "https://api.stlouisfed.org/fred";
    static final String OPEN_END = "9999-12-31";

    static final List<String> UNITS = List.of("lin", "chg", "ch1", "pch", "pc1", "pca", "cch", "cca", "log");
    static final List<String> FREQUENCIES = List.of("d", "w", "bw", "m", "q", "sa", "a");
    static final List<String> AGGREGATIONS = List.of("avg", "sum", "eop");

    private static final ConnectorSpec SPEC = new ConnectorSpec(
            ID,
            "FRED / ALFRED (St. Louis Fed)",
            ConnectorSpec.Category.MACRO,
            ConnectorSpec.Licence.FREE_PUBLIC,
            "Federal Reserve Economic Data: rates, inflation, employment, GDP, money supply, credit spreads and "
                    + "800k+ other series, with ALFRED vintages (what was known on any past date — use for "
                    + "point-in-time backtests) and the release calendar (when the next CPI/GDP/payrolls print lands). "
                    + "Some series are third-party copyrighted; FRED marks them in the series notes.",
            "Source: Federal Reserve Bank of St. Louis, FRED®. This product uses the FRED® API but is not endorsed "
                    + "or certified by the Federal Reserve Bank of St. Louis.",
            "https://fred.stlouisfed.org/docs/api/fred/",
            List.of(Field.secret("api_key", "FRED API key",
                    "Free: create an account at fredaccount.stlouisfed.org and request an API key "
                            + "(32 lower-case letters and digits).")),
            List.of(RateLimit.perMinute(100)));   // FRED allows 120 requests a minute per key

    private final String base;
    private final Clock clock;

    public FredConnector() {
        this(BASE_URL, Clock.systemUTC());
    }

    /** Test seam: point at a fake FRED. */
    FredConnector(String base, Clock clock) {
        this.base = base;
        this.clock = clock;
    }

    @Override
    public ConnectorSpec spec() {
        return SPEC;
    }

    @Override
    public String test(ConnectorContext ctx) {
        JsonNode s = call(ctx, "/series", "series_id", "GDP").path("seriess").path(0);
        return "Connected — FRED answered (GDP: " + s.path("title").asText("?") + ", last updated "
                + s.path("last_updated").asText("?") + ").";
    }

    @Override
    public List<ConnectorTool> tools() {
        return List.of(
                new ConnectorTool(ID + "_search",
                        "Find FRED series by keywords, most popular first: id, title, frequency, units, seasonal "
                                + "adjustment, date range and last update. Well-known ids: CPIAUCSL (CPI), CPILFESL (core CPI), "
                                + "PCEPILFE (core PCE), UNRATE, PAYEMS, GDPC1 (real GDP), FEDFUNDS, DGS10, DGS2, T10Y2Y, "
                                + "BAMLH0A0HYM2 (HY spread), M2SL, ICSA (jobless claims), UMCSENT, VIXCLS.",
                        object(props(
                                "query", str("Search words, e.g. \"core pce inflation\""),
                                "limit", integer("Max series (1-25, default 10)")), List.of("query")),
                        Duration.ofHours(6), this::search,
                        a -> "Searching FRED for " + a.path("query").asText("") + "…"),
                new ConnectorTool(ID + "_observations",
                        "Values of one FRED series, newest first, each with realtime_start (the day that value became "
                                + "known). Pass as_of=YYYY-MM-DD to get the ALFRED vintage that was current on that day — "
                                + "use it for point-in-time backtests (first-print GDP, unrevised payrolls). Optional "
                                + "transformations (units=pc1 → % change from a year ago) and frequency aggregation.",
                        object(props(
                                "series_id", str("FRED series id, e.g. CPIAUCSL"),
                                "start", str("First observation date YYYY-MM-DD"),
                                "end", str("Last observation date YYYY-MM-DD"),
                                "as_of", str("Vintage date YYYY-MM-DD: values exactly as published on that day (ALFRED)"),
                                "realtime_start", str("Advanced: real-time period start YYYY-MM-DD (instead of as_of); with "
                                        + "realtime_end it returns every vintage in the window"),
                                "realtime_end", str("Advanced: real-time period end YYYY-MM-DD"),
                                "units", enumStr("lin levels (default), chg change, ch1 change from a year ago, pch % change, "
                                        + "pc1 % change from a year ago, pca compounded annual rate, cch/cca continuously "
                                        + "compounded, log", UNITS),
                                "frequency", enumStr("Aggregate to a lower frequency", FREQUENCIES),
                                "aggregation", enumStr("How to aggregate (default avg)", AGGREGATIONS),
                                "limit", integer("Most recent N observations (1-1000, default 60)")), List.of("series_id")),
                        Duration.ofMinutes(30), this::observations,
                        a -> "Reading " + a.path("series_id").asText("series") + " from FRED…"),
                new ConnectorTool(ID + "_vintage_dates",
                        "Dates on which a FRED series was published or revised (ALFRED vintages), newest first. Shows "
                                + "how often and when a number changes — pick as_of dates from here.",
                        object(props(
                                "series_id", str("FRED series id"),
                                "limit", integer("Most recent N vintages (1-100, default 24)")), List.of("series_id")),
                        Duration.ofHours(6), this::vintageDates,
                        a -> "Reading " + a.path("series_id").asText("") + " vintages…"),
                new ConnectorTool(ID + "_release_calendar",
                        "Economic data release calendar from FRED: which releases (CPI, Employment Situation, GDP, FOMC "
                                + "H.15…) publish on which dates, including scheduled future dates. Filter to one release "
                                + "or to the release that contains a given series.",
                        object(props(
                                "start", str("From date YYYY-MM-DD (default today)"),
                                "end", str("To date YYYY-MM-DD (default start + 14 days; window at most 120 days)"),
                                "series_id", str("Only the release that publishes this series, e.g. PAYEMS"),
                                "release_id", integer("Only this FRED release id (e.g. 10 = CPI, 50 = Employment Situation, 53 = GDP)"),
                                "limit", integer("Max dates (1-200, default 100)")), List.of()),
                        Duration.ofHours(1), this::releaseCalendar,
                        a -> "Reading the FRED release calendar…"));
    }

    // ── tools ────────────────────────────────────────────────────────────────

    Object search(ConnectorContext ctx, JsonNode args) {
        String q = requireStr(args, "query", 200);
        int limit = intArg(args, "limit", 10, 1, 25);
        JsonNode r = call(ctx, "/series/search", "search_text", q, "limit", limit,
                "order_by", "popularity", "sort_order", "desc");
        List<Map<String, Object>> rows = new ArrayList<>();
        for (JsonNode s : r.path("seriess")) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", s.path("id").asText());
            m.put("title", s.path("title").asText());
            m.put("frequency", s.path("frequency_short").asText(null));
            m.put("units", s.path("units_short").asText(null));
            m.put("seasonal_adjustment", s.path("seasonal_adjustment_short").asText(null));
            m.put("observation_start", s.path("observation_start").asText(null));
            m.put("observation_end", s.path("observation_end").asText(null));
            m.put("last_updated", s.path("last_updated").asText(null));
            m.put("popularity", s.path("popularity").asInt());
            rows.add(m);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("query", q);
        out.put("total", r.path("count").asInt(rows.size()));
        out.put("series", rows);
        return out;
    }

    Object observations(ConnectorContext ctx, JsonNode args) {
        String id = seriesId(args);
        LocalDate start = date(args, "start");
        LocalDate end = date(args, "end");
        if (start != null && end != null && start.isAfter(end)) throw ConnectorException.badRequest("start is after end");
        LocalDate asOf = date(args, "as_of");
        LocalDate rtStart = date(args, "realtime_start");
        LocalDate rtEnd = date(args, "realtime_end");
        if (asOf != null && (rtStart != null || rtEnd != null)) {
            throw ConnectorException.badRequest("Use either as_of or realtime_start/realtime_end, not both");
        }
        LocalDate today = LocalDate.now(clock);
        if (asOf != null) {
            if (asOf.isAfter(today)) throw ConnectorException.badRequest("as_of cannot be in the future");
            rtStart = asOf;
            rtEnd = asOf;
        }
        if (rtStart != null && rtEnd != null && rtStart.isAfter(rtEnd)) {
            throw ConnectorException.badRequest("realtime_start is after realtime_end");
        }
        String units = oneOf(args, "units", null, UNITS);
        String freq = oneOf(args, "frequency", null, FREQUENCIES);
        String agg = oneOf(args, "aggregation", null, AGGREGATIONS);
        int limit = intArg(args, "limit", 60, 1, 1000);

        JsonNode meta = call(ctx, "/series", "series_id", id).path("seriess").path(0);
        JsonNode r = call(ctx, "/series/observations", "series_id", id,
                "observation_start", start, "observation_end", end,
                "realtime_start", rtStart, "realtime_end", rtEnd,
                "units", units, "frequency", freq, "aggregation_method", agg,
                "sort_order", "desc", "limit", limit);

        List<Map<String, Object>> rows = new ArrayList<>();
        for (JsonNode o : r.path("observations")) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("date", o.path("date").asText());
            m.put("value", num(o.path("value").asText(null)));
            m.put("realtime_start", o.path("realtime_start").asText(null));
            String rte = o.path("realtime_end").asText(OPEN_END);
            if (!OPEN_END.equals(rte) && asOf == null) m.put("realtime_end", rte);
            rows.add(m);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("series_id", id);
        out.put("title", meta.path("title").asText(null));
        out.put("units", units == null || units.equals("lin") ? meta.path("units").asText(null) : unitsLabel(units));
        out.put("frequency", freq == null ? meta.path("frequency").asText(null) : freq);
        out.put("seasonal_adjustment", meta.path("seasonal_adjustment_short").asText(null));
        out.put("last_updated", meta.path("last_updated").asText(null));
        if (asOf != null) out.put("as_of", asOf.toString());
        else if (rtStart != null || rtEnd != null) {
            out.put("realtime_start", r.path("realtime_start").asText(null));
            out.put("realtime_end", r.path("realtime_end").asText(null));
        }
        out.put("count", rows.size());
        out.put("total_available", r.path("count").asInt(rows.size()));
        out.put("observations", rows);
        out.put("note", asOf != null
                ? "Values as they stood on " + asOf + " (ALFRED vintage); realtime_start is when each became known."
                : "Latest revised values; realtime_start is the vintage date. For backtests pass as_of to avoid look-ahead from revisions.");
        return out;
    }

    Object vintageDates(ConnectorContext ctx, JsonNode args) {
        String id = seriesId(args);
        int limit = intArg(args, "limit", 24, 1, 100);
        JsonNode r = call(ctx, "/series/vintagedates", "series_id", id, "sort_order", "desc", "limit", limit);
        List<String> dates = new ArrayList<>();
        r.path("vintage_dates").forEach(d -> dates.add(d.asText()));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("series_id", id);
        out.put("total_vintages", r.path("count").asInt(dates.size()));
        out.put("vintage_dates", dates);
        return out;
    }

    Object releaseCalendar(ConnectorContext ctx, JsonNode args) {
        LocalDate today = LocalDate.now(clock);
        LocalDate start = date(args, "start");
        if (start == null) start = today;
        LocalDate end = date(args, "end");
        if (end == null) end = start.plusDays(14);
        if (end.isBefore(start)) throw ConnectorException.badRequest("end is before start");
        if (ChronoUnit.DAYS.between(start, end) > 120) throw ConnectorException.badRequest("window is at most 120 days");
        int limit = intArg(args, "limit", 100, 1, 200);
        String seriesId = optStr(args, "series_id");
        Integer releaseId = null;
        if (args != null && args.hasNonNull("release_id")) {
            if (!args.path("release_id").canConvertToInt() || args.path("release_id").asInt() <= 0) {
                throw ConnectorException.badRequest("release_id must be a positive integer");
            }
            releaseId = args.path("release_id").asInt();
        }
        String releaseName = null;
        if (seriesId != null) {
            if (releaseId != null) throw ConnectorException.badRequest("Use either series_id or release_id, not both");
            JsonNode rel = call(ctx, "/series/release", "series_id", seriesId(args)).path("releases").path(0);
            if (rel.isMissingNode()) throw ConnectorException.badRequest("FRED has no release for " + seriesId);
            releaseId = rel.path("id").asInt();
            releaseName = rel.path("name").asText(null);
        }
        // FRED's real-time window cannot start in the future; ask from today and filter locally.
        LocalDate rtStart = start.isAfter(today) ? today : start;
        JsonNode r = releaseId == null
                ? call(ctx, "/releases/dates", "realtime_start", rtStart, "realtime_end", end,
                        "include_release_dates_with_no_data", "true", "order_by", "release_date", "sort_order", "asc", "limit", 1000)
                : call(ctx, "/release/dates", "release_id", releaseId, "realtime_start", rtStart, "realtime_end", end,
                        "include_release_dates_with_no_data", "true", "sort_order", "asc", "limit", 1000);
        List<Map<String, Object>> rows = new ArrayList<>();
        for (JsonNode d : r.path("release_dates")) {
            String date = d.path("date").asText("");
            if (date.isEmpty() || date.compareTo(start.toString()) < 0 || date.compareTo(end.toString()) > 0) continue;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("date", date);
            m.put("release_id", d.path("release_id").asInt());
            m.put("release", d.path("release_name").asText(releaseName));
            m.put("status", date.compareTo(today.toString()) > 0 ? "scheduled" : "published");
            rows.add(m);
            if (rows.size() >= limit) break;
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("from", start.toString());
        out.put("to", end.toString());
        if (releaseId != null) out.put("release_id", releaseId);
        if (releaseName != null) out.put("release", releaseName);
        if (seriesId != null) out.put("series_id", seriesId.toUpperCase(Locale.ROOT));
        out.put("count", rows.size());
        out.put("release_dates", rows);
        out.put("note", "Dates only (FRED does not carry release times; most US releases are 8:30 ET). Scheduled dates can move.");
        return out;
    }

    // ── plumbing ─────────────────────────────────────────────────────────────

    /** One FRED call; FRED's 400 for an unregistered key becomes AUTH, other 400s BAD_REQUEST. */
    JsonNode call(ConnectorContext ctx, String path, Object... kv) {
        Object[] all = new Object[kv.length + 4];
        System.arraycopy(kv, 0, all, 0, kv.length);
        all[kv.length] = "api_key";
        all[kv.length + 1] = ctx.require("api_key");
        all[kv.length + 2] = "file_type";
        all[kv.length + 3] = "json";
        try {
            return ctx.getJson(URI.create(url(base + path, all)));
        } catch (ConnectorException e) {
            String msg = e.getMessage() == null ? "" : e.getMessage();
            if (msg.contains("api_key")) {
                throw new ConnectorException(ConnectorException.Kind.AUTH,
                        "FRED rejected the API key. Check it in Settings → Connectors (32 lower-case letters and digits).");
            }
            if (e.kind() == ConnectorException.Kind.UPSTREAM && msg.contains("HTTP 400")) {
                java.util.regex.Matcher m = java.util.regex.Pattern.compile("\"error_message\"\\s*:\\s*\"([^\"]*)").matcher(msg);
                throw ConnectorException.badRequest("FRED: " + (m.find() ? m.group(1).replace("\\/", "/") : "bad request"));
            }
            throw e;
        }
    }

    private static String seriesId(JsonNode args) {
        String id = requireStr(args, "series_id", 40).toUpperCase(Locale.ROOT);
        if (!id.matches("^[A-Z0-9._-]{1,40}$")) throw ConnectorException.badRequest("series_id must look like CPIAUCSL or DGS10");
        return id;
    }

    private static String unitsLabel(String u) {
        return switch (u) {
            case "chg" -> "Change";
            case "ch1" -> "Change from a year ago";
            case "pch" -> "Percent change";
            case "pc1" -> "Percent change from a year ago";
            case "pca" -> "Compounded annual rate of change";
            case "cch" -> "Continuously compounded rate of change";
            case "cca" -> "Continuously compounded annual rate of change";
            case "log" -> "Natural log";
            default -> u;
        };
    }

    /** FRED values are strings; "." means missing. */
    static Number num(String s) {
        if (s == null || s.isBlank() || s.equals(".")) return null;
        try {
            BigDecimal d = new BigDecimal(s.strip());
            return d.scale() <= 0 ? (Number) d.longValue() : (Number) d.doubleValue();
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
