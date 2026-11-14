package com.stocktracker.connectors.massive;

import com.fasterxml.jackson.databind.JsonNode;
import com.stocktracker.connectors.Connector;
import com.stocktracker.connectors.ConnectorContext;
import com.stocktracker.connectors.ConnectorException;
import com.stocktracker.connectors.ConnectorSpec;
import com.stocktracker.connectors.ConnectorSpec.Field;
import com.stocktracker.connectors.ConnectorSpec.RateLimit;
import com.stocktracker.connectors.ConnectorTool;
import com.stocktracker.connectors.ToolArgs;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.stocktracker.connectors.ToolArgs.bool;
import static com.stocktracker.connectors.ToolArgs.enumStr;
import static com.stocktracker.connectors.ToolArgs.integer;
import static com.stocktracker.connectors.ToolArgs.object;
import static com.stocktracker.connectors.ToolArgs.props;
import static com.stocktracker.connectors.ToolArgs.str;

/**
 * Massive (formerly Polygon.io) REST (Connectors roadmap §1): stock aggregates, ticker news with
 * per-ticker sentiment, ticker details and the single-ticker snapshot. Base
 * {@code https://api.massive.com} (api.polygon.io still answers the same API); the key goes in
 * {@code Authorization: Bearer}, never the URL.
 *
 * <p>The limit is the free Stocks Basic plan's 5 calls/minute; paid plans are unlimited, but the
 * spec's limits are static (see the framework request in the connectors report). The snapshot
 * is not included in Stocks Basic — Massive answers 403 for it, surfaced as an AUTH error with
 * Massive's own message.
 */
@Component
public class MassiveConnector implements Connector {

    static final String ID = "massive";
    static final String BASE_URL = "https://api.massive.com";
    static final List<String> TIMESPANS = List.of("minute", "hour", "day", "week", "month", "quarter", "year");

    private static final ConnectorSpec SPEC = new ConnectorSpec(
            ID,
            "Massive",
            ConnectorSpec.Category.MARKET_DATA,
            ConnectorSpec.Licence.BYO_PERSONAL,
            "US stock OHLCV aggregates at any bar size, ticker news with per-ticker sentiment and reasoning, ticker "
                    + "details (identifiers, market cap, shares, SIC, list date, point-in-time by date) and the latest snapshot.",
            "Source: Massive (formerly Polygon.io)",
            "https://massive.com/docs/rest/stocks/overview",
            List.of(Field.secret("api_key", "API key",
                    "From massive.com/dashboard/keys. Individual plans are for individual use; the free Stocks Basic plan "
                            + "allows 5 calls a minute and end-of-day data.")),
            List.of(RateLimit.perMinute(5)));

    private final String base;

    public MassiveConnector() {
        this(BASE_URL);
    }

    /** Test seam: point at a fake API. */
    MassiveConnector(String base) {
        this.base = base;
    }

    @Override
    public ConnectorSpec spec() {
        return SPEC;
    }

    @Override
    public String test(ConnectorContext ctx) {
        JsonNode r = get(ctx, "/v3/reference/tickers/AAPL").path("results");
        return "Connected — Massive answered (AAPL = " + r.path("name").asText("?") + ", " + r.path("primary_exchange").asText("?") + ").";
    }

    @Override
    public List<ConnectorTool> tools() {
        Map<String, Object> ticker = str("Ticker, case-sensitive as listed, e.g. AAPL or BRK.B");
        return List.of(
                new ConnectorTool(ID + "_aggregates",
                        "OHLCV bars (open, high, low, close, volume, VWAP, trades) for a stock over a date range at any bar "
                                + "size, oldest first; returns the newest `bars` bars. Times are bar-start UTC.",
                        object(props(
                                "ticker", ticker,
                                "multiplier", integer("Bar size multiplier, e.g. 5 with timespan minute (default 1)"),
                                "timespan", enumStr("Bar unit (default day)", TIMESPANS),
                                "from", str("Start date YYYY-MM-DD (inclusive)"),
                                "to", str("End date YYYY-MM-DD (inclusive)"),
                                "adjusted", bool("Split-adjusted (default true)"),
                                "bars", integer("Newest bars to return (1-500, default 120)")), List.of("ticker", "from", "to")),
                        Duration.ofMinutes(15), this::aggregates, a -> "Reading " + a.path("ticker").asText("") + " bars…"),
                new ConnectorTool(ID + "_news",
                        "Ticker news, newest first: title, publisher, publication time (UTC), link, keywords and per-ticker "
                                + "sentiment with reasoning.",
                        object(props(
                                "ticker", ticker,
                                "published_since", str("Only articles published on/after YYYY-MM-DD"),
                                "limit", integer("Articles (1-50, default 10)")), List.of()),
                        Duration.ofMinutes(15), this::news, a -> "Reading Massive news…"),
                new ConnectorTool(ID + "_ticker_details",
                        "Ticker details: name, type, primary exchange, CIK and FIGIs, market cap, shares outstanding, SIC, "
                                + "list date, employees, description. Pass date for the details as they were on that day.",
                        object(props("ticker", ticker, "date", str("As-of date YYYY-MM-DD (default latest)")), List.of("ticker")),
                        Duration.ofHours(24), this::details, a -> "Reading " + a.path("ticker").asText("") + " details…"),
                new ConnectorTool(ID + "_snapshot",
                        "Latest snapshot for a stock: today's and previous day's bars, last minute bar, change and change %, "
                                + "last trade/quote when the plan includes them. Needs a paid Stocks plan (not Basic).",
                        object(props("ticker", ticker), List.of("ticker")),
                        Duration.ofMinutes(1), this::snapshot, a -> "Reading the " + a.path("ticker").asText("") + " snapshot…"));
    }

    // ── tools ────────────────────────────────────────────────────────────────

    Object aggregates(ConnectorContext ctx, JsonNode args) {
        String t = ticker(args);
        int mult = ToolArgs.intArg(args, "multiplier", 1, 1, 1000);
        String span = ToolArgs.oneOf(args, "timespan", "day", TIMESPANS);
        LocalDate from = ToolArgs.date(args, "from");
        LocalDate to = ToolArgs.date(args, "to");
        if (from == null || to == null) throw ConnectorException.badRequest("from and to are required (YYYY-MM-DD)");
        if (to.isBefore(from)) throw ConnectorException.badRequest("to must not be before from");
        boolean adjusted = !args.has("adjusted") || args.path("adjusted").asBoolean(true);
        int bars = ToolArgs.intArg(args, "bars", 120, 1, 500);
        JsonNode r = get(ctx, ToolArgs.url("/v2/aggs/ticker/" + ToolArgs.enc(t) + "/range/" + mult + "/" + span + "/" + from + "/" + to,
                "adjusted", adjusted, "sort", "asc"));
        List<JsonNode> all = new ArrayList<>();
        r.path("results").forEach(all::add);
        List<Map<String, Object>> out = new ArrayList<>();
        for (JsonNode b : all.subList(Math.max(0, all.size() - bars), all.size())) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("t", b.has("t") ? Instant.ofEpochMilli(b.path("t").asLong()).toString() : null);
            m.put("o", num(b.get("o")));
            m.put("h", num(b.get("h")));
            m.put("l", num(b.get("l")));
            m.put("c", num(b.get("c")));
            m.put("v", num(b.get("v")));
            m.put("vw", num(b.get("vw")));
            m.put("n", b.has("n") ? b.path("n").asLong() : null);
            out.add(m);
        }
        Map<String, Object> res = new LinkedHashMap<>();
        res.put("ticker", r.path("ticker").asText(t));
        res.put("adjusted", r.path("adjusted").asBoolean(adjusted));
        res.put("bar", mult + " " + span);
        res.put("bars_total", all.size());
        res.put("partial", r.hasNonNull("next_url"));
        res.put("bars", out);
        return res;
    }

    Object news(ConnectorContext ctx, JsonNode args) {
        String t = ToolArgs.optStr(args, "ticker") == null ? null : ticker(args);
        LocalDate since = ToolArgs.date(args, "published_since");
        int limit = ToolArgs.intArg(args, "limit", 10, 1, 50);
        JsonNode r = get(ctx, ToolArgs.url("/v2/reference/news", "ticker", t,
                "published_utc.gte", since == null ? null : since.toString(), "order", "desc", "sort", "published_utc", "limit", limit));
        List<Map<String, Object>> out = new ArrayList<>();
        for (JsonNode a : r.path("results")) {
            if (out.size() >= limit) break;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("published_utc", a.path("published_utc").asText(null));
            m.put("title", a.path("title").asText(null));
            m.put("publisher", a.path("publisher").path("name").asText(null));
            m.put("author", a.path("author").asText(null));
            m.put("url", a.path("article_url").asText(null));
            m.put("description", trim(a.path("description").asText(null), 400));
            m.put("tickers", list(a.path("tickers")));
            m.put("keywords", list(a.path("keywords")));
            List<Map<String, Object>> ins = new ArrayList<>();
            for (JsonNode i : a.path("insights")) {
                Map<String, Object> x = new LinkedHashMap<>();
                x.put("ticker", i.path("ticker").asText(null));
                x.put("sentiment", i.path("sentiment").asText(null));
                x.put("reasoning", trim(i.path("sentiment_reasoning").asText(null), 300));
                ins.add(x);
            }
            m.put("insights", ins);
            out.add(m);
        }
        return Map.of("count", out.size(), "articles", out);
    }

    Object details(ConnectorContext ctx, JsonNode args) {
        String t = ticker(args);
        LocalDate date = ToolArgs.date(args, "date");
        JsonNode r = get(ctx, ToolArgs.url("/v3/reference/tickers/" + ToolArgs.enc(t), "date", date == null ? null : date.toString()))
                .path("results");
        Map<String, Object> out = new LinkedHashMap<>();
        for (String k : List.of("ticker", "name", "type", "market", "locale", "active", "primary_exchange", "currency_name", "cik",
                "composite_figi", "share_class_figi", "market_cap", "share_class_shares_outstanding", "weighted_shares_outstanding",
                "sic_code", "sic_description", "list_date", "delisted_utc", "total_employees", "homepage_url", "round_lot")) {
            JsonNode v = r.get(k);
            out.put(k, v == null || v.isNull() ? null : v.isNumber() ? (Object) v.numberValue() : v.isBoolean() ? v.asBoolean() : v.asText());
        }
        out.put("city", r.path("address").path("city").asText(null));
        out.put("state", r.path("address").path("state").asText(null));
        out.put("description", trim(r.path("description").asText(null), 600));
        out.put("as_of", date == null ? "latest" : date.toString());
        return out;
    }

    Object snapshot(ConnectorContext ctx, JsonNode args) {
        String t = ticker(args);
        JsonNode s = get(ctx, "/v2/snapshot/locale/us/markets/stocks/tickers/" + ToolArgs.enc(t)).path("ticker");
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ticker", s.path("ticker").asText(t));
        out.put("updated", s.has("updated") ? nanos(s.path("updated").asLong()) : null);
        out.put("change", num(s.get("todaysChange")));
        out.put("change_pct", num(s.get("todaysChangePerc")));
        out.put("day", bar(s.path("day")));
        out.put("prev_day", bar(s.path("prevDay")));
        out.put("minute", bar(s.path("min")));
        if (s.has("lastTrade")) {
            Map<String, Object> tr = new LinkedHashMap<>();
            tr.put("price", num(s.path("lastTrade").get("p")));
            tr.put("size", s.path("lastTrade").path("s").asLong());
            tr.put("time", nanos(s.path("lastTrade").path("t").asLong()));
            out.put("last_trade", tr);
        }
        if (s.has("lastQuote")) {
            Map<String, Object> q = new LinkedHashMap<>();
            q.put("bid", num(s.path("lastQuote").get("p")));
            q.put("ask", num(s.path("lastQuote").get("P")));
            q.put("time", nanos(s.path("lastQuote").path("t").asLong()));
            out.put("last_quote", q);
        }
        return out;
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    JsonNode get(ConnectorContext ctx, String pathAndQuery) {
        return ctx.getJson(URI.create(base + pathAndQuery), Map.of("Authorization", "Bearer " + ctx.require("api_key")));
    }

    static String ticker(JsonNode args) {
        String t = ToolArgs.ticker(args, "ticker");
        if (t.contains("/")) throw ConnectorException.badRequest("ticker must look like AAPL or BRK.B");
        return t;
    }

    static Map<String, Object> bar(JsonNode b) {
        if (b == null || b.isMissingNode() || b.isEmpty()) return null;
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("o", num(b.get("o")));
        m.put("h", num(b.get("h")));
        m.put("l", num(b.get("l")));
        m.put("c", num(b.get("c")));
        m.put("v", num(b.get("v")));
        m.put("vw", num(b.get("vw")));
        if (b.has("t")) m.put("t", Instant.ofEpochMilli(b.path("t").asLong()).toString());
        return m;
    }

    /** Massive's snapshot/trade times are Unix nanoseconds. */
    static String nanos(long ns) {
        if (ns <= 0) return null;
        return Instant.ofEpochSecond(ns / 1_000_000_000L, ns % 1_000_000_000L).toString();
    }

    static Double num(JsonNode n) {
        return n == null || n.isNull() || !n.isNumber() ? null : n.asDouble();
    }

    static List<String> list(JsonNode a) {
        List<String> out = new ArrayList<>();
        a.forEach(x -> out.add(x.asText()));
        return out;
    }

    static String trim(String s, int max) {
        if (s == null) return null;
        return s.length() > max ? s.substring(0, max) + "…" : s;
    }
}
