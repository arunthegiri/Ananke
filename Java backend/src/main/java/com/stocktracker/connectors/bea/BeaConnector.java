package com.stocktracker.connectors.bea;

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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.stocktracker.connectors.ToolArgs.enumStr;
import static com.stocktracker.connectors.ToolArgs.intArg;
import static com.stocktracker.connectors.ToolArgs.integer;
import static com.stocktracker.connectors.ToolArgs.object;
import static com.stocktracker.connectors.ToolArgs.oneOf;
import static com.stocktracker.connectors.ToolArgs.optStr;
import static com.stocktracker.connectors.ToolArgs.props;
import static com.stocktracker.connectors.ToolArgs.requireStr;
import static com.stocktracker.connectors.ToolArgs.str;
import static com.stocktracker.connectors.ToolArgs.strArray;
import static com.stocktracker.connectors.ToolArgs.strSet;
import static com.stocktracker.connectors.ToolArgs.url;

/**
 * Bureau of Economic Analysis API: the NIPA tables — GDP and its components, PCE and the PCE price
 * index, personal income and saving, corporate profits.
 *
 * <p>Point-in-time: the API serves the latest vintage only. Each result carries the table's
 * "last revised" note and BEA's production timestamp; for first-print values use FRED/ALFRED.
 */
@Component
public class BeaConnector implements Connector {

    static final String ID = "bea";
    static final String BASE_URL = "https://apps.bea.gov/api/data";
    static final Pattern REVISED = Pattern.compile("(?i)(last\\s*revised|last\\s*updated)\\s*(on)?\\s*:?\\s*([A-Z][a-z]+\\.? \\d{1,2},? \\d{4})");
    static final Pattern NEXT = Pattern.compile("(?i)next\\s*release\\s*(date)?\\s*:?\\s*([A-Z][a-z]+\\.? \\d{1,2},? \\d{4})");

    private static final ConnectorSpec SPEC = new ConnectorSpec(
            ID,
            "U.S. Bureau of Economic Analysis",
            ConnectorSpec.Category.MACRO,
            ConnectorSpec.Licence.FREE_PUBLIC,
            "National accounts (NIPA) from BEA: GDP growth and its components (consumption, investment, government, "
                    + "net exports), the PCE price index the Fed targets, personal income, spending and saving, and corporate "
                    + "profits — quarterly, annual and monthly tables.",
            "Source: U.S. Bureau of Economic Analysis. This product uses the BEA Data API but is not endorsed or certified by BEA.",
            "https://apps.bea.gov/api/signup/",
            List.of(Field.secret("api_key", "BEA UserID",
                    "Free 36-character UserID: register at apps.bea.gov/API/signup and confirm the email.")),
            List.of(RateLimit.perMinute(60)));   // BEA: 100 requests, 100 MB and 30 errors a minute, then a timeout

    private final String base;
    private final Clock clock;

    public BeaConnector() {
        this(BASE_URL, Clock.systemUTC());
    }

    /** Test seam: point at a fake BEA. */
    BeaConnector(String base, Clock clock) {
        this.base = base;
        this.clock = clock;
    }

    @Override
    public ConnectorSpec spec() {
        return SPEC;
    }

    @Override
    public String test(ConnectorContext ctx) {
        JsonNode res = call(ctx, "method", "GETDATASETLIST");
        int n = res.path("Dataset").size();
        return "Connected — BEA answered (" + n + " datasets available).";
    }

    @Override
    public List<ConnectorTool> tools() {
        return List.of(
                new ConnectorTool(ID + "_nipa_tables",
                        "List BEA NIPA tables (id + title), optionally filtered by words in the title. Key tables: T10101 "
                                + "real GDP % change and contributions; T10102 contributions to GDP growth; T10105 nominal GDP "
                                + "($); T10106 real GDP (chained $); T20100 personal income and outlays; T20301/T20304 PCE and PCE "
                                + "price index; T20804 PCE price index % change (monthly); T20600 personal income monthly; "
                                + "T61600D corporate profits.",
                        object(props(
                                "query", str("Words to match in the title, e.g. \"price index\" or \"profits\""),
                                "limit", integer("Max tables (1-100, default 40)")), List.of()),
                        Duration.ofHours(24), this::tables,
                        a -> "Listing BEA NIPA tables…"),
                new ConnectorTool(ID + "_nipa_data",
                        "One BEA NIPA table: each line (e.g. GDP, PCE, Goods, Services) with its values for the most recent "
                                + "periods, units, the table's last-revised date and next release when BEA states them.",
                        object(props(
                                "table", str("NIPA table id, e.g. T10101"),
                                "frequency", enumStr("Q quarterly (default), A annual, M monthly", List.of("Q", "A", "M")),
                                "years", integer("How many recent years to request (1-10, default 3)"),
                                "periods", integer("Most recent N periods per line (1-40, default 8)"),
                                "lines", strArray("Only these line numbers, e.g. [\"1\",\"2\"]"),
                                "max_lines", integer("Max lines (1-80, default 40)")), List.of("table")),
                        Duration.ofHours(3), this::data,
                        a -> "Reading BEA table " + a.path("table").asText("") + "…"));
    }

    Object tables(ConnectorContext ctx, JsonNode args) {
        String q = optStr(args, "query");
        if (q != null && q.length() > 100) throw ConnectorException.badRequest("query is too long (max 100 characters)");
        int limit = intArg(args, "limit", 40, 1, 100);
        JsonNode res = call(ctx, "method", "GetParameterValues", "DataSetName", "NIPA", "ParameterName", "TableName");
        List<String> words = q == null ? List.of() : List.of(q.toLowerCase(Locale.ROOT).split("\\s+"));
        List<Map<String, Object>> rows = new ArrayList<>();
        int matched = 0;
        for (JsonNode p : res.path("ParamValue")) {
            String name = p.path("TableName").asText(p.path("Key").asText(""));
            String desc = p.path("Description").asText(p.path("Desc").asText(""));
            String hay = (name + " " + desc).toLowerCase(Locale.ROOT);
            if (!words.stream().allMatch(hay::contains)) continue;
            matched++;
            if (rows.size() < limit) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("table", name);
                m.put("title", desc);
                rows.add(m);
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        if (q != null) out.put("query", q);
        out.put("matched", matched);
        out.put("tables", rows);
        return out;
    }

    Object data(ConnectorContext ctx, JsonNode args) {
        String table = requireStr(args, "table", 20).toUpperCase(Locale.ROOT);
        if (!table.matches("^T[0-9A-Z]{3,12}$")) throw ConnectorException.badRequest("table must be a NIPA table id like T10101");
        String freq = oneOf(args, "frequency", "Q", List.of("Q", "A", "M"));
        int years = intArg(args, "years", 3, 1, 10);
        int periods = intArg(args, "periods", 8, 1, 40);
        int maxLines = intArg(args, "max_lines", 40, 1, 80);
        Set<String> lines = strSet(args, "lines", false);
        for (String l : lines) if (!l.matches("^\\d{1,3}$")) throw ConnectorException.badRequest("lines must be line numbers like \"1\"");
        int thisYear = LocalDate.now(clock).getYear();
        StringBuilder ys = new StringBuilder();
        for (int y = thisYear - years + 1; y <= thisYear; y++) ys.append(ys.isEmpty() ? "" : ",").append(y);

        JsonNode res = call(ctx, "method", "GetData", "DataSetName", "NIPA", "TableName", table,
                "Frequency", freq, "Year", ys.toString());

        Map<String, Map<String, Object>> byLine = new LinkedHashMap<>();
        TreeSet<String> allPeriods = new TreeSet<>(BeaConnector::comparePeriods);
        for (JsonNode d : res.path("Data")) {
            String line = d.path("LineNumber").asText();
            if (!lines.isEmpty() && !lines.contains(line)) continue;
            Map<String, Object> m = byLine.get(line);
            if (m == null) {
                if (byLine.size() >= maxLines) continue;
                m = new LinkedHashMap<>();
                m.put("line", line);
                m.put("series_code", d.path("SeriesCode").asText(null));
                m.put("description", d.path("LineDescription").asText(null));
                m.put("metric", d.path("METRIC_NAME").asText(d.path("Metric_Name").asText(null)));
                m.put("unit", d.path("CL_UNIT").asText(null));
                int mult = d.path("UNIT_MULT").asInt(0);
                if (mult != 0) m.put("unit_multiplier", "1e" + mult);
                m.put("values", new LinkedHashMap<String, Object>());
                byLine.put(line, m);
            }
            String period = d.path("TimePeriod").asText();
            allPeriods.add(period);
            @SuppressWarnings("unchecked")
            Map<String, Object> values = (Map<String, Object>) m.get("values");
            values.put(period, num(d.path("DataValue").asText(null)));
        }
        // keep the most recent N periods, newest first
        List<String> keep = new ArrayList<>(allPeriods.descendingSet());
        if (keep.size() > periods) keep = keep.subList(0, periods);
        Set<String> keepSet = new LinkedHashSet<>(keep);
        for (Map<String, Object> m : byLine.values()) {
            @SuppressWarnings("unchecked")
            Map<String, Object> values = (Map<String, Object>) m.get("values");
            Map<String, Object> ordered = new LinkedHashMap<>();
            for (String p : keepSet) if (values.containsKey(p)) ordered.put(p, values.get(p));
            m.put("values", ordered);
        }

        String title = null;
        String revised = null;
        String next = null;
        List<String> notes = new ArrayList<>();
        for (JsonNode n : res.path("Notes")) {
            String text = n.path("NoteText").asText("").replaceAll("\\s+", " ").strip();
            if (text.isEmpty()) continue;
            if (title == null && n.path("NoteRef").asText("").equalsIgnoreCase(table)) title = text.replaceAll("\\s*-?\\s*Last\\s*Revised.*$", "");
            Matcher r = REVISED.matcher(text);
            if (revised == null && r.find()) revised = r.group(3);
            Matcher x = NEXT.matcher(text);
            if (next == null && x.find()) next = x.group(2);
            if (notes.size() < 5) notes.add(text.length() > 300 ? text.substring(0, 300) + "…" : text);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("table", table);
        out.put("title", title);
        out.put("frequency", freq);
        if (revised != null) out.put("last_revised", revised);
        if (next != null) out.put("next_release", next);
        String produced = res.path("UTCProductionTime").asText(null);
        if (produced != null) out.put("produced_at_utc", produced);
        out.put("periods", keep);
        out.put("lines", new ArrayList<>(byLine.values()));
        if (!notes.isEmpty()) out.put("notes", notes);
        out.put("note", "Latest vintage only (BEA revises GDP twice after the advance estimate, then annually). "
                + "last_revised is when these numbers became public; for first-print values use FRED/ALFRED as_of.");
        return out;
    }

    /** One BEA call → the Results node. BEA answers 200 with an Error node for most failures, including a bad UserID. */
    JsonNode call(ConnectorContext ctx, Object... kv) {
        Object[] all = new Object[kv.length + 4];
        all[0] = "UserID";
        all[1] = ctx.require("api_key");
        System.arraycopy(kv, 0, all, 2, kv.length);
        all[kv.length + 2] = "ResultFormat";
        all[kv.length + 3] = "JSON";
        JsonNode r = ctx.getJson(URI.create(url(base, all))).path("BEAAPI");
        JsonNode res = r.path("Results");
        if (res.isArray()) res = res.path(0);
        JsonNode err = res.has("Error") ? res.path("Error") : r.path("Error");
        if (err.isArray()) err = err.path(0);
        if (!err.isMissingNode() && !err.isNull()) {
            String code = err.path("APIErrorCode").asText("");
            String desc = err.path("APIErrorDescription").asText("");
            String detail = err.path("ErrorDetail").path("Description").asText("");
            String msg = (desc + (detail.isEmpty() ? "" : " — " + detail)).strip();
            if (code.equals("1") || desc.toLowerCase(Locale.ROOT).contains("userid")) {
                throw new ConnectorException(ConnectorException.Kind.AUTH,
                        "BEA rejected the UserID. Check it in Settings → Connectors (and that the activation email was confirmed).");
            }
            if (code.equals("7") || desc.toLowerCase(Locale.ROOT).contains("quota") || desc.toLowerCase(Locale.ROOT).contains("exceeded")) {
                throw new ConnectorException(ConnectorException.Kind.RATE_LIMITED, "BEA rate limit reached: " + msg);
            }
            throw ConnectorException.badRequest("BEA: " + (msg.isEmpty() ? "error " + code : msg));
        }
        return res;
    }

    /** 2025Q4 < 2026Q1; 2026M9 < 2026M10 (BEA does not zero-pad months). */
    static int comparePeriods(String a, String b) {
        long x = periodKey(a), y = periodKey(b);
        return x != y ? Long.compare(x, y) : a.compareTo(b);
    }

    private static long periodKey(String p) {
        Matcher m = Pattern.compile("^(\\d{4})(?:[QM](\\d{1,2}))?$").matcher(p);
        if (!m.matches()) return Long.MIN_VALUE;
        return Long.parseLong(m.group(1)) * 100 + (m.group(2) == null ? 0 : Long.parseLong(m.group(2)));
    }

    static Number num(String s) {
        if (s == null) return null;
        String t = s.replace(",", "").strip();
        if (t.isEmpty() || t.startsWith("(") || t.equals("---")) return null;
        try {
            BigDecimal d = new BigDecimal(t);
            return d.scale() <= 0 ? (Number) d.longValue() : (Number) d.doubleValue();
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
