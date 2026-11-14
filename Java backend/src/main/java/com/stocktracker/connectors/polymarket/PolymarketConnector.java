package com.stocktracker.connectors.polymarket;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
 * Polymarket: event/market search and current odds from the Gamma API, price history from the
 * CLOB. Both are public and keyless.
 *
 * <p>Point-in-time: events and markets carry Polymarket's {@code updatedAt}; history points their
 * own timestamps.
 */
@Component
public class PolymarketConnector implements Connector {

    static final String ID = "polymarket";
    static final String GAMMA_URL = "https://gamma-api.polymarket.com";
    static final String CLOB_URL = "https://clob.polymarket.com";

    private static final ObjectMapper JSON = new ObjectMapper();

    private static final ConnectorSpec SPEC = new ConnectorSpec(
            ID,
            "Polymarket",
            ConnectorSpec.Category.PREDICTION_MARKETS,
            // Keyless public API; Polymarket's terms cover the user's own use, not redistribution.
            ConnectorSpec.Licence.BYO_PERSONAL,
            "Polymarket prediction-market odds on Fed decisions, inflation, recessions, elections, geopolitics, "
                    + "company and crypto events: implied probabilities, volume, liquidity and price history — "
                    + "a read on what the crowd prices for a catalyst versus what assets already discount.",
            "Market data: Polymarket (polymarket.com)",
            "https://docs.polymarket.com/",
            List.of(),
            // Documented: Gamma /markets 300 per 10 s, /public-search 350 per 10 s, CLOB prices-history 1000 per 10 s.
            List.of(RateLimit.perSecond(5)));

    private final String gamma;
    private final String clob;
    private final Clock clock;

    public PolymarketConnector() {
        this(GAMMA_URL, CLOB_URL, Clock.systemUTC());
    }

    /** Test seam: point at a fake Polymarket. */
    PolymarketConnector(String gamma, String clob, Clock clock) {
        this.gamma = gamma;
        this.clob = clob;
        this.clock = clock;
    }

    @Override
    public ConnectorSpec spec() {
        return SPEC;
    }

    @Override
    public String test(ConnectorContext ctx) {
        JsonNode d = ctx.getJson(URI.create(url(gamma + "/markets", "limit", 1, "closed", "false")));
        int n = d.isArray() ? d.size() : 0;
        return "Connected — Polymarket answered (" + (n > 0 ? "e.g. \"" + d.get(0).path("question").asText("?") + "\"" : "no open markets") + ").";
    }

    @Override
    public List<ConnectorTool> tools() {
        return List.of(
                new ConnectorTool(ID + "_search",
                        "Search Polymarket events by keywords (e.g. \"fed rate\", \"recession\", \"tariffs\", \"nvidia\"). "
                                + "Each event lists its markets with the YES price (= implied probability), volume and liquidity.",
                        object(props(
                                "query", str("Keywords"),
                                "status", enumStr("active (default), resolved or all", List.of("active", "resolved", "all")),
                                "limit", integer("Max events (1-10, default 5)"),
                                "markets_per_event", integer("Max markets per event (1-20, default 8)")), List.of("query")),
                        Duration.ofMinutes(5), this::search,
                        a -> "Searching Polymarket for " + a.path("query").asText("") + "…"),
                new ConnectorTool(ID + "_event",
                        "One Polymarket event by slug with all of its markets (e.g. every outcome of \"How many Fed rate cuts in 2026?\").",
                        object(props(
                                "slug", str("Event slug from polymarket_search"),
                                "limit", integer("Max markets (1-40, default 25)")), List.of("slug")),
                        Duration.ofMinutes(2), this::event,
                        a -> "Reading Polymarket event " + a.path("slug").asText("") + "…"),
                new ConnectorTool(ID + "_market",
                        "One Polymarket market by slug: outcome prices, best bid/ask, last trade, 1h/1d/1w/1m price change, "
                                + "volume, liquidity, end date and resolution criteria.",
                        object(props("slug", str("Market slug from polymarket_search or polymarket_event")), List.of("slug")),
                        Duration.ofMinutes(1), this::market,
                        a -> "Reading Polymarket market " + a.path("slug").asText("") + "…"),
                new ConnectorTool(ID + "_history",
                        "Price history of one outcome of a Polymarket market (default the first outcome, usually Yes): "
                                + "how its implied probability moved over the last N days.",
                        object(props(
                                "slug", str("Market slug"),
                                "outcome", str("Outcome name, e.g. Yes or No (default: first outcome)"),
                                "days", integer("How many days back (1-365, default 30)")), List.of("slug")),
                        Duration.ofMinutes(10), this::history,
                        a -> "Reading Polymarket price history for " + a.path("slug").asText("") + "…"));
    }

    // ── tools ────────────────────────────────────────────────────────────────

    Object search(ConnectorContext ctx, JsonNode args) {
        String query = requireStr(args, "query", 100);
        String status = oneOf(args, "status", "active", List.of("active", "resolved", "all"));
        int limit = intArg(args, "limit", 5, 1, 10);
        int perEvent = intArg(args, "markets_per_event", 8, 1, 20);
        JsonNode d = ctx.getJson(URI.create(url(gamma + "/public-search", "q", query, "limit_per_type", limit,
                "events_status", status.equals("all") ? null : status, "search_profiles", "false", "search_tags", "false")));
        List<Map<String, Object>> events = new ArrayList<>();
        for (JsonNode e : d.path("events")) {
            if (events.size() >= limit) break;
            events.add(eventRow(e, perEvent));
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("query", query);
        out.put("status", status);
        out.put("total_results", d.path("pagination").path("totalResults").isNumber() ? d.path("pagination").path("totalResults").asInt() : null);
        out.put("count", events.size());
        out.put("events", events);
        out.put("note", "yes_price is in dollars per $1 share: 0.62 = 62% implied probability.");
        return out;
    }

    Object event(ConnectorContext ctx, JsonNode args) {
        String slug = slugArg(args);
        int limit = intArg(args, "limit", 25, 1, 40);
        JsonNode d = ctx.getJson(URI.create(url(gamma + "/events", "slug", slug)));
        if (!d.isArray() || d.isEmpty()) throw ConnectorException.badRequest("No Polymarket event with slug " + slug + ". Use polymarket_search to find it.");
        return eventRow(d.get(0), limit);
    }

    Object market(ConnectorContext ctx, JsonNode args) {
        JsonNode m = findMarket(ctx, slugArg(args));
        Map<String, Object> r = marketRow(m);
        r.put("best_bid", num(m, "bestBid"));
        r.put("best_ask", num(m, "bestAsk"));
        r.put("spread", num(m, "spread"));
        r.put("change_1h", num(m, "oneHourPriceChange"));
        r.put("change_1d", num(m, "oneDayPriceChange"));
        r.put("change_1w", num(m, "oneWeekPriceChange"));
        r.put("change_1m", num(m, "oneMonthPriceChange"));
        r.put("volume_1w", num(m, "volume1wk"));
        r.put("start_date", text(m, "startDate"));
        r.put("resolution_source", text(m, "resolutionSource"));
        r.put("rules", trim(text(m, "description"), 600));
        r.put("created_at", text(m, "createdAt"));
        return r;
    }

    Object history(ConnectorContext ctx, JsonNode args) {
        String slug = slugArg(args);
        String outcome = optStr(args, "outcome");
        int days = intArg(args, "days", 30, 1, 365);
        JsonNode m = findMarket(ctx, slug);
        List<String> outcomes = strList(m.path("outcomes"));
        List<String> tokens = strList(m.path("clobTokenIds"));
        if (tokens.isEmpty()) throw ConnectorException.badRequest("Market " + slug + " has no order book, so no price history.");
        int idx = 0;
        if (outcome != null) {
            idx = -1;
            for (int i = 0; i < outcomes.size(); i++) if (outcomes.get(i).equalsIgnoreCase(outcome)) idx = i;
            if (idx < 0 || idx >= tokens.size()) throw ConnectorException.badRequest("outcome must be one of " + outcomes);
        }
        long end = clock.instant().getEpochSecond();
        long start = end - days * 86_400L;
        int fidelity = Math.max(10, days * 1440 / 200);   // minutes per point: ≤ ~200 points
        JsonNode d = ctx.getJson(URI.create(url(clob + "/prices-history", "market", tokens.get(idx),
                "startTs", start, "endTs", end, "fidelity", fidelity)));
        List<Map<String, Object>> points = new ArrayList<>();
        for (JsonNode p : d.path("history")) {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("t", Instant.ofEpochSecond(p.path("t").asLong()).toString());
            r.put("p", p.path("p").asDouble());
            points.add(r);
        }
        if (points.size() > 250) points = points.subList(points.size() - 250, points.size());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("slug", slug);
        out.put("question", text(m, "question"));
        out.put("outcome", idx < outcomes.size() ? outcomes.get(idx) : null);
        out.put("from", Instant.ofEpochSecond(start).toString());
        out.put("to", Instant.ofEpochSecond(end).toString());
        out.put("interval_minutes", fidelity);
        out.put("count", points.size());
        out.put("points", points);
        return out;
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    JsonNode findMarket(ConnectorContext ctx, String slug) {
        JsonNode d = ctx.getJson(URI.create(url(gamma + "/markets", "slug", slug)));
        if (!d.isArray() || d.isEmpty()) {
            throw ConnectorException.badRequest("No Polymarket market with slug " + slug
                    + ". Market slugs come from polymarket_search / polymarket_event (an event slug is not a market slug).");
        }
        return d.get(0);
    }

    static Map<String, Object> eventRow(JsonNode e, int maxMarkets) {
        Map<String, Object> ev = new LinkedHashMap<>();
        ev.put("slug", text(e, "slug"));
        ev.put("title", text(e, "title"));
        ev.put("end_date", text(e, "endDate"));
        ev.put("closed", e.path("closed").asBoolean(false));
        ev.put("volume", num(e, "volume"));
        ev.put("volume_24h", num(e, "volume24hr"));
        ev.put("liquidity", num(e, "liquidity"));
        ev.put("open_interest", num(e, "openInterest"));
        ev.put("updated_at", text(e, "updatedAt"));
        List<Map<String, Object>> markets = new ArrayList<>();
        JsonNode ms = e.path("markets");
        for (JsonNode m : ms) {
            if (markets.size() >= maxMarkets) break;
            markets.add(marketRow(m));
        }
        ev.put("market_count", ms.size());
        ev.put("markets", markets);
        return ev;
    }

    static Map<String, Object> marketRow(JsonNode m) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("slug", text(m, "slug"));
        r.put("question", text(m, "question"));
        String label = text(m, "groupItemTitle");
        if (label != null) r.put("label", label);
        List<String> outcomes = strList(m.path("outcomes"));
        List<String> prices = strList(m.path("outcomePrices"));
        Map<String, Object> op = new LinkedHashMap<>();
        for (int i = 0; i < outcomes.size() && i < prices.size(); i++) op.put(outcomes.get(i), parse(prices.get(i)));
        r.put("outcome_prices", op);
        if (!prices.isEmpty() && !outcomes.isEmpty() && outcomes.get(0).equalsIgnoreCase("yes")) r.put("yes_price", parse(prices.get(0)));
        r.put("last_trade", num(m, "lastTradePrice"));
        r.put("volume", num(m, "volume"));
        r.put("volume_24h", num(m, "volume24hr"));
        r.put("liquidity", num(m, "liquidity"));
        r.put("end_date", text(m, "endDate"));
        r.put("closed", m.path("closed").asBoolean(false));
        r.put("updated_at", text(m, "updatedAt"));
        return r;
    }

    static String slugArg(JsonNode args) {
        String s = requireStr(args, "slug", 200);
        if (!s.matches("^[A-Za-z0-9._-]{1,200}$")) throw ConnectorException.badRequest("slug must look like will-the-fed-cut-rates-in-december");
        return s;
    }

    /** Gamma encodes outcome lists as JSON strings ("[\"Yes\", \"No\"]"); accept real arrays too. */
    static List<String> strList(JsonNode n) {
        List<String> out = new ArrayList<>();
        JsonNode arr = n;
        if (n.isTextual()) {
            try {
                arr = JSON.readTree(n.asText());
            } catch (Exception e) {
                return out;
            }
        }
        if (arr != null && arr.isArray()) arr.forEach(x -> out.add(x.asText()));
        return out;
    }

    static String text(JsonNode n, String field) {
        JsonNode v = n.path(field);
        if (v.isMissingNode() || v.isNull()) return null;
        String s = v.asText("").strip();
        return s.isEmpty() ? null : s;
    }

    static Double num(JsonNode n, String field) {
        JsonNode v = n.path(field);
        if (v.isNumber()) return v.asDouble();
        return parse(text(n, field));
    }

    static Double parse(String s) {
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
