package com.stocktracker.connectors.alpha_vantage;

import com.fasterxml.jackson.databind.JsonNode;
import com.stocktracker.connectors.Connector;
import com.stocktracker.connectors.ConnectorContext;
import com.stocktracker.connectors.ConnectorException;
import com.stocktracker.connectors.ConnectorException.Kind;
import com.stocktracker.connectors.ConnectorSpec;
import com.stocktracker.connectors.ConnectorSpec.Field;
import com.stocktracker.connectors.ConnectorSpec.RateLimit;
import com.stocktracker.connectors.ConnectorTool;
import com.stocktracker.connectors.ToolArgs;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static com.stocktracker.connectors.ToolArgs.enumStr;
import static com.stocktracker.connectors.ToolArgs.integer;
import static com.stocktracker.connectors.ToolArgs.object;
import static com.stocktracker.connectors.ToolArgs.props;
import static com.stocktracker.connectors.ToolArgs.str;
import static com.stocktracker.connectors.ToolArgs.strArray;

/**
 * Alpha Vantage REST (Connectors roadmap §1): news with sentiment, earnings-call transcripts,
 * insider transactions, earnings history and estimates, company overview and US economic
 * indicators. One query endpoint, {@code /query?function=…&apikey=…}.
 *
 * <p>Alpha Vantage answers HTTP 200 for every failure, with an {@code Error Message},
 * {@code Information} or {@code Note} body; {@link #check} maps those to AUTH / RATE_LIMITED /
 * BAD_REQUEST. Note that it does not reject unknown keys outright (they are served like free
 * keys), so a link test can only catch a missing key or a demo key.
 */
@Component
public class AlphaVantageConnector implements Connector {

    static final String ID = "alpha_vantage";
    static final String BASE_URL = "https://www.alphavantage.co";
    static final DateTimeFormatter AV_TIME = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss");
    static final DateTimeFormatter AV_FROM = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmm");

    static final List<String> TOPICS = List.of("blockchain", "earnings", "ipo", "mergers_and_acquisitions", "financial_markets",
            "economy_fiscal", "economy_monetary", "economy_macro", "energy_transportation", "finance", "life_sciences",
            "manufacturing", "real_estate", "retail_wholesale", "technology");

    /** indicator → allowed intervals (first = Alpha Vantage's default); empty = no interval parameter. */
    static final Map<String, List<String>> INDICATORS = indicators();

    private static Map<String, List<String>> indicators() {
        Map<String, List<String>> m = new LinkedHashMap<>();
        m.put("REAL_GDP", List.of("annual", "quarterly"));
        m.put("REAL_GDP_PER_CAPITA", List.of());
        m.put("TREASURY_YIELD", List.of("monthly", "weekly", "daily"));
        m.put("FEDERAL_FUNDS_RATE", List.of("monthly", "weekly", "daily"));
        m.put("CPI", List.of("monthly", "semiannual"));
        m.put("INFLATION", List.of());
        m.put("RETAIL_SALES", List.of());
        m.put("DURABLES", List.of());
        m.put("UNEMPLOYMENT", List.of());
        m.put("NONFARM_PAYROLL", List.of());
        return m;
    }

    private static final ConnectorSpec SPEC = new ConnectorSpec(
            ID,
            "Alpha Vantage",
            ConnectorSpec.Category.NEWS,
            ConnectorSpec.Licence.BYO_PERSONAL,
            "News with per-ticker sentiment, earnings-call transcripts with turn sentiment, insider transactions, "
                    + "earnings history (with report dates) and analyst estimates, company overview and US economic "
                    + "indicators (GDP, CPI, inflation, Treasury yields, Fed funds, payrolls, unemployment).",
            "Source: Alpha Vantage Inc.",
            "https://www.alphavantage.co/documentation/",
            List.of(Field.secret("api_key", "API key",
                    "Free at alphavantage.co/support/#api-key (25 requests a day, 1 a second). Premium keys lift the daily cap.")),
            List.of(RateLimit.perSecond(1), RateLimit.perDay(25)));   // free tier, as stated in its own rate-limit message

    private final Clock clock;
    private final String base;

    public AlphaVantageConnector() {
        this(Clock.systemUTC(), BASE_URL);
    }

    /** Test seam: point at a fake Alpha Vantage. */
    AlphaVantageConnector(Clock clock, String base) {
        this.clock = clock;
        this.base  = base;
    }

    @Override
    public ConnectorSpec spec() {
        return SPEC;
    }

    @Override
    public String test(ConnectorContext ctx) {
        JsonNode r = query(ctx, "MARKET_STATUS");
        int n = r.path("markets").size();
        if (n == 0) throw ConnectorException.upstream("Alpha Vantage answered without market data.");
        return "Connected — Alpha Vantage answered (" + n + " markets). Note: it serves unknown keys like free keys, "
                + "so a mistyped key only shows up as tighter limits.";
    }

    @Override
    public List<ConnectorTool> tools() {
        return List.of(
                new ConnectorTool(ID + "_news_sentiment",
                        "Market news with sentiment: each article's publication time, source, summary, overall sentiment and "
                                + "per-ticker relevance/sentiment (-1 bearish … +1 bullish). Filter by tickers (all must be "
                                + "mentioned; crypto as CRYPTO:BTC, FX as FOREX:USD), topics and time range.",
                        object(props(
                                "tickers", strArray("Tickers that must all be mentioned, e.g. [\"AAPL\"]"),
                                "topics", Map.of("type", "array", "items", Map.of("type", "string", "enum", TOPICS),
                                        "description", "Topics that must all be covered"),
                                "time_from", str("Earliest publication, YYYY-MM-DD or YYYY-MM-DDTHH:MM"),
                                "time_to", str("Latest publication, YYYY-MM-DD or YYYY-MM-DDTHH:MM"),
                                "sort", enumStr("LATEST (default), EARLIEST or RELEVANCE", List.of("LATEST", "EARLIEST", "RELEVANCE")),
                                "limit", integer("Articles to return (1-50, default 20)")), List.of()),
                        Duration.ofMinutes(30), this::news, a -> "Reading Alpha Vantage news…"),
                new ConnectorTool(ID + "_earnings_transcript",
                        "Earnings-call transcript for one fiscal quarter (since 2010Q1): speaker, title, sentiment and text "
                                + "of each turn, cut to max_chars.",
                        object(props(
                                "symbol", str("Ticker, e.g. IBM"),
                                "quarter", str("Fiscal quarter as YYYYQn, e.g. 2024Q1"),
                                "max_chars", integer("Max transcript characters (2000-40000, default 15000)")), List.of("symbol", "quarter")),
                        Duration.ofDays(7), this::transcript, a -> "Reading the " + a.path("symbol").asText("") + " earnings call…"),
                new ConnectorTool(ID + "_insider_transactions",
                        "Insider transactions (Forms 3/4/5) newest first: executive, title, security, acquisition (A) or "
                                + "disposal (D), shares and price. Gives the transaction date only, not when it was filed.",
                        object(props(
                                "symbol", str("Ticker"),
                                "from", str("Only transactions on/after this date, YYYY-MM-DD (default: one year ago)"),
                                "limit", integer("Transactions to return (1-100, default 30)")), List.of("symbol")),
                        Duration.ofHours(6), this::insiders, a -> "Reading insider transactions for " + a.path("symbol").asText("") + "…"),
                new ConnectorTool(ID + "_earnings",
                        "Quarterly earnings history: fiscal period, report date and time (pre/post-market), reported vs "
                                + "estimated EPS and surprise. The report date is when it became public.",
                        object(props("symbol", str("Ticker"), "limit", integer("Quarters to return (1-40, default 8)")), List.of("symbol")),
                        Duration.ofHours(12), this::earnings, a -> "Reading " + a.path("symbol").asText("") + " earnings…"),
                new ConnectorTool(ID + "_earnings_estimates",
                        "Analyst EPS and revenue estimates by fiscal quarter/year with analyst counts and 7/30/60/90-day "
                                + "revisions (as of today; no history of past snapshots).",
                        object(props("symbol", str("Ticker"), "limit", integer("Periods to return (1-20, default 8)")), List.of("symbol")),
                        Duration.ofHours(12), this::estimates, a -> "Reading " + a.path("symbol").asText("") + " estimates…"),
                new ConnectorTool(ID + "_company_overview",
                        "Company overview and key metrics: sector, industry, market cap, P/E, PEG, margins, ROE, EPS, "
                                + "dividend yield, beta, analyst target and ratings, 52-week range, latest fiscal quarter.",
                        object(props("symbol", str("Ticker")), List.of("symbol")),
                        Duration.ofHours(24), this::overview, a -> "Reading the " + a.path("symbol").asText("") + " overview…"),
                new ConnectorTool(ID + "_economic_indicator",
                        "US economic indicator time series, newest first: REAL_GDP, REAL_GDP_PER_CAPITA, TREASURY_YIELD, "
                                + "FEDERAL_FUNDS_RATE, CPI, INFLATION, RETAIL_SALES, DURABLES, UNEMPLOYMENT, NONFARM_PAYROLL. "
                                + "Dates are observation periods, not release dates.",
                        object(props(
                                "indicator", enumStr("Indicator", List.copyOf(INDICATORS.keySet())),
                                "interval", str("REAL_GDP: annual|quarterly; TREASURY_YIELD, FEDERAL_FUNDS_RATE: daily|weekly|monthly; "
                                        + "CPI: monthly|semiannual. Others have one interval."),
                                "maturity", enumStr("TREASURY_YIELD only (default 10year)",
                                        List.of("3month", "2year", "5year", "7year", "10year", "30year")),
                                "limit", integer("Observations to return (1-120, default 24)")), List.of("indicator")),
                        Duration.ofHours(12), this::indicator, a -> "Reading " + a.path("indicator").asText("an indicator") + "…"));
    }

    // ── tools ────────────────────────────────────────────────────────────────

    Object news(ConnectorContext ctx, JsonNode args) {
        Set<String> tickers = ToolArgs.strSet(args, "tickers", true);
        Set<String> topics = ToolArgs.strSet(args, "topics", false);
        for (String t : topics) if (!TOPICS.contains(t)) throw ConnectorException.badRequest("Unknown topic " + t + "; use one of " + TOPICS);
        int limit = ToolArgs.intArg(args, "limit", 20, 1, 50);
        JsonNode r = query(ctx, "NEWS_SENTIMENT",
                "tickers", tickers.isEmpty() ? null : String.join(",", tickers),
                "topics", topics.isEmpty() ? null : String.join(",", topics),
                "time_from", avTime(ToolArgs.optStr(args, "time_from"), "time_from"),
                "time_to", avTime(ToolArgs.optStr(args, "time_to"), "time_to"),
                "sort", ToolArgs.oneOf(args, "sort", null, List.of("LATEST", "EARLIEST", "RELEVANCE")),
                "limit", limit);
        List<Map<String, Object>> out = new ArrayList<>();
        for (JsonNode a : r.path("feed")) {
            if (out.size() >= limit) break;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("published", isoTime(a.path("time_published").asText(null)));
            m.put("title", a.path("title").asText(null));
            m.put("source", a.path("source").asText(null));
            m.put("url", a.path("url").asText(null));
            m.put("summary", trim(a.path("summary").asText(""), 500));
            m.put("sentiment", a.path("overall_sentiment_score").isNumber() ? a.path("overall_sentiment_score").asDouble() : null);
            m.put("sentiment_label", a.path("overall_sentiment_label").asText(null));
            m.put("topics", a.path("topics").findValuesAsText("topic"));
            List<Map<String, Object>> ts = new ArrayList<>();
            for (JsonNode t : a.path("ticker_sentiment")) {
                if (!tickers.isEmpty() && !tickers.contains(t.path("ticker").asText())) continue;
                if (ts.size() >= 5) break;
                Map<String, Object> x = new LinkedHashMap<>();
                x.put("ticker", t.path("ticker").asText());
                x.put("relevance", num(t.path("relevance_score")));
                x.put("sentiment", num(t.path("ticker_sentiment_score")));
                x.put("label", t.path("ticker_sentiment_label").asText(null));
                ts.add(x);
            }
            m.put("tickers", ts);
            out.add(m);
        }
        Map<String, Object> res = new LinkedHashMap<>();
        res.put("count", out.size());
        res.put("time_note", "published is Alpha Vantage's time_published (YYYYMMDDTHHMMSS) in ISO form; no zone is given upstream.");
        res.put("articles", out);
        return res;
    }

    Object transcript(ConnectorContext ctx, JsonNode args) {
        String symbol = ToolArgs.ticker(args, "symbol");
        String quarter = ToolArgs.requireStr(args, "quarter", 6).toUpperCase(Locale.ROOT);
        if (!quarter.matches("^(20[1-9][0-9])Q[1-4]$")) throw ConnectorException.badRequest("quarter must look like 2024Q1 (2010Q1 or later)");
        int maxChars = ToolArgs.intArg(args, "max_chars", 15_000, 2_000, 40_000);
        JsonNode r = query(ctx, "EARNINGS_CALL_TRANSCRIPT", "symbol", symbol, "quarter", quarter);
        List<Map<String, Object>> turns = new ArrayList<>();
        int used = 0;
        boolean truncated = false;
        int total = r.path("transcript").size();
        for (JsonNode t : r.path("transcript")) {
            String content = t.path("content").asText("");
            if (used >= maxChars) {
                truncated = true;
                break;
            }
            if (used + content.length() > maxChars) {
                content = content.substring(0, maxChars - used) + "…";
                truncated = true;
            }
            used += content.length();
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("speaker", t.path("speaker").asText(null));
            m.put("title", t.path("title").asText(null));
            m.put("sentiment", num(t.path("sentiment")));
            m.put("text", content);
            turns.add(m);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("symbol", r.path("symbol").asText(symbol));
        out.put("quarter", r.path("quarter").asText(quarter));
        out.put("turns_total", total);
        out.put("turns_returned", turns.size());
        out.put("truncated", truncated);
        out.put("turns", turns);
        return out;
    }

    Object insiders(ConnectorContext ctx, JsonNode args) {
        String symbol = ToolArgs.ticker(args, "symbol");
        LocalDate from = ToolArgs.date(args, "from");
        if (from == null) from = LocalDate.now(clock).minusYears(1);
        int limit = ToolArgs.intArg(args, "limit", 30, 1, 100);
        JsonNode r = query(ctx, "INSIDER_TRANSACTIONS", "symbol", symbol, "from", from.toString());
        List<JsonNode> rows = new ArrayList<>();
        r.path("data").forEach(rows::add);
        rows.sort(Comparator.comparing((JsonNode n) -> n.path("transaction_date").asText("")).reversed());
        List<Map<String, Object>> out = new ArrayList<>();
        for (JsonNode t : rows) {
            if (out.size() >= limit) break;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("transaction_date", t.path("transaction_date").asText(null));
            m.put("executive", t.path("executive").asText(null));
            m.put("title", t.path("executive_title").asText(null));
            m.put("security", t.path("security_type").asText(null));
            m.put("acquired_or_disposed", t.path("acquisition_or_disposal").asText(null));
            m.put("shares", num(t.path("shares")));
            m.put("price", num(t.path("share_price")));
            out.add(m);
        }
        Map<String, Object> res = new LinkedHashMap<>();
        res.put("symbol", symbol);
        res.put("from", from.toString());
        res.put("matched", rows.size());
        res.put("transactions", out);
        return res;
    }

    Object earnings(ConnectorContext ctx, JsonNode args) {
        String symbol = ToolArgs.ticker(args, "symbol");
        int limit = ToolArgs.intArg(args, "limit", 8, 1, 40);
        JsonNode r = query(ctx, "EARNINGS", "symbol", symbol);
        List<Map<String, Object>> out = new ArrayList<>();
        for (JsonNode q : r.path("quarterlyEarnings")) {
            if (out.size() >= limit) break;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("fiscal_date_ending", q.path("fiscalDateEnding").asText(null));
            m.put("reported_date", q.path("reportedDate").asText(null));
            m.put("report_time", q.path("reportTime").asText(null));
            m.put("reported_eps", num(q.path("reportedEPS")));
            m.put("estimated_eps", num(q.path("estimatedEPS")));
            m.put("surprise", num(q.path("surprise")));
            m.put("surprise_pct", num(q.path("surprisePercentage")));
            out.add(m);
        }
        return Map.of("symbol", r.path("symbol").asText(symbol), "quarters", out);
    }

    Object estimates(ConnectorContext ctx, JsonNode args) {
        String symbol = ToolArgs.ticker(args, "symbol");
        int limit = ToolArgs.intArg(args, "limit", 8, 1, 20);
        JsonNode r = query(ctx, "EARNINGS_ESTIMATES", "symbol", symbol);
        List<Map<String, Object>> out = new ArrayList<>();
        for (JsonNode e : r.path("estimates")) {
            if (out.size() >= limit) break;
            Map<String, Object> m = new LinkedHashMap<>();
            e.fields().forEachRemaining(f -> m.put(f.getKey(), f.getValue().isTextual() && !f.getKey().equals("date")
                    && !f.getKey().equals("horizon") ? num(f.getValue()) : f.getValue().isNull() ? null : f.getValue().asText()));
            out.add(m);
        }
        Map<String, Object> res = new LinkedHashMap<>();
        res.put("symbol", r.path("symbol").asText(symbol));
        res.put("as_of", LocalDate.now(clock).toString());
        res.put("estimates", out);
        return res;
    }

    Object overview(ConnectorContext ctx, JsonNode args) {
        String symbol = ToolArgs.ticker(args, "symbol");
        JsonNode r = query(ctx, "OVERVIEW", "symbol", symbol);
        if (!r.has("Symbol")) throw ConnectorException.badRequest("Alpha Vantage has no overview for " + symbol + ".");
        Map<String, Object> out = new LinkedHashMap<>();
        r.fields().forEachRemaining(f -> {
            String v = f.getValue().asText(null);
            if (v == null || v.equals("None") || v.equals("-")) v = null;
            out.put(f.getKey(), f.getKey().equals("Description") && v != null ? trim(v, 600) : v);
        });
        return out;
    }

    Object indicator(ConnectorContext ctx, JsonNode args) {
        String ind = ToolArgs.oneOf(args, "indicator", null, List.copyOf(INDICATORS.keySet()));
        if (ind == null) throw ConnectorException.badRequest("indicator is required");
        List<String> intervals = INDICATORS.get(ind);
        String interval = ToolArgs.optStr(args, "interval");
        if (interval != null) {
            interval = interval.toLowerCase(Locale.ROOT);
            if (!intervals.contains(interval)) {
                throw ConnectorException.badRequest(ind + (intervals.isEmpty() ? " takes no interval" : " interval must be one of " + intervals));
            }
        }
        String maturity = null;
        if (ind.equals("TREASURY_YIELD")) {
            maturity = ToolArgs.oneOf(args, "maturity", null, List.of("3month", "2year", "5year", "7year", "10year", "30year"));
        }
        int limit = ToolArgs.intArg(args, "limit", 24, 1, 120);
        JsonNode r = query(ctx, ind, "interval", interval, "maturity", maturity);
        List<Map<String, Object>> data = new ArrayList<>();
        for (JsonNode d : r.path("data")) {
            if (data.size() >= limit) break;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("date", d.path("date").asText(null));
            m.put("value", num(d.path("value")));
            data.add(m);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("indicator", ind);
        out.put("name", r.path("name").asText(null));
        out.put("interval", r.path("interval").asText(null));
        out.put("unit", r.path("unit").asText(null));
        out.put("data", data);
        return out;
    }

    // ── transport ────────────────────────────────────────────────────────────

    /** One {@code /query} call; the key is sent as a query parameter (the only way AV accepts it). */
    JsonNode query(ConnectorContext ctx, String function, Object... kv) {
        Object[] all = new Object[kv.length + 4];
        all[0] = "function";
        all[1] = function;
        System.arraycopy(kv, 0, all, 2, kv.length);
        all[kv.length + 2] = "apikey";
        all[kv.length + 3] = ctx.require("api_key");
        return check(ctx.getJson(URI.create(ToolArgs.url(base + "/query", all))));
    }

    /** AV reports errors in a 200 body; turn them into kinds. Never echoes the key. */
    static JsonNode check(JsonNode r) {
        String err = r.path("Error Message").asText(null);
        if (err != null) {
            if (err.toLowerCase(Locale.ROOT).contains("apikey")) {
                throw new ConnectorException(Kind.AUTH, "Alpha Vantage rejected the request: the API key is invalid or missing.");
            }
            throw ConnectorException.badRequest("Alpha Vantage: " + trim(err, 200));
        }
        String info = r.has("Information") ? r.path("Information").asText("") : r.has("Note") ? r.path("Note").asText("") : null;
        if (info != null && r.size() <= 2) {
            String l = info.toLowerCase(Locale.ROOT);
            if (l.contains("demo")) throw new ConnectorException(Kind.AUTH, "Alpha Vantage: the demo key only works for documented examples. Use your own key.");
            // The rate-limit text also mentions "premium endpoints", so test for it first.
            if (l.contains("rate limit") || l.contains("requests per day") || l.contains("per minute") || l.contains("sparingly")
                    || l.contains("per second")) {
                throw new ConnectorException(Kind.RATE_LIMITED, "Alpha Vantage rate limit reached: " + trim(info, 220));
            }
            if (l.contains("is a premium endpoint")) {
                throw new ConnectorException(Kind.AUTH, "Alpha Vantage: this endpoint needs a premium plan (or the key is not recognised).");
            }
            throw ConnectorException.upstream("Alpha Vantage: " + trim(info, 220));
        }
        return r;
    }

    /** {@code YYYY-MM-DD[THH:MM]} → AV's {@code YYYYMMDDTHHMM}. */
    static String avTime(String s, String name) {
        if (s == null) return null;
        try {
            if (s.length() == 10) return LocalDate.parse(s).atStartOfDay().format(AV_FROM);
            return LocalDateTime.parse(s.length() == 16 ? s + ":00" : s).format(AV_FROM);
        } catch (Exception e) {
            throw ConnectorException.badRequest(name + " must be YYYY-MM-DD or YYYY-MM-DDTHH:MM");
        }
    }

    static String isoTime(String av) {
        if (av == null) return null;
        try {
            return LocalDateTime.parse(av, AV_TIME).toString();
        } catch (Exception e) {
            return av;
        }
    }

    static Double num(JsonNode n) {
        if (n == null || n.isNull() || n.isMissingNode()) return null;
        if (n.isNumber()) return n.asDouble();
        String s = n.asText("").strip();
        if (s.isEmpty() || s.equals("None") || s.equals("-")) return null;
        try {
            return Double.parseDouble(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    static String trim(String s, int max) {
        if (s == null) return null;
        return s.length() > max ? s.substring(0, max) + "…" : s;
    }
}
