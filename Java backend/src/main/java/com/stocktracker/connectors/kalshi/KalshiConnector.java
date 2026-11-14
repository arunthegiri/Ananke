package com.stocktracker.connectors.kalshi;

import com.fasterxml.jackson.databind.JsonNode;
import com.stocktracker.connectors.Connector;
import com.stocktracker.connectors.ConnectorContext;
import com.stocktracker.connectors.ConnectorException;
import com.stocktracker.connectors.ConnectorSpec;
import com.stocktracker.connectors.ConnectorSpec.RateLimit;
import com.stocktracker.connectors.ConnectorTool;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static com.stocktracker.connectors.ToolArgs.enumStr;
import static com.stocktracker.connectors.ToolArgs.intArg;
import static com.stocktracker.connectors.ToolArgs.integer;
import static com.stocktracker.connectors.ToolArgs.object;
import static com.stocktracker.connectors.ToolArgs.oneOf;
import static com.stocktracker.connectors.ToolArgs.props;
import static com.stocktracker.connectors.ToolArgs.requireStr;
import static com.stocktracker.connectors.ToolArgs.str;
import static com.stocktracker.connectors.ToolArgs.url;

/**
 * Kalshi (CFTC-regulated event contracts): public market data — series, events with their
 * markets' current prices, and price history. No key is needed to read market data.
 *
 * <p>Kalshi has no keyword search in its API, so {@code kalshi_search} matches against the
 * series list of a category (held in memory for a few hours; the Politics list alone is ~3 MB).
 *
 * <p>Point-in-time: every market carries Kalshi's {@code updated_time}, every series its
 * {@code last_updated_ts}, every candle its period end.
 */
@Component
public class KalshiConnector implements Connector {

    static final String ID = "kalshi";
    static final String BASE_URL = "https://api.elections.kalshi.com/trade-api/v2";
    static final Duration SERIES_TTL = Duration.ofHours(6);

    /** Kalshi's series categories (exact, case-sensitive in the API). */
    static final List<String> CATEGORIES = List.of("Economics", "Financials", "Companies", "Politics", "Elections",
            "Crypto", "Commodities", "World", "Health", "Science and Technology", "Climate and Weather",
            "Transportation", "Social", "Entertainment", "Sports", "Mentions");
    /** Searched when no category is given: the ones that move markets. */
    static final List<String> DEFAULT_CATEGORIES = List.of("Economics", "Financials", "Companies", "Politics");

    private static final ConnectorSpec SPEC = new ConnectorSpec(
            ID,
            "Kalshi",
            ConnectorSpec.Category.PREDICTION_MARKETS,
            // No key; Kalshi's terms grant market data for the user's own (non-commercial) use.
            ConnectorSpec.Licence.PUBLIC_PERSONAL,
            "Kalshi prediction-market odds on macro and political catalysts — Fed decisions, CPI, payrolls, GDP, "
                    + "recessions, elections, company events. Crowd-implied probabilities, volume and price history "
                    + "to compare against what rates, index and sector pricing already assume.",
            "Market data: Kalshi (kalshi.com)",
            "https://docs.kalshi.com/welcome",
            List.of(),
            // Basic tier: 200 read tokens/s ≈ 20 requests/s; stay far below.
            List.of(RateLimit.perSecond(5)));

    private final Clock clock;
    private final String base;
    private final Map<String, CachedSeries> series = new ConcurrentHashMap<>();

    public KalshiConnector() {
        this(BASE_URL, Clock.systemUTC());
    }

    /** Test seam: point at a fake Kalshi. */
    KalshiConnector(String base, Clock clock) {
        this.base = base;
        this.clock = clock;
    }

    record Series(String ticker, String title, String category, List<String> tags, String frequency,
                  double volume, String updated, String haystack) { }

    private record CachedSeries(List<Series> rows, Instant at) { }

    @Override
    public ConnectorSpec spec() {
        return SPEC;
    }

    @Override
    public String test(ConnectorContext ctx) {
        JsonNode s = ctx.getJson(URI.create(base + "/series/KXFED"));
        return "Connected — Kalshi answered (series KXFED: " + s.path("series").path("title").asText("?") + ").";
    }

    @Override
    public List<ConnectorTool> tools() {
        return List.of(
                new ConnectorTool(ID + "_search",
                        "Find Kalshi series (recurring questions such as \"Fed rate decision\", \"CPI\", \"recession\") by keywords. "
                                + "Returns series tickers ranked by traded volume; pass one to kalshi_events for the live markets.",
                        object(props(
                                "query", str("Keywords, e.g. \"fed rate\", \"cpi\", \"recession\", \"tesla\""),
                                "category", enumStr("Kalshi category to search. Omit to search Economics, Financials, Companies and Politics.", CATEGORIES),
                                "limit", integer("Max series (1-25, default 10)")), List.of("query")),
                        Duration.ofMinutes(30), this::search,
                        a -> "Searching Kalshi for " + a.path("query").asText("") + "…"),
                new ConnectorTool(ID + "_events",
                        "Events of one Kalshi series (e.g. each FOMC meeting of KXFED) with their markets: yes bid/ask, "
                                + "last price (= implied probability in dollars per $1 contract), volume, open interest, close time.",
                        object(props(
                                "series_ticker", str("Series ticker from kalshi_search, e.g. KXFED"),
                                "status", enumStr("open (default), closed or settled", List.of("open", "closed", "settled")),
                                "limit", integer("Max events (1-10, default 4)"),
                                "markets_per_event", integer("Max markets per event (1-30, default 15)")), List.of("series_ticker")),
                        Duration.ofMinutes(2), this::events,
                        a -> "Reading Kalshi " + a.path("series_ticker").asText("") + " markets…"),
                new ConnectorTool(ID + "_market",
                        "One Kalshi market by ticker: current bid/ask, last and previous price, volume, open interest, "
                                + "close/expiry times, result if settled, and its resolution rules.",
                        object(props("ticker", str("Market ticker, e.g. KXFED-26DEC-T3.50")), List.of("ticker")),
                        Duration.ofMinutes(1), this::market,
                        a -> "Reading Kalshi market " + a.path("ticker").asText("") + "…"),
                new ConnectorTool(ID + "_history",
                        "Price history of one Kalshi market: hourly or daily candles with last trade price, yes bid/ask close, "
                                + "volume and open interest — how the implied probability moved into an event.",
                        object(props(
                                "ticker", str("Market ticker"),
                                "period", enumStr("hour or day (default)", List.of("hour", "day")),
                                "days", integer("How many days back (hour: 1-14, day: 1-365; default 30 / 7)")), List.of("ticker")),
                        Duration.ofMinutes(10), this::history,
                        a -> "Reading Kalshi price history for " + a.path("ticker").asText("") + "…"));
    }

    // ── tools ────────────────────────────────────────────────────────────────

    Object search(ConnectorContext ctx, JsonNode args) {
        String query = requireStr(args, "query", 100).toLowerCase(Locale.ROOT);
        String category = oneOf(args, "category", null, CATEGORIES);
        int limit = intArg(args, "limit", 10, 1, 25);
        List<String> terms = Arrays.stream(query.split("[^a-z0-9.%]+")).filter(t -> !t.isBlank()).toList();
        if (terms.isEmpty()) throw ConnectorException.badRequest("query needs at least one word");

        List<Series> pool = new ArrayList<>();
        for (String c : category == null ? DEFAULT_CATEGORIES : List.of(category)) pool.addAll(seriesOf(ctx, c));

        List<Series> all = pool.stream().filter(s -> terms.stream().allMatch(s.haystack()::contains))
                .sorted(Comparator.comparingDouble(Series::volume).reversed()).toList();
        boolean partial = all.isEmpty();
        List<Series> hits = partial
                ? pool.stream().filter(s -> terms.stream().anyMatch(s.haystack()::contains))
                    .sorted(Comparator.<Series>comparingLong(s -> terms.stream().filter(s.haystack()::contains).count())
                            .thenComparingDouble(Series::volume).reversed()).toList()
                : all;

        List<Map<String, Object>> rows = new ArrayList<>();
        for (Series s : hits.subList(0, Math.min(limit, hits.size()))) {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("series_ticker", s.ticker());
            r.put("title", s.title());
            r.put("category", s.category());
            r.put("tags", s.tags());
            r.put("frequency", s.frequency());
            r.put("volume", s.volume());
            r.put("updated_at", s.updated());
            rows.add(r);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("query", query);
        out.put("categories", category == null ? DEFAULT_CATEGORIES : List.of(category));
        out.put("match", partial ? "partial (no series matched every word)" : "all words");
        out.put("count", rows.size());
        out.put("series", rows);
        out.put("next", "Call kalshi_events with a series_ticker for its open markets and prices.");
        return out;
    }

    Object events(ConnectorContext ctx, JsonNode args) {
        String series = tickerArg(args, "series_ticker");
        String status = oneOf(args, "status", "open", List.of("open", "closed", "settled"));
        int limit = intArg(args, "limit", 4, 1, 10);
        int perEvent = intArg(args, "markets_per_event", 15, 1, 30);
        JsonNode d = ctx.getJson(URI.create(url(base + "/events", "series_ticker", series, "status", status,
                "with_nested_markets", "true", "limit", limit)));
        List<Map<String, Object>> events = new ArrayList<>();
        for (JsonNode e : d.path("events")) {
            if (events.size() >= limit) break;
            Map<String, Object> ev = new LinkedHashMap<>();
            ev.put("event_ticker", e.path("event_ticker").asText());
            ev.put("title", e.path("title").asText());
            ev.put("sub_title", text(e, "sub_title"));
            ev.put("category", text(e, "category"));
            ev.put("mutually_exclusive", e.path("mutually_exclusive").asBoolean(false));
            ev.put("updated_at", text(e, "last_updated_ts"));
            List<Map<String, Object>> markets = new ArrayList<>();
            JsonNode ms = e.path("markets");
            for (JsonNode m : ms) {
                if (markets.size() >= perEvent) break;
                markets.add(marketRow(m, false));
            }
            ev.put("market_count", ms.size());
            ev.put("markets", markets);
            events.add(ev);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("series_ticker", series);
        out.put("status", status);
        out.put("count", events.size());
        out.put("events", events);
        out.put("note", "Prices are dollars per $1 contract: 0.62 = 62% implied probability of YES.");
        return out;
    }

    Object market(ConnectorContext ctx, JsonNode args) {
        String ticker = tickerArg(args, "ticker");
        JsonNode m = ctx.getJson(URI.create(base + "/markets/" + ticker)).path("market");
        if (m.isMissingNode()) throw ConnectorException.upstream("Kalshi answered without a market.");
        return marketRow(m, true);
    }

    Object history(ConnectorContext ctx, JsonNode args) {
        String ticker = tickerArg(args, "ticker");
        String period = oneOf(args, "period", "day", List.of("hour", "day"));
        boolean hourly = period.equals("hour");
        int days = hourly ? intArg(args, "days", 7, 1, 14) : intArg(args, "days", 30, 1, 365);
        long end = clock.instant().getEpochSecond();
        long start = end - days * 86_400L;
        JsonNode d = ctx.getJson(URI.create(url(base + "/markets/candlesticks", "market_tickers", ticker,
                "start_ts", start, "end_ts", end, "period_interval", hourly ? 60 : 1440)));
        JsonNode candles = null;
        for (JsonNode m : d.path("markets")) {
            if (ticker.equals(m.path("market_ticker").asText(ticker))) candles = m.path("candlesticks");
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        if (candles != null) {
            for (JsonNode c : candles) {
                Map<String, Object> r = new LinkedHashMap<>();
                r.put("t", Instant.ofEpochSecond(c.path("end_period_ts").asLong()).toString());
                JsonNode p = c.path("price");
                Double close = num(p, "close_dollars");
                r.put("price", close != null ? close : num(p, "previous_dollars"));
                r.put("traded", close != null);
                r.put("yes_bid", num(c.path("yes_bid"), "close_dollars"));
                r.put("yes_ask", num(c.path("yes_ask"), "close_dollars"));
                r.put("volume", num(c, "volume_fp"));
                r.put("open_interest", num(c, "open_interest_fp"));
                rows.add(r);
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ticker", ticker);
        out.put("period", period);
        out.put("from", Instant.ofEpochSecond(start).toString());
        out.put("to", Instant.ofEpochSecond(end).toString());
        out.put("count", rows.size());
        out.put("candles", rows);
        out.put("note", "t = candle end (UTC). price = last trade in the period, else the previous trade (traded=false).");
        return out;
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    List<Series> seriesOf(ConnectorContext ctx, String category) {
        CachedSeries c = series.get(category);
        Instant now = clock.instant();
        if (c != null && c.at().plus(SERIES_TTL).isAfter(now)) return c.rows();
        JsonNode d = ctx.getJson(URI.create(url(base + "/series", "category", category, "include_volume", "true")));
        List<Series> rows = new ArrayList<>();
        for (JsonNode s : d.path("series")) {
            List<String> tags = new ArrayList<>();
            s.path("tags").forEach(t -> tags.add(t.asText()));
            String title = s.path("title").asText("");
            String ticker = s.path("ticker").asText("");
            String hay = (ticker + " " + title + " " + String.join(" ", tags)).toLowerCase(Locale.ROOT);
            Double vol = num(s, "volume_fp");
            rows.add(new Series(ticker, title, s.path("category").asText(category), tags, text(s, "frequency"),
                    vol == null ? 0 : vol, text(s, "last_updated_ts"), hay));
        }
        series.put(category, new CachedSeries(List.copyOf(rows), now));
        return rows;
    }

    static Map<String, Object> marketRow(JsonNode m, boolean detail) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("ticker", m.path("ticker").asText());
        if (detail) {
            r.put("event_ticker", text(m, "event_ticker"));
            r.put("title", text(m, "title"));
        }
        String label = text(m, "yes_sub_title");
        r.put("label", label != null ? label : text(m, "subtitle"));
        r.put("status", text(m, "status"));
        r.put("yes_bid", num(m, "yes_bid_dollars"));
        r.put("yes_ask", num(m, "yes_ask_dollars"));
        r.put("last_price", num(m, "last_price_dollars"));
        if (detail) {
            r.put("previous_price", num(m, "previous_price_dollars"));
            r.put("no_bid", num(m, "no_bid_dollars"));
            r.put("no_ask", num(m, "no_ask_dollars"));
        }
        r.put("volume", num(m, "volume_fp"));
        r.put("volume_24h", num(m, "volume_24h_fp"));
        r.put("open_interest", num(m, "open_interest_fp"));
        r.put("close_time", text(m, "close_time"));
        if (detail) {
            r.put("open_time", text(m, "open_time"));
            r.put("expected_expiration_time", text(m, "expected_expiration_time"));
            r.put("rules", trim(text(m, "rules_primary"), 600));
        }
        String result = text(m, "result");
        if (result != null) r.put("result", result);
        r.put("updated_at", text(m, "updated_time"));
        return r;
    }

    static String tickerArg(JsonNode args, String name) {
        String t = requireStr(args, name, 80).toUpperCase(Locale.ROOT);
        if (!t.matches("^[A-Z0-9._-]{1,80}$")) throw ConnectorException.badRequest(name + " must look like KXFED or KXFED-26DEC-T3.50");
        return t;
    }

    static String text(JsonNode n, String field) {
        JsonNode v = n.path(field);
        if (v.isMissingNode() || v.isNull()) return null;
        String s = v.asText("").strip();
        return s.isEmpty() ? null : s;
    }

    /** Kalshi sends prices and sizes as decimal strings ("0.9800", "6801.79"). */
    static Double num(JsonNode n, String field) {
        JsonNode v = n.path(field);
        if (v.isNumber()) return v.asDouble();
        String s = text(n, field);
        if (s == null) return null;
        try {
            return Double.valueOf(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    static String trim(String s, int max) {
        if (s == null) return null;
        String t = s.replaceAll("\\s+", " ").strip();
        return t.length() > max ? t.substring(0, max) + "…" : t;
    }
}
