package com.stocktracker.connectors.bls;

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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static com.stocktracker.connectors.ToolArgs.bool;
import static com.stocktracker.connectors.ToolArgs.intArg;
import static com.stocktracker.connectors.ToolArgs.integer;
import static com.stocktracker.connectors.ToolArgs.object;
import static com.stocktracker.connectors.ToolArgs.props;
import static com.stocktracker.connectors.ToolArgs.strArray;
import static com.stocktracker.connectors.ToolArgs.strSet;

/**
 * Bureau of Labor Statistics public data API v2: CPI, payrolls, unemployment, wages, PPI, JOLTS…
 * by series id. Works without a key (25 queries a day, 10 years per query); a free registration
 * key raises that to 500 a day and 20 years, and adds catalog titles and % changes.
 *
 * <p>Point-in-time: the API serves the current (revised) values only; preliminary values are
 * flagged. For first-print values use FRED's {@code as_of} on the matching FRED series.
 */
@Component
public class BlsConnector implements Connector {

    static final String ID = "bls";
    static final String BASE_URL = "https://api.bls.gov/publicAPI/v2/timeseries/data/";

    private static final ConnectorSpec SPEC = new ConnectorSpec(
            ID,
            "U.S. Bureau of Labor Statistics",
            ConnectorSpec.Category.MACRO,
            ConnectorSpec.Licence.FREE_PUBLIC,
            "Official BLS series straight from the source: CPI (headline, core, components), nonfarm payrolls, "
                    + "unemployment and participation, average hourly earnings, PPI, JOLTS job openings and more, with "
                    + "preliminary flags. For inflation and labour-market reads around CPI and jobs-report days.",
            "Source: U.S. Bureau of Labor Statistics. BLS.gov cannot vouch for the data or analyses derived from these "
                    + "data after the data have been retrieved from BLS.gov.",
            "https://www.bls.gov/developers/api_signature_v2.htm",
            List.of(new Field("api_key", "BLS registration key (optional)",
                    "Optional. Without a key BLS allows 25 queries a day and 10 years per query; a free key from "
                            + "data.bls.gov/registrationEngine raises that to 500 a day and 20 years, and adds series titles "
                            + "and % changes.", true, true, null)),
            List.of(new RateLimit(40, Duration.ofSeconds(10)), RateLimit.perDay(500)));   // BLS: 50 per 10 s; 500/day with a key

    static final Map<String, String> COMMON = commonSeries();

    private final String base;
    private final Clock clock;

    public BlsConnector() {
        this(BASE_URL, Clock.systemUTC());
    }

    /** Test seam: point at a fake BLS. */
    BlsConnector(String base, Clock clock) {
        this.base = base;
        this.clock = clock;
    }

    private static Map<String, String> commonSeries() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("CUSR0000SA0", "CPI-U all items, SA (index)");
        m.put("CUSR0000SA0L1E", "Core CPI-U (less food & energy), SA");
        m.put("CUUR0000SA0", "CPI-U all items, NSA (use for YoY)");
        m.put("CES0000000001", "Total nonfarm payrolls, SA (thousands)");
        m.put("LNS14000000", "Unemployment rate, SA (%)");
        m.put("LNS11300000", "Labor force participation rate, SA (%)");
        m.put("CES0500000003", "Average hourly earnings, private, SA ($)");
        m.put("WPSFD4", "PPI final demand, SA");
        m.put("JTS000000000000000JOL", "JOLTS job openings, total nonfarm, SA (thousands)");
        return m;
    }

    @Override
    public ConnectorSpec spec() {
        return SPEC;
    }

    @Override
    public String test(ConnectorContext ctx) {
        int year = LocalDate.now(clock).getYear();
        JsonNode r = post(ctx, List.of("LNS14000000"), year - 1, year, false, false);
        JsonNode latest = r.path("Results").path("series").path(0).path("data").path(0);
        Optional<String> key = ctx.field("api_key");
        return "Connected — BLS answered (unemployment rate " + latest.path("value").asText("?") + "% in "
                + latest.path("periodName").asText("?") + " " + latest.path("year").asText("") + "; "
                + (key.isPresent() ? "registration key accepted" : "no key: 25 queries a day") + ").";
    }

    @Override
    public List<ConnectorTool> tools() {
        StringBuilder common = new StringBuilder();
        COMMON.forEach((k, v) -> common.append(common.isEmpty() ? "" : "; ").append(k).append(" = ").append(v));
        return List.of(new ConnectorTool(ID + "_series",
                "BLS time series by id, newest first, with preliminary flags (and 1/12-month % changes when a "
                        + "registration key is linked). Common ids: " + common + ". Monthly periods come back as YYYY-MM.",
                object(props(
                        "series_ids", strArray("1-20 BLS series ids, e.g. [\"CUSR0000SA0\",\"LNS14000000\"]"),
                        "start_year", integer("First year (default: last year). Without a key at most 10 years per call"),
                        "end_year", integer("Last year (default: this year)"),
                        "limit", integer("Most recent N observations per series (1-120, default 13)"),
                        "annual_average", bool("Include annual averages (needs a key)")), List.of("series_ids")),
                Duration.ofMinutes(30), this::series,
                a -> "Reading BLS series…"));
    }

    Object series(ConnectorContext ctx, JsonNode args) {
        Set<String> ids = strSet(args, "series_ids", true);
        if (ids.isEmpty()) throw ConnectorException.badRequest("series_ids is required, e.g. [\"CUSR0000SA0\"]");
        if (ids.size() > 20) throw ConnectorException.badRequest("At most 20 series_ids per call");
        for (String id : ids) {
            if (!id.matches("^[A-Z0-9_#-]{4,40}$")) throw ConnectorException.badRequest("Not a BLS series id: " + id);
        }
        boolean keyed = ctx.field("api_key").isPresent();
        int thisYear = LocalDate.now(clock).getYear();
        int end = intArg(args, "end_year", thisYear, 1913, thisYear);
        int start = intArg(args, "start_year", end - 1, 1913, end);
        int maxSpan = keyed ? 20 : 10;
        if (end - start + 1 > maxSpan) {
            throw ConnectorException.badRequest("At most " + maxSpan + " years per call" + (keyed ? "" : " without a registration key"));
        }
        int limit = intArg(args, "limit", 13, 1, 120);
        boolean annual = args != null && args.path("annual_average").asBoolean(false);

        JsonNode r = post(ctx, new ArrayList<>(ids), start, end, keyed, keyed && annual);
        List<Map<String, Object>> out = new ArrayList<>();
        for (JsonNode s : r.path("Results").path("series")) {
            String id = s.path("seriesID").asText();
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("series_id", id);
            String title = s.path("catalog").path("series_title").asText(null);
            m.put("title", title != null ? title : COMMON.get(id));
            List<Map<String, Object>> obs = new ArrayList<>();
            for (JsonNode d : s.path("data")) {
                if (obs.size() >= limit) break;
                Map<String, Object> o = new LinkedHashMap<>();
                o.put("period", period(d.path("year").asText(), d.path("period").asText()));
                o.put("value", num(d.path("value").asText(null)));
                List<String> notes = new ArrayList<>();
                boolean prelim = false;
                for (JsonNode f : d.path("footnotes")) {
                    if ("P".equals(f.path("code").asText())) prelim = true;
                    else if (f.hasNonNull("text") && !f.path("text").asText().isBlank()) notes.add(f.path("text").asText());
                }
                if (prelim) o.put("preliminary", true);
                if (!notes.isEmpty()) o.put("notes", notes);
                JsonNode pct = d.path("calculations").path("pct_changes");
                if (pct.isObject()) {
                    if (pct.hasNonNull("1")) o.put("pct_change_1m", num(pct.path("1").asText()));
                    if (pct.hasNonNull("12")) o.put("pct_change_12m", num(pct.path("12").asText()));
                }
                obs.add(o);
            }
            m.put("observations", obs);
            if (obs.isEmpty()) m.put("warning", "No data — check the series id and years");
            out.add(m);
        }
        List<String> messages = new ArrayList<>();
        r.path("message").forEach(x -> {
            String t = x.asText();
            if (!t.startsWith("Unable to get Catalog Data")) messages.add(t);
        });
        Map<String, Object> res = new LinkedHashMap<>();
        res.put("years", start + "-" + end);
        res.put("series", out);
        if (!messages.isEmpty()) res.put("messages", messages);
        res.put("note", "Current (revised) values; 'preliminary' marks values BLS will revise. BLS releases are at "
                + "8:30 AM ET — check fred_release_calendar for dates. For as-first-published values use FRED/ALFRED as_of.");
        return res;
    }

    /** M01..M12 → YYYY-MM, M13 → YYYY annual avg, Q01..Q04 → YYYY-Qn, S01/S02 → YYYY-Hn, A01 → YYYY. */
    static String period(String year, String p) {
        if (p.matches("^M(0[1-9]|1[0-2])$")) return year + "-" + p.substring(1);
        if (p.equals("M13") || p.equals("A01")) return year + " (annual)";
        if (p.matches("^Q0[1-5]$")) return year + "-Q" + p.substring(2);
        if (p.matches("^S0[1-3]$")) return year + "-H" + p.substring(2);
        return year + " " + p;
    }

    JsonNode post(ConnectorContext ctx, List<String> ids, int start, int end, boolean keyed, boolean annual) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("seriesid", ids);
        body.put("startyear", String.valueOf(start));
        body.put("endyear", String.valueOf(end));
        ctx.field("api_key").ifPresent(k -> {
            body.put("registrationkey", k);
            body.put("catalog", true);
            body.put("calculations", true);
        });
        if (annual) body.put("annualaverage", true);
        JsonNode r = ctx.postJson(URI.create(base), Map.of(), body);
        String status = r.path("status").asText("");
        if (!status.equals("REQUEST_SUCCEEDED")) {
            StringBuilder msg = new StringBuilder();
            r.path("message").forEach(m -> msg.append(msg.isEmpty() ? "" : " ").append(m.asText()));
            String text = msg.toString();
            String lower = text.toLowerCase(Locale.ROOT);
            if (lower.contains("key") && (lower.contains("invalid") || lower.contains("not valid") || lower.contains("expired"))) {
                throw new ConnectorException(ConnectorException.Kind.AUTH,
                        "BLS rejected the registration key. Check it in Settings → Connectors, or remove it to use the keyless limits.");
            }
            if (lower.contains("threshold") || lower.contains("daily")) {
                throw new ConnectorException(ConnectorException.Kind.RATE_LIMITED,
                        "BLS daily query limit reached" + (ctx.field("api_key").isPresent() ? "" : " (25 a day without a key — add a free registration key)") + ".");
            }
            if (status.equals("REQUEST_NOT_PROCESSED")) {
                throw ConnectorException.badRequest("BLS did not process the request: " + snippet(text));
            }
            throw ConnectorException.upstream("BLS failed: " + status + " " + snippet(text));
        }
        return r;
    }

    private static String snippet(String s) {
        return s.length() > 300 ? s.substring(0, 300) + "…" : s;
    }

    static Number num(String s) {
        if (s == null) return null;
        String t = s.replace(",", "").strip();
        if (t.isEmpty() || t.equals("-")) return null;
        try {
            BigDecimal d = new BigDecimal(t);
            return d.scale() <= 0 ? (Number) d.longValue() : (Number) d.doubleValue();
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
