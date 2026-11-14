package com.stocktracker.connectors.thetadata;

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
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static com.stocktracker.connectors.ToolArgs.*;

/**
 * ThetaData (Connectors roadmap, options/market data) through the user's own <b>locally running
 * Theta Terminal</b> — this connector calls localhost (default {@code http://127.0.0.1:25503/v3}),
 * never ThetaData's servers directly. The Terminal holds the ThetaData login; Ananke stores no key.
 *
 * <p>Built against the Terminal v3 REST API (docs.thetadata.us/openapiv3.yaml): {@code format=json}
 * answers {@code {"response":[…]}}, option endpoints group rows as {@code {contract, data[]}}. The
 * Terminal's own status codes (471 permission, 472 no data, 473 bad params, 474 disconnected…) are
 * mapped here, so this connector sends its own requests (one rate-limit permit each).
 *
 * <p>Point-in-time: ThetaData builds its EOD reports at 17:15 ET; rows carry {@code created} (report
 * time) and {@code last_trade}. Open interest is OPRA's ~06:30 ET report for the prior session.
 */
@Component
public class ThetaDataConnector implements Connector {

    static final String ID = "thetadata";
    static final String DEFAULT_URL = "http://127.0.0.1:25503";
    static final ZoneId EASTERN = ZoneId.of("America/New_York");
    static final DateTimeFormatter YMD = DateTimeFormatter.BASIC_ISO_DATE;

    private static final ConnectorSpec SPEC = new ConnectorSpec(
            ID,
            "ThetaData (local Theta Terminal)",
            ConnectorSpec.Category.OPTIONS_FLOW,
            ConnectorSpec.Licence.BYO_PERSONAL,
            "Historical US options and equities from ThetaData via your own Theta Terminal running on this "
                    + "machine (calls localhost, default port 25503): stock end-of-day bars, option expirations, "
                    + "option end-of-day prices, end-of-day greeks/IV (Standard plan) and open interest (Value plan).",
            "Data: ThetaData (thetadata.net) via Theta Terminal",
            "https://docs.thetadata.us/",
            List.of(new Field("base_url", "Theta Terminal URL",
                    "Where your Theta Terminal v3 listens. Leave blank for the default local port. The Terminal must be "
                            + "running and logged in to your ThetaData account; Ananke stores no ThetaData credentials.",
                    false, true, DEFAULT_URL)),
            // Concurrency, not rate, is ThetaData's limit (1-8 by tier, extra requests queue). Stay gentle.
            List.of(RateLimit.perSecond(4)));

    private final Clock clock;
    private final String defaultBase;

    public ThetaDataConnector() {
        this(Clock.systemUTC(), DEFAULT_URL);
    }

    /** Test seam: a fake Terminal and a fixed clock. */
    ThetaDataConnector(Clock clock, String defaultBase) {
        this.clock = clock;
        this.defaultBase = defaultBase;
    }

    @Override
    public ConnectorSpec spec() {
        return SPEC;
    }

    @Override
    public String test(ConnectorContext ctx) {
        List<JsonNode> rows = rows(call(ctx, "/v3/calendar/today"));
        if (rows.isEmpty()) return "Connected — Theta Terminal answered.";
        JsonNode r = rows.get(0);
        String type = r.path("type").asText("?");
        return "Connected — Theta Terminal answered (market today: " + type
                + (r.hasNonNull("open") ? " " + r.path("open").asText() + "–" + r.path("close").asText() + " ET" : "") + ").";
    }

    @Override
    public List<ConnectorTool> tools() {
        return List.of(
                new ConnectorTool(ID + "_stock_eod",
                        "Daily end-of-day bars for a US stock or ETF from ThetaData's national EOD report: open, high, low, "
                                + "close, volume, trade count, closing bid/ask, report time. Oldest first.",
                        object(props(
                                "symbol", str("Ticker symbol"),
                                "from", str("Start date YYYY-MM-DD (default: 30 days before 'to')"),
                                "to", str("End date YYYY-MM-DD (default: today ET)"),
                                "limit", integer("Max bars, most recent kept (1-260, default 60)")), List.of("symbol")),
                        Duration.ofHours(1), this::stockEod,
                        a -> "Reading ThetaData EOD bars for " + a.path("symbol").asText("") + "…"),
                new ConnectorTool(ID + "_option_expirations",
                        "Listed option expiration dates for an underlying, nearest first (upcoming only unless include_past).",
                        object(props(
                                "symbol", str("Underlying symbol"),
                                "include_past", bool("Also return expired dates (most recent first)"),
                                "limit", integer("Max dates (1-200, default 30)")), List.of("symbol")),
                        Duration.ofHours(6), this::expirations,
                        a -> "Listing option expirations for " + a.path("symbol").asText("") + "…"),
                new ConnectorTool(ID + "_option_eod",
                        "End-of-day option prices for one expiration of an underlying: per contract (strike, call/put) and "
                                + "day — open, high, low, close, volume, closing bid/ask. Defaults to the 10 strikes "
                                + "either side of the spot price.",
                        optionSchema(), Duration.ofHours(1), (c, a) -> optionHistory(c, a, "/v3/option/history/eod", false),
                        a -> "Reading ThetaData option EOD for " + a.path("symbol").asText("") + "…"),
                new ConnectorTool(ID + "_option_greeks_eod",
                        "End-of-day option greeks for one expiration: implied vol, delta, gamma, theta, vega, rho, close, "
                                + "bid/ask, volume and the underlying price, per contract and day. Needs ThetaData Standard.",
                        optionSchema(), Duration.ofHours(1), (c, a) -> optionHistory(c, a, "/v3/option/history/greeks/eod", true),
                        a -> "Reading ThetaData option greeks for " + a.path("symbol").asText("") + "…"),
                new ConnectorTool(ID + "_option_open_interest",
                        "Open interest per contract for one expiration on a date (OPRA's ~06:30 ET report, which reflects "
                                + "the previous session). Needs ThetaData Value or higher.",
                        object(props(
                                "symbol", str("Underlying symbol"),
                                "expiration", str("Expiration date YYYY-MM-DD"),
                                "date", str("Report date YYYY-MM-DD (default: today ET)"),
                                "right", enumStr("call, put or both (default)", List.of("call", "put", "both")),
                                "strike_range", integer("Strikes either side of spot (1-50, default 10)"),
                                "limit", integer("Max rows (1-300, default 100)")), List.of("symbol", "expiration")),
                        Duration.ofHours(6), this::openInterest,
                        a -> "Reading open interest for " + a.path("symbol").asText("") + "…"));
    }

    static Map<String, Object> optionSchema() {
        return object(props(
                "symbol", str("Underlying symbol"),
                "expiration", str("Expiration date YYYY-MM-DD"),
                "strike", number("Only this strike in dollars, e.g. 170"),
                "right", enumStr("call, put or both (default)", List.of("call", "put", "both")),
                "from", str("Start date YYYY-MM-DD (default: 'to')"),
                "to", str("End date YYYY-MM-DD (default: today ET)"),
                "strike_range", integer("Strikes either side of spot when no strike is given (1-50, default 10)"),
                "limit", integer("Max rows (1-300, default 100)")), List.of("symbol", "expiration"));
    }

    // ── tools ────────────────────────────────────────────────────────────────

    Object stockEod(ConnectorContext ctx, JsonNode args) {
        String symbol = ticker(args, "symbol");
        LocalDate[] range = range(args, 30, 366);
        int limit = intArg(args, "limit", 60, 1, 260);
        List<Map<String, Object>> bars = new ArrayList<>();
        for (JsonNode r : rows(call(ctx, "/v3/stock/history/eod", "symbol", symbol,
                "start_date", range[0].format(YMD), "end_date", range[1].format(YMD), "format", "json"))) {
            for (JsonNode d : data(r)) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("date", day(d.path("created").asText(null)));
                m.put("open", num(d, "open"));
                m.put("high", num(d, "high"));
                m.put("low", num(d, "low"));
                m.put("close", num(d, "close"));
                m.put("volume", num(d, "volume"));
                m.put("trades", num(d, "count"));
                m.put("bid", num(d, "bid"));
                m.put("ask", num(d, "ask"));
                m.put("report_time_et", text(d, "created"));
                m.put("last_trade_et", text(d, "last_trade"));
                bars.add(m);
            }
        }
        bars.sort(Comparator.comparing(m -> String.valueOf(m.get("report_time_et"))));
        List<Map<String, Object>> tail = bars.subList(Math.max(0, bars.size() - limit), bars.size());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("symbol", symbol);
        out.put("from", range[0].toString());
        out.put("to", range[1].toString());
        out.put("count", tail.size());
        out.put("bars", tail);
        out.put("note", "Times are US Eastern as the Terminal reports them; the EOD report is built ~17:15 ET.");
        return out;
    }

    Object expirations(ConnectorContext ctx, JsonNode args) {
        String symbol = ticker(args, "symbol");
        boolean past = args.path("include_past").asBoolean(false);
        int limit = intArg(args, "limit", 30, 1, 200);
        LocalDate today = LocalDate.now(clock.withZone(EASTERN));
        List<LocalDate> upcoming = new ArrayList<>();
        List<LocalDate> expired = new ArrayList<>();
        for (JsonNode r : rows(call(ctx, "/v3/option/list/expirations", "symbol", symbol, "format", "json"))) {
            LocalDate d = parseDay(r.path("expiration").asText(null));
            if (d == null) continue;
            (d.isBefore(today) ? expired : upcoming).add(d);
        }
        upcoming.sort(Comparator.naturalOrder());
        expired.sort(Comparator.reverseOrder());
        List<String> out = new ArrayList<>();
        for (LocalDate d : upcoming) if (out.size() < limit) out.add(d.toString());
        if (past) for (LocalDate d : expired) if (out.size() < limit) out.add(d.toString());
        Map<String, Object> res = new LinkedHashMap<>();
        res.put("symbol", symbol);
        res.put("as_of", today.toString());
        res.put("upcoming_total", upcoming.size());
        res.put("expirations", out);
        return res;
    }

    Object optionHistory(ConnectorContext ctx, JsonNode args, String path, boolean greeks) {
        String symbol = ticker(args, "symbol");
        LocalDate exp = date(args, "expiration");
        if (exp == null) throw ConnectorException.badRequest("expiration is required (YYYY-MM-DD)");
        LocalDate[] range = range(args, 0, 31);
        String right = oneOf(args, "right", "both", List.of("call", "put", "both"));
        Double strike = args.hasNonNull("strike") ? args.path("strike").asDouble() : null;
        if (strike != null && (strike <= 0 || strike > 1_000_000)) throw ConnectorException.badRequest("strike must be a positive dollar amount");
        int strikeRange = intArg(args, "strike_range", 10, 1, 50);
        int limit = intArg(args, "limit", 100, 1, 300);
        JsonNode body = call(ctx, path, "symbol", symbol, "expiration", exp.format(YMD),
                "strike", strike == null ? null : String.format(Locale.ROOT, "%.3f", strike),
                "right", right,
                "strike_range", strike == null ? String.valueOf(strikeRange) : null,
                "start_date", range[0].format(YMD), "end_date", range[1].format(YMD), "format", "json");
        List<Map<String, Object>> rows = new ArrayList<>();
        int total = 0;
        for (JsonNode group : rows(body)) {
            JsonNode c = group.path("contract").isObject() ? group.path("contract") : group;
            for (JsonNode d : data(group)) {
                total++;
                if (rows.size() >= limit) continue;
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("strike", num(c, "strike"));
                m.put("right", text(c, "right") == null ? null : text(c, "right").toLowerCase(Locale.ROOT));
                m.put("expiration", text(c, "expiration"));
                String ts = text(d, greeks ? "timestamp" : "created");
                m.put("date", day(ts != null ? ts : text(d, "created")));
                m.put("close", num(d, "close"));
                m.put("bid", num(d, "bid"));
                m.put("ask", num(d, "ask"));
                m.put("volume", num(d, "volume"));
                if (greeks) {
                    m.put("implied_vol", num(d, "implied_vol"));
                    m.put("delta", num(d, "delta"));
                    m.put("gamma", num(d, "gamma"));
                    m.put("theta", num(d, "theta"));
                    m.put("vega", num(d, "vega"));
                    m.put("rho", num(d, "rho"));
                    m.put("iv_error", num(d, "iv_error"));
                    m.put("underlying_price", num(d, "underlying_price"));
                } else {
                    m.put("open", num(d, "open"));
                    m.put("high", num(d, "high"));
                    m.put("low", num(d, "low"));
                    m.put("trades", num(d, "count"));
                }
                rows.add(m);
            }
        }
        rows.sort(Comparator.comparing((Map<String, Object> m) -> String.valueOf(m.get("date")))
                .thenComparing(m -> String.valueOf(m.get("right")))
                .thenComparing(m -> m.get("strike") == null ? 0.0 : (Double) m.get("strike")));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("symbol", symbol);
        out.put("expiration", exp.toString());
        out.put("from", range[0].toString());
        out.put("to", range[1].toString());
        out.put("total_rows", total);
        out.put("count", rows.size());
        out.put("rows", rows);
        if (total > rows.size()) out.put("truncated", "Narrow with strike, right or a smaller strike_range/date range to see the rest.");
        return out;
    }

    Object openInterest(ConnectorContext ctx, JsonNode args) {
        String symbol = ticker(args, "symbol");
        LocalDate exp = date(args, "expiration");
        if (exp == null) throw ConnectorException.badRequest("expiration is required (YYYY-MM-DD)");
        LocalDate day = date(args, "date");
        if (day == null) day = LocalDate.now(clock.withZone(EASTERN));
        String right = oneOf(args, "right", "both", List.of("call", "put", "both"));
        int strikeRange = intArg(args, "strike_range", 10, 1, 50);
        int limit = intArg(args, "limit", 100, 1, 300);
        JsonNode body = call(ctx, "/v3/option/history/open_interest", "symbol", symbol, "expiration", exp.format(YMD),
                "right", right, "strike_range", String.valueOf(strikeRange), "date", day.format(YMD), "format", "json");
        List<Map<String, Object>> rows = new ArrayList<>();
        for (JsonNode group : rows(body)) {
            JsonNode c = group.path("contract").isObject() ? group.path("contract") : group;
            for (JsonNode d : data(group)) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("strike", num(c, "strike"));
                m.put("right", text(c, "right") == null ? null : text(c, "right").toLowerCase(Locale.ROOT));
                m.put("open_interest", num(d, "open_interest"));
                m.put("reported_at_et", text(d, "timestamp"));
                rows.add(m);
            }
        }
        rows.sort(Comparator.comparing((Map<String, Object> m) -> String.valueOf(m.get("right")))
                .thenComparing(m -> m.get("strike") == null ? 0.0 : (Double) m.get("strike")));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("symbol", symbol);
        out.put("expiration", exp.toString());
        out.put("date", day.toString());
        out.put("count", Math.min(limit, rows.size()));
        out.put("contracts", rows.subList(0, Math.min(limit, rows.size())));
        out.put("note", "Open interest reported that morning reflects the end of the previous trading day.");
        return out;
    }

    // ── transport ────────────────────────────────────────────────────────────

    JsonNode call(ConnectorContext ctx, String path, String... query) {
        String base = ctx.field("base_url").orElse(defaultBase).replaceAll("/+$", "").replaceFirst("/v3$", "");
        if (!base.matches("^https?://[^\\s/?#]+(:\\d+)?$")) {
            throw ConnectorException.badRequest("Theta Terminal URL must look like http://127.0.0.1:25503");
        }
        Object[] kv = new Object[query.length];
        System.arraycopy(query, 0, kv, 0, query.length);
        HttpRequest req = HttpRequest.newBuilder(URI.create(url(base + path, kv)))
                .timeout(Duration.ofSeconds(60)).GET().header("Accept", "application/json").header("User-Agent", "Ananke/1").build();
        ctx.acquirePermit();
        HttpResponse<String> res;
        try {
            res = ctx.http().send(req, HttpResponse.BodyHandlers.ofString());
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            throw ConnectorException.upstream("Theta Terminal is not reachable at " + base
                    + " — start the Terminal (and log in), or fix the URL in Settings → Connectors.");
        }
        int s = res.statusCode();
        String body = res.body() == null ? "" : res.body();
        String detail = body.replaceAll("<[^>]*>", " ").replaceAll("\\s+", " ").strip();
        if (detail.length() > 200) detail = detail.substring(0, 200) + "…";
        switch (s) {
            case 200 -> {
                if (body.length() > 20_000_000) throw ConnectorException.upstream("Theta Terminal answered with too much data; narrow the request.");
                try {
                    return ctx.mapper().readTree(body);
                } catch (Exception e) {
                    throw ConnectorException.upstream("Theta Terminal answered something that is not JSON.");
                }
            }
            case 472 -> {
                return ctx.mapper().createObjectNode().putArray("response");   // NO_DATA: an empty result, not an error
            }
            case 471 -> throw new ConnectorException(ConnectorException.Kind.AUTH,
                    "ThetaData: your subscription does not include this data (471 PERMISSION). " + detail);
            case 473, 475 -> throw ConnectorException.badRequest("ThetaData: invalid request parameters (" + s + "). " + detail);
            case 404 -> throw ConnectorException.badRequest("Theta Terminal has no such endpoint (404) — is it Terminal v3 and up to date?");
            case 429 -> throw new ConnectorException(ConnectorException.Kind.RATE_LIMITED, "Theta Terminal is throttling requests (429). Try again shortly.");
            case 474 -> throw ConnectorException.upstream("Theta Terminal has lost its connection to ThetaData (474). " + detail);
            case 476 -> throw ConnectorException.upstream("Theta Terminal: requests must come from one IP (476) — don't mix 127.0.0.1 and localhost.");
            case 478 -> throw ConnectorException.upstream("Theta Terminal: invalid session (478) — is more than one Terminal running?");
            default -> throw ConnectorException.upstream("Theta Terminal failed: HTTP " + s + (detail.isEmpty() ? "" : " " + detail));
        }
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    /** {@code {"response":[…]}} or a bare array → rows. */
    static List<JsonNode> rows(JsonNode n) {
        List<JsonNode> out = new ArrayList<>();
        JsonNode arr = n == null ? null : n.isArray() ? n : n.path("response");
        if (arr != null && arr.isArray()) arr.forEach(out::add);
        return out;
    }

    /** Option rows are {@code {contract, data[]}}; flat rows (stocks, ndjson-style) are their own data. */
    static List<JsonNode> data(JsonNode group) {
        List<JsonNode> out = new ArrayList<>();
        if (group.path("data").isArray()) group.path("data").forEach(out::add);
        else out.add(group);
        return out;
    }

    LocalDate[] range(JsonNode args, int defaultDays, int maxDays) {
        LocalDate to = date(args, "to");
        LocalDate from = date(args, "from");
        LocalDate today = LocalDate.now(clock.withZone(EASTERN));
        if (to == null) to = today;
        if (from == null) from = to.minusDays(defaultDays);
        if (from.isAfter(to)) throw ConnectorException.badRequest("from must be on or before to");
        if (ChronoUnit.DAYS.between(from, to) > maxDays) throw ConnectorException.badRequest("date range is at most " + maxDays + " days");
        return new LocalDate[]{from, to};
    }

    static String text(JsonNode r, String f) {
        JsonNode n = r.path(f);
        if (n.isMissingNode() || n.isNull()) return null;
        String s = n.asText("").strip();
        return s.isEmpty() ? null : s;
    }

    static Double num(JsonNode r, String f) {
        JsonNode n = r.path(f);
        if (n.isNumber()) return n.asDouble();
        if (n.isTextual()) {
            try {
                return Double.parseDouble(n.asText().strip());
            } catch (Exception e) {
                return null;
            }
        }
        return null;
    }

    static LocalDate parseDay(String s) {
        if (s == null || s.length() < 8) return null;
        try {
            return s.contains("-") ? LocalDate.parse(s.substring(0, 10)) : LocalDate.parse(s.substring(0, 8), YMD);
        } catch (Exception e) {
            return null;
        }
    }

    static String day(String s) {
        LocalDate d = parseDay(s);
        return d == null ? null : d.toString();
    }
}
