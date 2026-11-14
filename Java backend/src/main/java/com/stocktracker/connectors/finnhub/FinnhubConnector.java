package com.stocktracker.connectors.finnhub;

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
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.stocktracker.connectors.ToolArgs.enumStr;
import static com.stocktracker.connectors.ToolArgs.integer;
import static com.stocktracker.connectors.ToolArgs.object;
import static com.stocktracker.connectors.ToolArgs.props;
import static com.stocktracker.connectors.ToolArgs.str;

/**
 * Finnhub REST (Connectors roadmap §1): company and market news, the earnings calendar, analyst
 * recommendation trends and insider sentiment (MSPR) — the endpoints on Finnhub's free plan. The
 * economic calendar is premium-only on Finnhub and is left out. Key in {@code X-Finnhub-Token}.
 *
 * <p>Default date ranges are computed in US Eastern (the market's calendar) from the injected clock.
 */
@Component
public class FinnhubConnector implements Connector {

    static final String ID = "finnhub";
    static final String BASE_URL = "https://finnhub.io/api/v1";
    static final ZoneId ET = ZoneId.of("America/New_York");

    private static final ConnectorSpec SPEC = new ConnectorSpec(
            ID,
            "Finnhub",
            ConnectorSpec.Category.NEWS,
            ConnectorSpec.Licence.BYO_PERSONAL,
            "Company news (North American companies) and market news, the earnings calendar with EPS/revenue "
                    + "estimates and actuals, analyst recommendation trends and monthly insider sentiment (MSPR).",
            "Source: Finnhub.io",
            "https://finnhub.io/docs/api",
            List.of(Field.secret("api_key", "API key",
                    "Free at finnhub.io/register (60 calls a minute, personal use). Paid plans carry their own licence.")),
            List.of(RateLimit.perSecond(20), RateLimit.perMinute(55)));   // free plan: 60/min; keep headroom

    private final Clock clock;
    private final String base;

    public FinnhubConnector() {
        this(Clock.systemUTC(), BASE_URL);
    }

    /** Test seam: point at a fake Finnhub. */
    FinnhubConnector(Clock clock, String base) {
        this.clock = clock;
        this.base  = base;
    }

    @Override
    public ConnectorSpec spec() {
        return SPEC;
    }

    @Override
    public String test(ConnectorContext ctx) {
        JsonNode q = get(ctx, "/quote", "symbol", "AAPL");
        if (!q.path("c").isNumber()) throw ConnectorException.upstream("Finnhub answered without a quote.");
        return "Connected — Finnhub answered (AAPL last " + q.path("c").asText() + ").";
    }

    @Override
    public List<ConnectorTool> tools() {
        Map<String, Object> symbol = str("US ticker, e.g. AAPL");
        return List.of(
                new ConnectorTool(ID + "_company_news",
                        "News about one North American company, newest first, with publication time (UTC), source, "
                                + "headline, summary and link. Free plan: up to one year back.",
                        object(props(
                                "symbol", symbol,
                                "from", str("From date YYYY-MM-DD (default 7 days ago)"),
                                "to", str("To date YYYY-MM-DD (default today)"),
                                "limit", integer("Articles (1-50, default 20)")), List.of("symbol")),
                        Duration.ofMinutes(15), this::companyNews, a -> "Reading Finnhub news for " + a.path("symbol").asText("") + "…"),
                new ConnectorTool(ID + "_market_news",
                        "Latest market news by category, newest first, with publication time (UTC).",
                        object(props(
                                "category", enumStr("general (default), forex, crypto or merger", List.of("general", "forex", "crypto", "merger")),
                                "limit", integer("Articles (1-50, default 20)")), List.of()),
                        Duration.ofMinutes(15), this::marketNews, a -> "Reading Finnhub market news…"),
                new ConnectorTool(ID + "_earnings_calendar",
                        "Earnings releases in a date range (or for one symbol): date, before/after market, fiscal quarter, "
                                + "EPS and revenue estimate vs actual. Free plan: one month of history plus upcoming.",
                        object(props(
                                "from", str("From date YYYY-MM-DD (default today)"),
                                "to", str("To date YYYY-MM-DD (default 14 days ahead)"),
                                "symbol", str("Only this ticker"),
                                "limit", integer("Releases (1-200, default 50)")), List.of()),
                        Duration.ofHours(1), this::earningsCalendar, a -> "Reading the earnings calendar…"),
                new ConnectorTool(ID + "_recommendation_trends",
                        "Analyst recommendation counts per month (strong buy, buy, hold, sell, strong sell), newest first.",
                        object(props("symbol", symbol, "months", integer("Months (1-24, default 6)")), List.of("symbol")),
                        Duration.ofHours(12), this::recommendations, a -> "Reading analyst trends for " + a.path("symbol").asText("") + "…"),
                new ConnectorTool(ID + "_insider_sentiment",
                        "Monthly insider sentiment for a US company: MSPR (-100 most negative … +100 most positive) and net "
                                + "share change from insider transactions.",
                        object(props(
                                "symbol", symbol,
                                "from", str("From date YYYY-MM-DD (default 12 months ago)"),
                                "to", str("To date YYYY-MM-DD (default today)")), List.of("symbol")),
                        Duration.ofHours(12), this::insiderSentiment, a -> "Reading insider sentiment for " + a.path("symbol").asText("") + "…"));
    }

    // ── tools ────────────────────────────────────────────────────────────────

    Object companyNews(ConnectorContext ctx, JsonNode args) {
        String symbol = ToolArgs.ticker(args, "symbol");
        LocalDate[] range = range(args, 7, 0);
        int limit = ToolArgs.intArg(args, "limit", 20, 1, 50);
        JsonNode r = get(ctx, "/company-news", "symbol", symbol, "from", range[0], "to", range[1]);
        return Map.of("symbol", symbol, "from", range[0].toString(), "to", range[1].toString(), "articles", articles(r, limit));
    }

    Object marketNews(ConnectorContext ctx, JsonNode args) {
        String category = ToolArgs.oneOf(args, "category", "general", List.of("general", "forex", "crypto", "merger"));
        int limit = ToolArgs.intArg(args, "limit", 20, 1, 50);
        return Map.of("category", category, "articles", articles(get(ctx, "/news", "category", category), limit));
    }

    Object earningsCalendar(ConnectorContext ctx, JsonNode args) {
        LocalDate[] range = range(args, 0, 14);
        String symbol = ToolArgs.optStr(args, "symbol") == null ? null : ToolArgs.ticker(args, "symbol");
        int limit = ToolArgs.intArg(args, "limit", 50, 1, 200);
        JsonNode r = get(ctx, "/calendar/earnings", "from", range[0], "to", range[1], "symbol", symbol);
        List<JsonNode> rows = new ArrayList<>();
        r.path("earningsCalendar").forEach(rows::add);
        rows.sort(Comparator.comparing((JsonNode n) -> n.path("date").asText("")).thenComparing(n -> n.path("symbol").asText("")));
        List<Map<String, Object>> out = new ArrayList<>();
        for (JsonNode e : rows) {
            if (out.size() >= limit) break;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("date", e.path("date").asText(null));
            m.put("hour", hour(e.path("hour").asText("")));
            m.put("symbol", e.path("symbol").asText(null));
            m.put("year", e.path("year").isNumber() ? e.path("year").asInt() : null);
            m.put("quarter", e.path("quarter").isNumber() ? e.path("quarter").asInt() : null);
            m.put("eps_estimate", num(e.get("epsEstimate")));
            m.put("eps_actual", num(e.get("epsActual")));
            m.put("revenue_estimate", num(e.get("revenueEstimate")));
            m.put("revenue_actual", num(e.get("revenueActual")));
            out.add(m);
        }
        Map<String, Object> res = new LinkedHashMap<>();
        res.put("from", range[0].toString());
        res.put("to", range[1].toString());
        res.put("total", rows.size());
        res.put("releases", out);
        return res;
    }

    Object recommendations(ConnectorContext ctx, JsonNode args) {
        String symbol = ToolArgs.ticker(args, "symbol");
        int months = ToolArgs.intArg(args, "months", 6, 1, 24);
        JsonNode r = get(ctx, "/stock/recommendation", "symbol", symbol);
        List<JsonNode> rows = new ArrayList<>();
        r.forEach(rows::add);
        rows.sort(Comparator.comparing((JsonNode n) -> n.path("period").asText("")).reversed());
        List<Map<String, Object>> out = new ArrayList<>();
        for (JsonNode t : rows) {
            if (out.size() >= months) break;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("period", t.path("period").asText(null));
            for (String k : List.of("strongBuy", "buy", "hold", "sell", "strongSell")) m.put(k, t.path(k).asInt());
            out.add(m);
        }
        return Map.of("symbol", symbol, "trends", out);
    }

    Object insiderSentiment(ConnectorContext ctx, JsonNode args) {
        String symbol = ToolArgs.ticker(args, "symbol");
        LocalDate[] range = range(args, 365, 0);
        JsonNode r = get(ctx, "/stock/insider-sentiment", "symbol", symbol, "from", range[0], "to", range[1]);
        List<JsonNode> rows = new ArrayList<>();
        r.path("data").forEach(rows::add);
        rows.sort(Comparator.comparing((JsonNode n) -> n.path("year").asInt() * 100 + n.path("month").asInt()).reversed());
        List<Map<String, Object>> out = new ArrayList<>();
        for (JsonNode d : rows) {
            if (out.size() >= 24) break;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("month", String.format("%04d-%02d", d.path("year").asInt(), d.path("month").asInt()));
            m.put("mspr", num(d.get("mspr")));
            m.put("change", d.path("change").isNumber() ? d.path("change").asLong() : null);
            out.add(m);
        }
        return Map.of("symbol", symbol, "from", range[0].toString(), "to", range[1].toString(), "months", out);
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    JsonNode get(ConnectorContext ctx, String path, Object... query) {
        Object[] q = new Object[query.length];
        for (int i = 0; i < query.length; i++) q[i] = query[i] instanceof LocalDate d ? d.toString() : query[i];
        return ctx.getJson(URI.create(ToolArgs.url(base + path, q)), Map.of("X-Finnhub-Token", ctx.require("api_key")));
    }

    /** {from, to}: given dates, else today (ET) minus {@code back} / plus {@code ahead} days. */
    LocalDate[] range(JsonNode args, int back, int ahead) {
        LocalDate today = LocalDate.now(clock.withZone(ET));
        LocalDate from = ToolArgs.date(args, "from");
        LocalDate to = ToolArgs.date(args, "to");
        if (from == null) from = today.minusDays(back);
        if (to == null) to = today.plusDays(ahead);
        if (to.isBefore(from)) throw ConnectorException.badRequest("to must not be before from");
        return new LocalDate[] {from, to};
    }

    static List<Map<String, Object>> articles(JsonNode r, int limit) {
        List<JsonNode> rows = new ArrayList<>();
        r.forEach(rows::add);
        rows.sort(Comparator.comparing((JsonNode n) -> n.path("datetime").asLong()).reversed());
        List<Map<String, Object>> out = new ArrayList<>();
        for (JsonNode a : rows) {
            if (out.size() >= limit) break;
            Map<String, Object> m = new LinkedHashMap<>();
            long t = a.path("datetime").asLong(0);
            m.put("published_utc", t > 0 ? Instant.ofEpochSecond(t).toString() : null);
            m.put("headline", a.path("headline").asText(null));
            m.put("source", a.path("source").asText(null));
            m.put("related", a.path("related").asText(null));
            m.put("summary", trim(a.path("summary").asText(null), 400));
            m.put("url", a.path("url").asText(null));
            out.add(m);
        }
        return out;
    }

    static String hour(String h) {
        return switch (h) {
            case "bmo" -> "before market open";
            case "amc" -> "after market close";
            case "dmh" -> "during market hours";
            default -> h.isEmpty() ? null : h;
        };
    }

    static Double num(JsonNode n) {
        return n == null || n.isNull() || !n.isNumber() ? null : n.asDouble();
    }

    static String trim(String s, int max) {
        if (s == null || s.isEmpty()) return s;
        return s.length() > max ? s.substring(0, max) + "…" : s;
    }
}
