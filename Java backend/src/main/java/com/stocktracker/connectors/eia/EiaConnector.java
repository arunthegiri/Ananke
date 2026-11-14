package com.stocktracker.connectors.eia;

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
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static com.stocktracker.connectors.ToolArgs.date;
import static com.stocktracker.connectors.ToolArgs.intArg;
import static com.stocktracker.connectors.ToolArgs.integer;
import static com.stocktracker.connectors.ToolArgs.object;
import static com.stocktracker.connectors.ToolArgs.optStr;
import static com.stocktracker.connectors.ToolArgs.props;
import static com.stocktracker.connectors.ToolArgs.requireStr;
import static com.stocktracker.connectors.ToolArgs.str;
import static com.stocktracker.connectors.ToolArgs.strArray;
import static com.stocktracker.connectors.ToolArgs.strSet;
import static com.stocktracker.connectors.ToolArgs.url;

/**
 * U.S. Energy Information Administration API v2: every EIA dataset through one route-based API,
 * plus named tools for the weekly petroleum status report, the weekly natural gas storage report
 * and daily spot prices.
 *
 * <p>Point-in-time: weekly petroleum data for the week ending Friday is released the following
 * Wednesday 10:30 ET; gas storage the following Thursday 10:30 ET (a day later after holidays).
 * Rows carry {@code released_est} from that schedule.
 */
@Component
public class EiaConnector implements Connector {

    static final String ID = "eia";
    static final String BASE_URL = "https://api.eia.gov/v2";

    /** Weekly petroleum stocks (route petroleum/stoc/wstk), thousand barrels. */
    static final Map<String, String> PETROLEUM = petroleum();
    static final List<String> PETROLEUM_DEFAULT = List.of("crude", "cushing", "gasoline", "distillate", "spr");
    /** Weekly working gas in storage (route natural-gas/stor/wkly), Bcf. */
    static final Map<String, String> GAS_STORAGE = gasStorage();
    /** Daily spot prices: key → {route, series}. */
    static final Map<String, String[]> SPOT = spot();

    private static final ConnectorSpec SPEC = new ConnectorSpec(
            ID,
            "U.S. Energy Information Administration",
            ConnectorSpec.Category.MACRO,
            ConnectorSpec.Licence.FREE_PUBLIC,
            "EIA energy data: weekly crude, gasoline and distillate inventories (incl. Cushing and the SPR), weekly "
                    + "natural gas storage by region, WTI/Brent/Henry Hub spot prices, and any other EIA dataset (production, "
                    + "refinery runs, electricity, STEO forecasts) through its route API. For oil and gas trades around the "
                    + "Wednesday/Thursday reports.",
            "Source: U.S. Energy Information Administration (EIA)",
            "https://www.eia.gov/opendata/documentation.php",
            List.of(Field.secret("api_key", "EIA API key",
                    "Free: register at eia.gov/opendata (the key arrives by email).")),
            List.of(RateLimit.perSecond(4), RateLimit.perMinute(140)));   // EIA: bursts under 5/s, ~9,000/hour sustained

    private final String base;

    public EiaConnector() {
        this(BASE_URL);
    }

    /** Test seam: point at a fake EIA. */
    EiaConnector(String base) {
        this.base = base;
    }

    private static Map<String, String> petroleum() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("crude", "WCESTUS1");          // commercial crude, excluding SPR
        m.put("cushing", "W_EPC0_SAX_YCUOK_MBBL");
        m.put("gasoline", "WGTSTUS1");
        m.put("distillate", "WDISTUS1");
        m.put("spr", "WCSSTUS1");
        m.put("jet", "WKJSTUS1");
        m.put("residual", "WRESTUS1");
        m.put("propane", "WPRSTUS1");
        m.put("crude_incl_spr", "WCRSTUS1");
        m.put("total", "WTTSTUS1");
        return m;
    }

    private static Map<String, String> gasStorage() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("lower48", "NW2_EPG0_SWO_R48_BCF");
        m.put("east", "NW2_EPG0_SWO_R31_BCF");
        m.put("midwest", "NW2_EPG0_SWO_R32_BCF");
        m.put("south_central", "NW2_EPG0_SWO_R33_BCF");
        m.put("mountain", "NW2_EPG0_SWO_R34_BCF");
        m.put("pacific", "NW2_EPG0_SWO_R35_BCF");
        return m;
    }

    private static Map<String, String[]> spot() {
        Map<String, String[]> m = new LinkedHashMap<>();
        m.put("wti", new String[] {"petroleum/pri/spt", "RWTC"});
        m.put("brent", new String[] {"petroleum/pri/spt", "RBRTE"});
        m.put("henry_hub", new String[] {"natural-gas/pri/fut", "RNGWHHD"});
        return m;
    }

    @Override
    public ConnectorSpec spec() {
        return SPEC;
    }

    @Override
    public String test(ConnectorContext ctx) {
        JsonNode r = get(ctx, "petroleum/stoc/wstk", List.of());
        return "Connected — EIA answered (weekly petroleum stocks through " + r.path("response").path("endPeriod").asText("?") + ").";
    }

    @Override
    public List<ConnectorTool> tools() {
        return List.of(
                new ConnectorTool(ID + "_browse",
                        "Explore the EIA API tree: a route's child routes, or for a data route its frequencies, facets "
                                + "(filters such as series, duoarea, product, process) and data columns. Start with no route.",
                        object(props("route", str("Route path, e.g. petroleum, petroleum/stoc/wstk, natural-gas/stor/wkly, "
                                + "electricity/retail-sales, steo. Empty for the top level.")), List.of()),
                        Duration.ofHours(24), this::browse,
                        a -> "Browsing EIA " + a.path("route").asText("") + "…"),
                new ConnectorTool(ID + "_data",
                        "Data rows from any EIA data route, newest first. Filter with facets, e.g. route "
                                + "petroleum/stoc/wstk, facets {\"series\":[\"WCESTUS1\"]}; route petroleum/pri/spt, facets "
                                + "{\"series\":[\"RWTC\"]}. Use eia_browse to find routes and facet ids.",
                        object(props(
                                "route", str("Data route, e.g. petroleum/stoc/wstk"),
                                "frequency", str("e.g. weekly, daily, monthly, annual"),
                                "facets", Map.of("type", "object", "description",
                                        "Facet filters: {facetId: [values]}, e.g. {\"series\":[\"WCESTUS1\"],\"duoarea\":[\"NUS\"]}",
                                        "additionalProperties", Map.of("type", "array", "items", Map.of("type", "string"))),
                                "columns", strArray("Data columns (default [\"value\"])"),
                                "start", str("From period, e.g. 2026-01-01 or 2026-01"),
                                "end", str("To period"),
                                "limit", integer("Max rows (1-200, default 40)")), List.of("route")),
                        Duration.ofMinutes(30), this::data,
                        a -> "Reading EIA " + a.path("route").asText("") + "…"),
                new ConnectorTool(ID + "_petroleum_inventories",
                        "Weekly U.S. petroleum inventories (Weekly Petroleum Status Report), newest week first, with "
                                + "week-over-week change in thousand barrels. Series: " + String.join(", ", PETROLEUM.keySet())
                                + " (crude = commercial excl. SPR). Released Wednesdays 10:30 ET.",
                        object(props(
                                "series", strArray("Which stocks (default crude, cushing, gasoline, distillate, spr)"),
                                "weeks", integer("Weeks (1-52, default 6)")), List.of()),
                        Duration.ofMinutes(30), this::petroleumInventories,
                        a -> "Reading EIA weekly petroleum stocks…"),
                new ConnectorTool(ID + "_natural_gas_storage",
                        "Weekly working natural gas in underground storage (Bcf), Lower 48 and the five regions, newest "
                                + "week first, with the weekly injection/withdrawal. Released Thursdays 10:30 ET.",
                        object(props("weeks", integer("Weeks (1-52, default 6)")), List.of()),
                        Duration.ofMinutes(30), this::gasStorage,
                        a -> "Reading EIA natural gas storage…"),
                new ConnectorTool(ID + "_spot_prices",
                        "Daily spot prices, newest first: WTI Cushing and Brent ($/bbl), Henry Hub natural gas ($/MMBtu). "
                                + "EIA posts them with a lag of a few days (weekly updates).",
                        object(props(
                                "series", strArray("Any of wti, brent, henry_hub (default all)"),
                                "days", integer("Trading days (1-60, default 10)")), List.of()),
                        Duration.ofHours(1), this::spotPrices,
                        a -> "Reading EIA spot prices…"));
    }

    // ── tools ────────────────────────────────────────────────────────────────

    Object browse(ConnectorContext ctx, JsonNode args) {
        String route = route(optStr(args, "route"), true);
        JsonNode r = get(ctx, route, List.of()).path("response");
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("route", route);
        out.put("name", r.path("name").asText(r.path("id").asText(null)));
        out.put("description", r.path("description").asText(null));
        if (r.path("routes").isArray()) {
            List<Map<String, Object>> kids = new ArrayList<>();
            for (JsonNode k : r.path("routes")) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("route", (route.isEmpty() ? "" : route + "/") + k.path("id").asText());
                m.put("name", k.path("name").asText(null));
                kids.add(m);
            }
            out.put("routes", kids);
        }
        if (r.path("frequency").isArray()) {
            List<String> f = new ArrayList<>();
            r.path("frequency").forEach(x -> f.add(x.path("id").asText()));
            out.put("frequencies", f);
        }
        if (r.path("facets").isArray()) {
            List<Map<String, Object>> f = new ArrayList<>();
            r.path("facets").forEach(x -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", x.path("id").asText());
                m.put("description", x.path("description").asText(null));
                f.add(m);
            });
            out.put("facets", f);
            out.put("facet_values_hint", "Call eia_data with a facet filter, or open route + '/facet/<id>' in eia_browse to list values.");
        }
        if (r.path("data").isObject()) {
            List<String> cols = new ArrayList<>();
            r.path("data").fieldNames().forEachRemaining(cols::add);
            out.put("data_columns", cols);
        }
        if (r.hasNonNull("startPeriod")) out.put("start_period", r.path("startPeriod").asText());
        if (r.hasNonNull("endPeriod")) out.put("end_period", r.path("endPeriod").asText());
        if (r.path("facets").isArray() && r.path("facets").size() > 0 && r.path("facets").path(0).has("name")) {
            // a /facet/<id> listing: values with names
            List<Map<String, Object>> vals = new ArrayList<>();
            for (JsonNode x : r.path("facets")) {
                if (vals.size() >= 200) break;
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", x.path("id").asText());
                m.put("name", x.path("name").asText(null));
                vals.add(m);
            }
            out.remove("facets");
            out.remove("facet_values_hint");
            out.put("facet_values", vals);
            out.put("total_values", r.path("totalFacets").asInt(vals.size()));
        }
        return out;
    }

    Object data(ConnectorContext ctx, JsonNode args) {
        String route = route(requireStr(args, "route", 120), false);
        String freq = optStr(args, "frequency");
        if (freq != null && !freq.matches("^[a-z0-9-]{1,30}$")) throw ConnectorException.badRequest("frequency must look like weekly or monthly");
        Set<String> asked = strSet(args, "columns", false);
        final Set<String> cols = asked.isEmpty() ? Set.of("value") : asked;
        if (cols.size() > 10) throw ConnectorException.badRequest("At most 10 columns");
        for (String c : cols) if (!c.matches("^[A-Za-z0-9_-]{1,40}$")) throw ConnectorException.badRequest("Bad column: " + c);
        String start = period(args, "start");
        String end = period(args, "end");
        int limit = intArg(args, "limit", 40, 1, 200);

        List<Object> q = new ArrayList<>();
        if (freq != null) { q.add("frequency"); q.add(freq); }
        int i = 0;
        for (String c : cols) { q.add("data[" + i++ + "]"); q.add(c); }
        JsonNode facets = args == null ? null : args.get("facets");
        if (facets != null && !facets.isNull()) {
            if (!facets.isObject()) throw ConnectorException.badRequest("facets must be an object like {\"series\":[\"WCESTUS1\"]}");
            if (facets.size() > 6) throw ConnectorException.badRequest("At most 6 facets");
            Iterator<Map.Entry<String, JsonNode>> it = facets.fields();
            while (it.hasNext()) {
                Map.Entry<String, JsonNode> e = it.next();
                if (!e.getKey().matches("^[A-Za-z0-9_-]{1,40}$")) throw ConnectorException.badRequest("Bad facet id: " + e.getKey());
                List<String> vals = new ArrayList<>();
                if (e.getValue().isArray()) e.getValue().forEach(v -> vals.add(v.asText()));
                else vals.add(e.getValue().asText());
                if (vals.size() > 30) throw ConnectorException.badRequest("At most 30 values per facet");
                for (String v : vals) {
                    if (!v.matches("^[A-Za-z0-9_ .,/&()-]{1,60}$")) throw ConnectorException.badRequest("Bad facet value: " + v);
                    q.add("facets[" + e.getKey() + "][]");
                    q.add(v);
                }
            }
        }
        if (start != null) { q.add("start"); q.add(start); }
        if (end != null) { q.add("end"); q.add(end); }
        q.add("sort[0][column]"); q.add("period");
        q.add("sort[0][direction]"); q.add("desc");
        q.add("length"); q.add(limit);
        JsonNode r = get(ctx, route + "/data", q).path("response");
        List<Map<String, Object>> rows = new ArrayList<>();
        for (JsonNode d : r.path("data")) {
            Map<String, Object> m = new LinkedHashMap<>();
            d.fields().forEachRemaining(e -> {
                String k = e.getKey();
                String v = e.getValue().isNull() ? null : e.getValue().asText();
                m.put(k, cols.contains(k) ? numOrText(v) : v);
            });
            rows.add(m);
            if (rows.size() >= limit) break;
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("route", route);
        out.put("frequency", r.path("frequency").asText(freq));
        out.put("total", r.path("total").asInt(rows.size()));
        out.put("rows", rows);
        return out;
    }

    Object petroleumInventories(ConnectorContext ctx, JsonNode args) {
        Map<String, String> chosen = choose(args, PETROLEUM, PETROLEUM_DEFAULT);
        int weeks = intArg(args, "weeks", 6, 1, 52);
        List<Map<String, Object>> series = weekly(ctx, "petroleum/stoc/wstk", chosen, weeks, 5);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("units", "thousand barrels");
        out.put("series", series);
        out.put("note", "Week ending Friday; released_est = the following Wednesday 10:30 ET (Thursday after a federal "
                + "holiday). Use released_est for point-in-time backtests.");
        return out;
    }

    Object gasStorage(ConnectorContext ctx, JsonNode args) {
        int weeks = intArg(args, "weeks", 6, 1, 52);
        List<Map<String, Object>> series = weekly(ctx, "natural-gas/stor/wkly", GAS_STORAGE, weeks, 6);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("units", "billion cubic feet (Bcf)");
        out.put("series", series);
        out.put("note", "Week ending Friday; change = net injection (+) or withdrawal (−). released_est = the following "
                + "Thursday 10:30 ET (shifts around holidays).");
        return out;
    }

    Object spotPrices(ConnectorContext ctx, JsonNode args) {
        Set<String> want = strSet(args, "series", false);
        List<String> keys = new ArrayList<>();
        if (want.isEmpty()) keys.addAll(SPOT.keySet());
        for (String w : want) {
            String k = w.toLowerCase(Locale.ROOT).replace(' ', '_');
            if (!SPOT.containsKey(k)) throw ConnectorException.badRequest("series must be among " + SPOT.keySet());
            keys.add(k);
        }
        int days = intArg(args, "days", 10, 1, 60);
        Map<String, Map<String, String>> byRoute = new LinkedHashMap<>();
        for (String k : keys) byRoute.computeIfAbsent(SPOT.get(k)[0], x -> new LinkedHashMap<>()).put(k, SPOT.get(k)[1]);
        List<Map<String, Object>> series = new ArrayList<>();
        for (Map.Entry<String, Map<String, String>> e : byRoute.entrySet()) {
            series.addAll(pivot(fetch(ctx, e.getKey(), "daily", e.getValue().values(), days), e.getValue(), days, -1));
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("series", series);
        out.put("note", "Daily closing spot prices (Refinitiv/NGI via EIA). EIA posts them with a few days' lag, so the "
                + "newest date here may trail the market.");
        return out;
    }

    // ── weekly helpers ───────────────────────────────────────────────────────

    private List<Map<String, Object>> weekly(ConnectorContext ctx, String route, Map<String, String> chosen, int weeks, int releaseLagDays) {
        List<JsonNode> rows = fetch(ctx, route, "weekly", chosen.values(), weeks + 1);
        return pivot(rows, chosen, weeks, releaseLagDays);
    }

    private List<JsonNode> fetch(ConnectorContext ctx, String route, String freq, java.util.Collection<String> seriesIds, int perSeries) {
        List<Object> q = new ArrayList<>(List.of("frequency", freq, "data[0]", "value"));
        for (String s : seriesIds) { q.add("facets[series][]"); q.add(s); }
        q.addAll(List.of("sort[0][column]", "period", "sort[0][direction]", "desc", "length", perSeries * seriesIds.size()));
        List<JsonNode> out = new ArrayList<>();
        get(ctx, route + "/data", q).path("response").path("data").forEach(out::add);
        return out;
    }

    /** Rows → one entry per series (in the order asked), newest first, with change vs the prior period. */
    private static List<Map<String, Object>> pivot(List<JsonNode> rows, Map<String, String> chosen, int n, int releaseLagDays) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map.Entry<String, String> c : chosen.entrySet()) {
            List<JsonNode> mine = rows.stream().filter(r -> c.getValue().equals(r.path("series").asText())).toList();
            Map<String, Object> s = new LinkedHashMap<>();
            s.put("name", c.getKey());
            s.put("series_id", c.getValue());
            s.put("description", mine.isEmpty() ? null : mine.get(0).path("series-description").asText(null));
            s.put("units", mine.isEmpty() ? null : mine.get(0).path("units").asText(null));
            List<Map<String, Object>> vals = new ArrayList<>();
            for (int i = 0; i < mine.size() && vals.size() < n; i++) {
                JsonNode r = mine.get(i);
                Map<String, Object> v = new LinkedHashMap<>();
                String period = r.path("period").asText();
                v.put(releaseLagDays > 0 ? "week_ending" : "date", period);
                if (releaseLagDays > 0) {
                    try {
                        v.put("released_est", LocalDate.parse(period).plusDays(releaseLagDays).toString());
                    } catch (Exception ignored) {
                        // non-daily period format; no estimate
                    }
                }
                Number val = num(r.path("value").asText(null));
                v.put("value", val);
                if (releaseLagDays > 0 && i + 1 < mine.size()) {
                    Number prev = num(mine.get(i + 1).path("value").asText(null));
                    if (val != null && prev != null) {
                        v.put("change", new BigDecimal(val.toString()).subtract(new BigDecimal(prev.toString())));
                    }
                }
                vals.add(v);
            }
            s.put("values", vals);
            if (vals.isEmpty()) s.put("warning", "EIA returned no rows for this series");
            out.add(s);
        }
        return out;
    }

    // ── plumbing ─────────────────────────────────────────────────────────────

    /** GET {base}/{route}?api_key=…&q…; EIA's 400 (bad route/facet) becomes BAD_REQUEST, 403 is AUTH. */
    JsonNode get(ConnectorContext ctx, String route, List<Object> q) {
        List<Object> kv = new ArrayList<>(List.of("api_key", ctx.require("api_key")));
        kv.addAll(q);
        String path = base + (route.isEmpty() ? "/" : "/" + route + (route.endsWith("/data") ? "/" : ""));
        try {
            return ctx.getJson(URI.create(url(path, kv.toArray())));
        } catch (ConnectorException e) {
            String msg = e.getMessage() == null ? "" : e.getMessage();
            if (e.kind() == ConnectorException.Kind.AUTH || msg.contains("API_KEY")) {
                throw new ConnectorException(ConnectorException.Kind.AUTH,
                        "EIA rejected the API key. Check it in Settings → Connectors.");
            }
            if (e.kind() == ConnectorException.Kind.UPSTREAM && msg.contains("HTTP 400")) {
                java.util.regex.Matcher m = java.util.regex.Pattern.compile("\"error\"\\s*:\\s*\"([^\"]*)").matcher(msg);
                throw ConnectorException.badRequest("EIA: " + (m.find() ? m.group(1).replace("\\/", "/") : "bad request") + " (see eia_browse)");
            }
            throw e;
        }
    }

    static String route(String raw, boolean allowEmpty) {
        String r = raw == null ? "" : raw.strip().replaceAll("^/+|/+$", "").toLowerCase(Locale.ROOT);
        if (r.startsWith("v2/")) r = r.substring(3);
        if (r.endsWith("/data")) r = r.substring(0, r.length() - 5);
        if (r.isEmpty()) {
            if (allowEmpty) return r;
            throw ConnectorException.badRequest("route is required, e.g. petroleum/stoc/wstk");
        }
        if (!r.matches("^[a-z0-9-]+(/[a-z0-9-]+){0,6}$")) throw ConnectorException.badRequest("route must look like petroleum/stoc/wstk");
        return r;
    }

    private static String period(JsonNode args, String name) {
        String s = optStr(args, name);
        if (s == null) return null;
        if (!s.matches("^\\d{4}(-\\d{2}(-\\d{2}(T\\d{2})?)?|-Q[1-4])?$")) throw ConnectorException.badRequest(name + " must look like 2026, 2026-01 or 2026-01-15");
        if (s.length() == 10) date(args, name);   // validates real calendar dates
        return s;
    }

    private static Map<String, String> choose(JsonNode args, Map<String, String> all, List<String> dflt) {
        Set<String> want = strSet(args, "series", false);
        Map<String, String> out = new LinkedHashMap<>();
        for (String w : want.isEmpty() ? dflt : want) {
            String k = w.toLowerCase(Locale.ROOT).replace(' ', '_');
            if (!all.containsKey(k)) throw ConnectorException.badRequest("series must be among " + all.keySet());
            out.put(k, all.get(k));
        }
        return out;
    }

    private static Object numOrText(String s) {
        Number n = num(s);
        return n != null ? n : s;
    }

    static Number num(String s) {
        if (s == null) return null;
        String t = s.strip();
        if (t.isEmpty()) return null;
        try {
            BigDecimal d = new BigDecimal(t);
            return d.scale() <= 0 ? (Number) d.longValue() : (Number) d.doubleValue();
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
