package com.stocktracker.connectors.fmp;

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
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static com.stocktracker.connectors.ToolArgs.*;

/**
 * Financial Modeling Prep (Connectors roadmap, filings/estimates): earnings-call transcripts, analyst
 * estimates, price targets and rating changes, earnings/IPO/economic calendars and Form 4 insider
 * trades, from FMP's current {@code /stable} API with the user's own key.
 *
 * <p>Paths, parameters and response fields follow the stable API docs
 * (site.financialmodelingprep.com/developer/docs/stable) and their published sample responses.
 * Point-in-time: insider rows carry the SEC filing date next to the transaction date; transcripts
 * carry the call date; analyst estimates are the current consensus (not as-first-published).
 */
@Component
public class FmpConnector implements Connector {

    static final String ID = "fmp";
    static final String BASE_URL = "https://financialmodelingprep.com/stable";
    static final ZoneId EASTERN = ZoneId.of("America/New_York");

    private static final ConnectorSpec SPEC = new ConnectorSpec(
            ID,
            "Financial Modeling Prep",
            ConnectorSpec.Category.FILINGS,
            ConnectorSpec.Licence.BYO_PERSONAL,   // individual plans are personal use; display/redistribution needs a separate licence
            "Earnings-call transcripts, analyst revenue/EPS estimates, price-target consensus and rating "
                    + "changes, earnings/IPO/economic calendars, and SEC Form 4 insider trades with filing dates. "
                    + "Some endpoints (transcripts) need a paid FMP plan.",
            "Data: Financial Modeling Prep (financialmodelingprep.com)",
            "https://site.financialmodelingprep.com/developer/docs/stable",
            List.of(Field.secret("api_key", "API key", "Your FMP API key from site.financialmodelingprep.com/dashboard.")),
            // Paid plans allow 300-3,000 calls/min; the free plan is 250 calls/day (FMP answers 429 past it).
            List.of(RateLimit.perMinute(60)));

    private final Clock clock;
    private final String base;

    public FmpConnector() {
        this(Clock.systemUTC(), BASE_URL);
    }

    /** Test seam: point at a fake FMP with a fixed clock (calendar defaults). */
    FmpConnector(Clock clock, String base) {
        this.clock = clock;
        this.base = base;
    }

    @Override
    public ConnectorSpec spec() {
        return SPEC;
    }

    @Override
    public String test(ConnectorContext ctx) {
        List<JsonNode> q = list(get(ctx, "/quote", "symbol", "AAPL"));
        return "Connected — FMP answered" + (q.isEmpty() ? "." : " (AAPL " + q.get(0).path("price").asText("?") + ").");
    }

    @Override
    public List<ConnectorTool> tools() {
        return List.of(
                new ConnectorTool(ID + "_transcript",
                        "An earnings-call transcript (prepared remarks and Q&A) for a company and fiscal quarter, returned "
                                + "in chunks: use offset/next_offset to read on. Without year/quarter: the latest call.",
                        object(props(
                                "symbol", str("Ticker symbol"),
                                "year", integer("Fiscal year, e.g. 2026"),
                                "quarter", integer("Fiscal quarter 1-4"),
                                "offset", integer("Character offset to start from (default 0)"),
                                "max_chars", integer("Characters to return (2000-40000, default 12000)")), List.of("symbol")),
                        Duration.ofDays(7), this::transcript,
                        a -> "Reading the " + a.path("symbol").asText("") + " earnings call transcript…"),
                new ConnectorTool(ID + "_transcript_dates",
                        "Which earnings-call transcripts exist for a company: fiscal year, quarter and call date, newest first.",
                        object(props(
                                "symbol", str("Ticker symbol"),
                                "limit", integer("Max rows (1-40, default 12)")), List.of("symbol")),
                        Duration.ofDays(1), this::transcriptDates,
                        a -> "Listing " + a.path("symbol").asText("") + " transcripts…"),
                new ConnectorTool(ID + "_analyst_estimates",
                        "Analyst consensus estimates per fiscal period: revenue, EBITDA, net income and EPS (low/avg/high) "
                                + "and the number of analysts, nearest period first.",
                        object(props(
                                "symbol", str("Ticker symbol"),
                                "period", enumStr("annual (default) or quarter", List.of("annual", "quarter")),
                                "limit", integer("Max periods (1-20, default 6)")), List.of("symbol")),
                        Duration.ofHours(12), this::estimates,
                        a -> "Reading analyst estimates for " + a.path("symbol").asText("") + "…"),
                new ConnectorTool(ID + "_price_targets",
                        "Analyst price targets and ratings for a company: consensus/median/high/low target, average target "
                                + "over the last month/quarter/year with counts, and the latest rating actions "
                                + "(firm, previous → new grade, action, date).",
                        object(props(
                                "symbol", str("Ticker symbol"),
                                "grades", integer("Latest rating actions to include (0-30, default 10)")), List.of("symbol")),
                        Duration.ofHours(6), this::priceTargets,
                        a -> "Reading price targets for " + a.path("symbol").asText("") + "…"),
                new ConnectorTool(ID + "_calendar",
                        "Calendars over a date range (max 90 days; default today → +7 days, US Eastern): earnings (EPS and "
                                + "revenue estimate vs actual), IPOs, or economic releases (event, country, actual/estimate/previous, impact).",
                        object(props(
                                "kind", enumStr("earnings (default), ipo or economic", List.of("earnings", "ipo", "economic")),
                                "from", str("Start date YYYY-MM-DD"),
                                "to", str("End date YYYY-MM-DD"),
                                "symbols", strArray("earnings/ipo: only these tickers"),
                                "country", str("economic: ISO country code, e.g. US"),
                                "impact", enumStr("economic: minimum impact", List.of("Low", "Medium", "High")),
                                "limit", integer("Max rows (1-200, default 50)")), List.of()),
                        Duration.ofHours(1), this::calendar,
                        a -> "Reading the FMP " + a.path("kind").asText("earnings") + " calendar…"),
                new ConnectorTool(ID + "_insider_trades",
                        "SEC Form 4 insider trades, newest first: insider, relationship, transaction type, shares, price, "
                                + "holdings after, transaction date and filing date (when it became public), with the SEC link.",
                        object(props(
                                "symbol", str("Ticker symbol (omit for all companies)"),
                                "transaction_type", str("e.g. P-Purchase or S-Sale"),
                                "limit", integer("Max rows (1-100, default 25)")), List.of()),
                        Duration.ofMinutes(30), this::insiderTrades,
                        a -> "Reading insider trades from FMP…"));
    }

    // ── tools ────────────────────────────────────────────────────────────────

    Object transcript(ConnectorContext ctx, JsonNode args) {
        String symbol = ticker(args, "symbol");
        int offset = intArg(args, "offset", 0, 0, Integer.MAX_VALUE);
        int max = intArg(args, "max_chars", 12_000, 2_000, 40_000);
        Integer year = args.hasNonNull("year") ? intArg(args, "year", 0, 1990, 2100) : null;
        Integer quarter = args.hasNonNull("quarter") ? intArg(args, "quarter", 0, 1, 4) : null;
        if (year == null != (quarter == null)) throw ConnectorException.badRequest("give both year and quarter, or neither for the latest call");
        if (year == null) {
            List<JsonNode> dates = sortedDates(ctx, symbol);
            if (dates.isEmpty()) throw ConnectorException.badRequest("FMP has no earnings-call transcripts for " + symbol + ".");
            year = dates.get(0).path("fiscalYear").asInt();
            quarter = dates.get(0).path("quarter").asInt();
        }
        List<JsonNode> rows = list(get(ctx, "/earning-call-transcript", "symbol", symbol, "year", String.valueOf(year), "quarter", String.valueOf(quarter)));
        if (rows.isEmpty()) throw ConnectorException.badRequest("FMP has no transcript for " + symbol + " " + year + " Q" + quarter + ".");
        JsonNode t = rows.get(0);
        String content = t.path("content").asText("");
        int from = Math.min(offset, content.length());
        int to = Math.min(content.length(), from + max);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("symbol", t.path("symbol").asText(symbol));
        out.put("fiscal_year", t.path("year").asInt(year));
        out.put("period", t.path("period").asText("Q" + quarter));
        out.put("call_date", text(t, "date"));
        out.put("total_chars", content.length());
        out.put("offset", from);
        out.put("next_offset", to < content.length() ? to : null);
        out.put("content", content.substring(from, to));
        return out;
    }

    Object transcriptDates(ConnectorContext ctx, JsonNode args) {
        String symbol = ticker(args, "symbol");
        int limit = intArg(args, "limit", 12, 1, 40);
        List<Map<String, Object>> rows = new ArrayList<>();
        for (JsonNode r : sortedDates(ctx, symbol)) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("fiscal_year", r.path("fiscalYear").asInt());
            m.put("quarter", r.path("quarter").asInt());
            m.put("call_date", text(r, "date"));
            rows.add(m);
            if (rows.size() >= limit) break;
        }
        return Map.of("symbol", symbol, "count", rows.size(), "transcripts", rows);
    }

    Object estimates(ConnectorContext ctx, JsonNode args) {
        String symbol = ticker(args, "symbol");
        String period = oneOf(args, "period", "annual", List.of("annual", "quarter"));
        int limit = intArg(args, "limit", 6, 1, 20);
        // FMP returns furthest-out periods first; ask for enough rows to reach the nearest ones.
        List<Map<String, Object>> rows = new ArrayList<>();
        for (JsonNode r : list(get(ctx, "/analyst-estimates", "symbol", symbol, "period", period, "page", "0", "limit", "40"))) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("period_end", text(r, "date"));
            m.put("revenue_avg", num(r, "revenueAvg"));
            m.put("revenue_low", num(r, "revenueLow"));
            m.put("revenue_high", num(r, "revenueHigh"));
            m.put("ebitda_avg", num(r, "ebitdaAvg"));
            m.put("net_income_avg", num(r, "netIncomeAvg"));
            m.put("eps_avg", num(r, "epsAvg"));
            m.put("eps_low", num(r, "epsLow"));
            m.put("eps_high", num(r, "epsHigh"));
            m.put("analysts_revenue", num(r, "numAnalystsRevenue"));
            m.put("analysts_eps", num(r, "numAnalystsEps"));
            rows.add(m);
        }
        String today = LocalDate.now(clock.withZone(EASTERN)).toString();
        rows.sort(Comparator.comparing(m -> String.valueOf(m.get("period_end"))));
        // Nearest first: the first period ending on/after today, then later ones.
        List<Map<String, Object>> upcoming = new ArrayList<>(rows.stream().filter(m -> String.valueOf(m.get("period_end")).compareTo(today) >= 0).toList());
        if (upcoming.isEmpty()) upcoming = rows.subList(Math.max(0, rows.size() - limit), rows.size());
        List<Map<String, Object>> out = upcoming.subList(0, Math.min(limit, upcoming.size()));
        Map<String, Object> res = new LinkedHashMap<>();
        res.put("symbol", symbol);
        res.put("period", period);
        res.put("count", out.size());
        res.put("estimates", out);
        res.put("note", "Current consensus as of today, not as first published — do not use for point-in-time backtests.");
        return res;
    }

    Object priceTargets(ConnectorContext ctx, JsonNode args) {
        String symbol = ticker(args, "symbol");
        int grades = intArg(args, "grades", 10, 0, 30);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("symbol", symbol);
        List<JsonNode> cons = list(get(ctx, "/price-target-consensus", "symbol", symbol));
        if (!cons.isEmpty()) {
            JsonNode c = cons.get(0);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("consensus", num(c, "targetConsensus"));
            m.put("median", num(c, "targetMedian"));
            m.put("high", num(c, "targetHigh"));
            m.put("low", num(c, "targetLow"));
            out.put("consensus", m);
        }
        List<JsonNode> sum = list(get(ctx, "/price-target-summary", "symbol", symbol));
        if (!sum.isEmpty()) {
            JsonNode s = sum.get(0);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("last_month_avg", num(s, "lastMonthAvgPriceTarget"));
            m.put("last_month_count", num(s, "lastMonthCount"));
            m.put("last_quarter_avg", num(s, "lastQuarterAvgPriceTarget"));
            m.put("last_quarter_count", num(s, "lastQuarterCount"));
            m.put("last_year_avg", num(s, "lastYearAvgPriceTarget"));
            m.put("last_year_count", num(s, "lastYearCount"));
            out.put("summary", m);
        }
        if (grades > 0) {
            List<Map<String, Object>> rows = new ArrayList<>();
            for (JsonNode g : list(get(ctx, "/grades", "symbol", symbol))) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("date", text(g, "date"));
                m.put("firm", text(g, "gradingCompany"));
                m.put("previous_grade", text(g, "previousGrade"));
                m.put("new_grade", text(g, "newGrade"));
                m.put("action", text(g, "action"));
                rows.add(m);
            }
            rows.sort(Comparator.comparing((Map<String, Object> m) -> String.valueOf(m.get("date"))).reversed());
            out.put("rating_actions", rows.subList(0, Math.min(grades, rows.size())));
        }
        return out;
    }

    Object calendar(ConnectorContext ctx, JsonNode args) {
        String kind = oneOf(args, "kind", "earnings", List.of("earnings", "ipo", "economic"));
        LocalDate today = LocalDate.now(clock.withZone(EASTERN));
        LocalDate from = date(args, "from");
        LocalDate to = date(args, "to");
        if (from == null) from = to == null ? today : to.minusDays(7);
        if (to == null) to = from.plusDays(7);
        if (to.isBefore(from)) throw ConnectorException.badRequest("to must be on or after from");
        if (ChronoUnit.DAYS.between(from, to) > 90) throw ConnectorException.badRequest("date range is at most 90 days");
        int limit = intArg(args, "limit", 50, 1, 200);
        var symbols = strSet(args, "symbols", true);
        String country = optStr(args, "country");
        if (country != null && !country.matches("^[A-Za-z]{2}$")) throw ConnectorException.badRequest("country must be a 2-letter code, e.g. US");
        String impact = oneOf(args, "impact", null, List.of("Low", "Medium", "High"));
        int minImpact = impact == null ? 0 : List.of("Low", "Medium", "High").indexOf(impact);

        String path = switch (kind) {
            case "ipo" -> "/ipos-calendar";
            case "economic" -> "/economic-calendar";
            default -> "/earnings-calendar";
        };
        List<Map<String, Object>> rows = new ArrayList<>();
        int matched = 0;
        for (JsonNode r : list(get(ctx, path, "from", from.toString(), "to", to.toString()))) {
            Map<String, Object> m = new LinkedHashMap<>();
            switch (kind) {
                case "earnings" -> {
                    if (!symbols.isEmpty() && !symbols.contains(r.path("symbol").asText("").toUpperCase(Locale.ROOT))) continue;
                    m.put("symbol", text(r, "symbol"));
                    m.put("date", text(r, "date"));
                    m.put("eps_estimate", num(r, "epsEstimated"));
                    m.put("eps_actual", num(r, "epsActual"));
                    m.put("revenue_estimate", num(r, "revenueEstimated"));
                    m.put("revenue_actual", num(r, "revenueActual"));
                    m.put("last_updated", text(r, "lastUpdated"));
                }
                case "ipo" -> {
                    if (!symbols.isEmpty() && !symbols.contains(r.path("symbol").asText("").toUpperCase(Locale.ROOT))) continue;
                    m.put("symbol", text(r, "symbol"));
                    m.put("date", text(r, "date"));
                    m.put("company", text(r, "company"));
                    m.put("exchange", text(r, "exchange"));
                    m.put("status", text(r, "actions"));
                    m.put("shares", num(r, "shares"));
                    m.put("price_range", text(r, "priceRange"));
                    m.put("market_cap", num(r, "marketCap"));
                    m.put("announced_at", text(r, "daa"));
                }
                default -> {
                    if (country != null && !country.equalsIgnoreCase(r.path("country").asText(""))) continue;
                    int imp = List.of("Low", "Medium", "High").indexOf(r.path("impact").asText(""));
                    if (minImpact > 0 && imp < minImpact) continue;
                    m.put("date", text(r, "date"));
                    m.put("country", text(r, "country"));
                    m.put("event", text(r, "event"));
                    m.put("currency", text(r, "currency"));
                    m.put("actual", num(r, "actual"));
                    m.put("estimate", num(r, "estimate"));
                    m.put("previous", num(r, "previous"));
                    m.put("change", num(r, "change"));
                    m.put("unit", text(r, "unit"));
                    m.put("impact", text(r, "impact"));
                }
            }
            matched++;
            rows.add(m);
        }
        rows.sort(Comparator.comparing(m -> String.valueOf(m.get("date"))));
        if (rows.size() > limit) rows = new ArrayList<>(rows.subList(0, limit));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("kind", kind);
        out.put("from", from.toString());
        out.put("to", to.toString());
        out.put("matched", matched);
        out.put("count", rows.size());
        out.put("events", rows);
        if (kind.equals("economic")) out.put("note", "Economic release times are as FMP publishes them (UTC).");
        return out;
    }

    Object insiderTrades(ConnectorContext ctx, JsonNode args) {
        String symbol = optStr(args, "symbol") == null ? null : ticker(args, "symbol");
        String type = optStr(args, "transaction_type");
        if (type != null && !type.matches("^[A-Za-z]-?[A-Za-z ]{0,40}$")) throw ConnectorException.badRequest("transaction_type must look like P-Purchase");
        int limit = intArg(args, "limit", 25, 1, 100);
        List<Map<String, Object>> rows = new ArrayList<>();
        for (JsonNode r : list(get(ctx, "/insider-trading/search", "symbol", symbol, "transactionType", type, "page", "0", "limit", String.valueOf(limit)))) {
            Map<String, Object> m = new LinkedHashMap<>();
            String traded = text(r, "transactionDate");
            String filed = text(r, "filingDate");
            m.put("symbol", text(r, "symbol"));
            m.put("insider", text(r, "reportingName"));
            m.put("relationship", text(r, "typeOfOwner"));
            m.put("transaction_type", text(r, "transactionType"));
            m.put("acquired_disposed", text(r, "acquisitionOrDisposition"));
            m.put("shares", num(r, "securitiesTransacted"));
            m.put("price", num(r, "price"));
            m.put("shares_owned_after", num(r, "securitiesOwned"));
            m.put("ownership", text(r, "directOrIndirect"));
            m.put("security", text(r, "securityName"));
            m.put("form", text(r, "formType"));
            m.put("transaction_date", traded);
            m.put("filed_date", filed);
            m.put("filing_lag_days", lag(traded, filed));
            m.put("url", text(r, "url"));
            rows.add(m);
            if (rows.size() >= limit) break;
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("symbol", symbol);
        out.put("count", rows.size());
        out.put("trades", rows);
        out.put("note", "filed_date is when the Form 4 became public; key backtests on it, not transaction_date.");
        return out;
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private List<JsonNode> sortedDates(ConnectorContext ctx, String symbol) {
        List<JsonNode> rows = list(get(ctx, "/earning-call-transcript-dates", "symbol", symbol));
        rows.sort(Comparator.comparing((JsonNode r) -> r.path("fiscalYear").asInt() * 10 + r.path("quarter").asInt()).reversed());
        return rows;
    }

    private JsonNode get(ConnectorContext ctx, String path, String... query) {
        Object[] kv = new Object[query.length + 2];
        System.arraycopy(query, 0, kv, 0, query.length);
        kv[query.length] = "apikey";
        kv[query.length + 1] = ctx.require("api_key");
        JsonNode r;
        try {
            r = ctx.getJson(URI.create(url(base + path, kv)), Map.of());
        } catch (ConnectorException e) {
            if (e.kind() == ConnectorException.Kind.UPSTREAM && e.getMessage().contains("HTTP 402")) {
                throw new ConnectorException(ConnectorException.Kind.AUTH,
                        "FMP: this endpoint is not included in your FMP plan (HTTP 402). " + e.getMessage());
            }
            throw e;
        }
        // FMP reports some errors as {"Error Message": "..."} — sometimes with a 200.
        if (r.isObject() && r.hasNonNull("Error Message")) {
            String msg = r.path("Error Message").asText();
            String lower = msg.toLowerCase(Locale.ROOT);
            ConnectorException.Kind kind = lower.contains("limit reach") ? ConnectorException.Kind.RATE_LIMITED
                    : lower.contains("api key") || lower.contains("apikey") || lower.contains("subscription")
                    || lower.contains("exclusive") || lower.contains("restricted") || lower.contains("premium")
                    ? ConnectorException.Kind.AUTH : ConnectorException.Kind.UPSTREAM;
            throw new ConnectorException(kind, "FMP: " + (msg.length() > 200 ? msg.substring(0, 200) + "…" : msg));
        }
        return r;
    }

    static List<JsonNode> list(JsonNode n) {
        List<JsonNode> out = new ArrayList<>();
        if (n == null) return out;
        if (n.isArray()) n.forEach(out::add);
        else if (n.isObject() && !n.isEmpty()) {
            Iterator<JsonNode> it = n.elements();
            if (n.has("symbol")) out.add(n);   // single-object answers
            else while (it.hasNext()) {
                JsonNode v = it.next();
                if (v.isArray()) {
                    v.forEach(out::add);
                    break;
                }
            }
        }
        return out;
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
                return Double.parseDouble(n.asText().replace(",", "").strip());
            } catch (Exception e) {
                return null;
            }
        }
        return null;
    }

    static Long lag(String from, String to) {
        try {
            return from == null || to == null ? null : ChronoUnit.DAYS.between(LocalDate.parse(from.substring(0, 10)), LocalDate.parse(to.substring(0, 10)));
        } catch (Exception e) {
            return null;
        }
    }
}
