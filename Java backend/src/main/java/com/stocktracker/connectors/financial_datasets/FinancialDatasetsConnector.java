package com.stocktracker.connectors.financial_datasets;

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
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Iterator;
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

/**
 * Financial Datasets REST (financialdatasets.ai; Connectors roadmap §1): company facts, financial
 * statements, segmented revenue, Form 4 insider trades, 13F institutional ownership, SEC filings
 * and news. Key in {@code X-API-KEY}. Parameters and fields follow its OpenAPI spec
 * ({@code financialdatasets.ai/openapi.json}).
 *
 * <p>The REST API was chosen over its hosted MCP server: the OpenAPI spec is exact and the MCP
 * server signs in with OAuth. Every request is billed, so each tool makes exactly one request:
 * limits stay ≤ 10, the API's page size (more rows would follow {@code next_page_url}, a second
 * billed request). Point-in-time: statements carry {@code filing_date}/{@code filing_datetime},
 * insider trades and 13F rows their {@code filing_date}.
 */
@Component
public class FinancialDatasetsConnector implements Connector {

    static final String ID = "financial_datasets";
    static final String BASE_URL = "https://api.financialdatasets.ai";
    static final int PAGE = 10;

    static final Map<String, String> STATEMENTS = Map.of(
            "income", "income-statements", "balance", "balance-sheets", "cash_flow", "cash-flow-statements");
    static final Map<String, String> STATEMENT_KEYS = Map.of(
            "income", "income_statements", "balance", "balance_sheets", "cash_flow", "cash_flow_statements");

    private static final ConnectorSpec SPEC = new ConnectorSpec(
            ID,
            "Financial Datasets",
            ConnectorSpec.Category.FILINGS,
            ConnectorSpec.Licence.BYO_PERSONAL,
            "US company fundamentals from SEC filings: company facts, income/balance/cash-flow statements with filing "
                    + "dates, revenue by segment and product, Form 4 insider trades, 13F institutional holders, filing lists "
                    + "and company news.",
            "Source: Financial Datasets (financialdatasets.ai)",
            "https://docs.financialdatasets.ai/",
            List.of(Field.secret("api_key", "API key",
                    "From financialdatasets.ai → Dashboard. Every request is billed against your credits; Credits and "
                            + "Personal plans carry a personal licence.")),
            // No published per-minute limit; requests cost money, so cap spend.
            List.of(RateLimit.perMinute(30), RateLimit.perDay(500)));

    private final String base;

    public FinancialDatasetsConnector() {
        this(BASE_URL);
    }

    /** Test seam: point at a fake API. */
    FinancialDatasetsConnector(String base) {
        this.base = base;
    }

    @Override
    public ConnectorSpec spec() {
        return SPEC;
    }

    @Override
    public String test(ConnectorContext ctx) {
        JsonNode f = get(ctx, "/company/facts", "ticker", "AAPL").path("company_facts");
        return "Connected — Financial Datasets answered (AAPL = " + f.path("name").asText("?") + ", CIK " + f.path("cik").asText("?") + ").";
    }

    @Override
    public List<ConnectorTool> tools() {
        Map<String, Object> ticker = str("Ticker, e.g. AAPL");
        Map<String, Object> limit = integer("Rows to return (1-10, default 4)");
        return List.of(
                new ConnectorTool(ID + "_company_facts",
                        "Company facts: name, CIK, sector, industry, exchange, location, SIC code, active status.",
                        object(props("ticker", ticker), List.of("ticker")), Duration.ofHours(24), this::companyFacts,
                        a -> "Looking up " + a.path("ticker").asText("company") + "…"),
                new ConnectorTool(ID + "_financial_statements",
                        "Income statement, balance sheet or cash-flow statement, newest period first, each with its report "
                                + "period, fiscal period, form and the filing date/time it became public.",
                        object(props(
                                "ticker", ticker,
                                "statement", enumStr("income, balance or cash_flow", List.of("income", "balance", "cash_flow")),
                                "period", enumStr("annual, quarterly or ttm", List.of("annual", "quarterly", "ttm")),
                                "limit", limit), List.of("ticker", "statement", "period")),
                        Duration.ofHours(6), this::statements,
                        a -> "Reading " + a.path("ticker").asText("") + " " + a.path("statement").asText("financials") + "…"),
                new ConnectorTool(ID + "_segmented_revenue",
                        "Revenue (and segment operating income) broken down by product line and business segment, as "
                                + "reported in 10-K/10-Q filings, newest period first.",
                        object(props(
                                "ticker", ticker,
                                "period", enumStr("annual or quarterly", List.of("annual", "quarterly")),
                                "limit", limit), List.of("ticker", "period")),
                        Duration.ofHours(12), this::segments, a -> "Reading " + a.path("ticker").asText("") + " segments…"),
                new ConnectorTool(ID + "_insider_trades",
                        "Form 4 insider trades: insider name and title, director flag, transaction date and type, shares, "
                                + "price, value, holdings before/after and the filing date.",
                        object(props(
                                "ticker", ticker,
                                "filed_since", str("Only filings on/after YYYY-MM-DD"),
                                "limit", integer("Trades to return (1-10, default 10)")), List.of("ticker")),
                        Duration.ofMinutes(30), this::insiders, a -> "Reading insider trades for " + a.path("ticker").asText("") + "…"),
                new ConnectorTool(ID + "_institutional_ownership",
                        "Institutional holders of a stock from their latest 13F: filer, shares, value, report period and filing date.",
                        object(props("ticker", ticker, "limit", integer("Holders to return (1-10, default 10)")), List.of("ticker")),
                        Duration.ofHours(12), this::institutions, a -> "Reading 13F holders of " + a.path("ticker").asText("") + "…"),
                new ConnectorTool(ID + "_filings",
                        "SEC filings for a company, newest first: form type, report date, filing date, accession number and link.",
                        object(props(
                                "ticker", ticker,
                                "filing_type", str("Only this form, e.g. 10-K, 10-Q or 8-K"),
                                "limit", integer("Filings to return (1-10, default 10)")), List.of("ticker")),
                        Duration.ofMinutes(30), this::filings, a -> "Reading " + a.path("ticker").asText("") + " filings…"),
                new ConnectorTool(ID + "_news",
                        "Recent news for a company (or market news without a ticker): title, source, date and link.",
                        object(props("ticker", str("Ticker; omit for market news"), "limit", integer("Articles (1-10, default 10)")), List.of()),
                        Duration.ofMinutes(15), this::news, a -> "Reading news…"));
    }

    // ── tools ────────────────────────────────────────────────────────────────

    Object companyFacts(ConnectorContext ctx, JsonNode args) {
        JsonNode f = get(ctx, "/company/facts", "ticker", ToolArgs.ticker(args, "ticker")).path("company_facts");
        return copy(f, Set.of());
    }

    Object statements(ConnectorContext ctx, JsonNode args) {
        String ticker = ToolArgs.ticker(args, "ticker");
        String statement = ToolArgs.oneOf(args, "statement", null, List.of("income", "balance", "cash_flow"));
        String period = ToolArgs.oneOf(args, "period", null, List.of("annual", "quarterly", "ttm"));
        if (statement == null || period == null) throw ConnectorException.badRequest("statement and period are required");
        int limit = ToolArgs.intArg(args, "limit", 4, 1, PAGE);
        JsonNode r = get(ctx, "/financials/" + STATEMENTS.get(statement), "ticker", ticker, "period", period, "limit", limit);
        return rows(r.path(STATEMENT_KEYS.get(statement)), limit, Set.of(), Map.of("ticker", ticker, "statement", statement, "period", period));
    }

    Object segments(ConnectorContext ctx, JsonNode args) {
        String ticker = ToolArgs.ticker(args, "ticker");
        String period = ToolArgs.oneOf(args, "period", null, List.of("annual", "quarterly"));
        if (period == null) throw ConnectorException.badRequest("period is required");
        int limit = ToolArgs.intArg(args, "limit", 4, 1, PAGE);
        JsonNode r = get(ctx, "/financials/income-statements/segments", "ticker", ticker, "period", period, "limit", limit);
        return rows(r.path("segmented_financials"), limit, Set.of(), Map.of("ticker", ticker, "period", period));
    }

    Object insiders(ConnectorContext ctx, JsonNode args) {
        String ticker = ToolArgs.ticker(args, "ticker");
        LocalDate since = ToolArgs.date(args, "filed_since");
        int limit = ToolArgs.intArg(args, "limit", PAGE, 1, PAGE);
        JsonNode r = get(ctx, "/insider-trades", "ticker", ticker, "limit", limit,
                "filing_date_gte", since == null ? null : since.toString());
        return rows(r.path("insider_trades"), limit, Set.of("issuer"), Map.of("ticker", ticker));
    }

    Object institutions(ConnectorContext ctx, JsonNode args) {
        String ticker = ToolArgs.ticker(args, "ticker");
        int limit = ToolArgs.intArg(args, "limit", PAGE, 1, PAGE);
        JsonNode r = get(ctx, "/institutional-holdings", "ticker", ticker, "limit", limit);
        return rows(r.path("institutional_holdings"), limit, Set.of("subsidiaries", "name_of_issuer", "title_of_class"), Map.of("ticker", ticker));
    }

    Object filings(ConnectorContext ctx, JsonNode args) {
        String ticker = ToolArgs.ticker(args, "ticker");
        String type = ToolArgs.optStr(args, "filing_type");
        if (type != null && !type.matches("^[A-Za-z0-9/ -]{1,12}$")) throw ConnectorException.badRequest("filing_type must look like 10-K");
        int limit = ToolArgs.intArg(args, "limit", PAGE, 1, PAGE);
        JsonNode r = get(ctx, "/filings", "ticker", ticker, "filing_type", type == null ? null : type.toUpperCase(Locale.ROOT), "limit", limit);
        return rows(r.path("filings"), limit, Set.of(), Map.of("ticker", ticker));
    }

    Object news(ConnectorContext ctx, JsonNode args) {
        String ticker = ToolArgs.optStr(args, "ticker") == null ? null : ToolArgs.ticker(args, "ticker");
        int limit = ToolArgs.intArg(args, "limit", PAGE, 1, PAGE);
        JsonNode r = get(ctx, "/news", "ticker", ticker, "limit", limit);
        Map<String, Object> ctxFields = new LinkedHashMap<>();
        if (ticker != null) ctxFields.put("ticker", ticker);
        return rows(r.path("news"), limit, Set.of(), ctxFields);
    }

    // ── transport + shaping ──────────────────────────────────────────────────

    JsonNode get(ConnectorContext ctx, String path, Object... query) {
        return ctx.getJson(URI.create(ToolArgs.url(base + path, query)), Map.of("X-API-KEY", ctx.require("api_key")));
    }

    static Map<String, Object> rows(JsonNode array, int limit, Set<String> drop, Map<String, Object> head) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (JsonNode n : array) {
            if (out.size() >= limit) break;
            out.add(copy(n, drop));
        }
        Map<String, Object> res = new LinkedHashMap<>(head);
        res.put("count", out.size());
        res.put("rows", out);
        return res;
    }

    /** JSON object → ordered map, minus {@code drop}; nested values kept as plain Java values. */
    static Map<String, Object> copy(JsonNode n, Set<String> drop) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (Iterator<Map.Entry<String, JsonNode>> it = n.fields(); it.hasNext(); ) {
            Map.Entry<String, JsonNode> e = it.next();
            if (drop.contains(e.getKey())) continue;
            m.put(e.getKey(), plain(e.getValue()));
        }
        return m;
    }

    private static Object plain(JsonNode v) {
        if (v == null || v.isNull()) return null;
        if (v.isTextual()) return v.asText();
        if (v.isBoolean()) return v.asBoolean();
        if (v.isIntegralNumber()) return v.asLong();
        if (v.isNumber()) return v.asDouble();
        if (v.isArray()) {
            List<Object> l = new ArrayList<>();
            v.forEach(x -> l.add(plain(x)));
            return l;
        }
        return copy(v, Set.of());
    }
}
