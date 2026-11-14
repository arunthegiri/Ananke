package com.stocktracker.connectors.openbb;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.stocktracker.connectors.ConnectorContext;
import com.stocktracker.connectors.ConnectorException;
import com.stocktracker.connectors.ConnectorSpec;
import com.stocktracker.connectors.ConnectorSpec.Field;
import com.stocktracker.connectors.ConnectorSpec.RateLimit;
import com.stocktracker.connectors.ToolArgs;
import com.stocktracker.connectors.remote.RemoteMcpConnector;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.Base64;
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
 * OpenBB Platform through the user's own {@code openbb-mcp-server} sidecar (Connectors roadmap §1).
 *
 * <p>OpenBB's tool list depends on the extensions and providers installed, so the allowlist is a
 * fixed set of core-router commands whose MCP names follow the server's rule
 * ({@code <category>_<subcategory>_<command>}, from {@code route_naming} in
 * {@code openbb_mcp_server/utils/fastapi.py}) and whose parameters are the standard-model query
 * params every provider accepts (plus {@code provider}). A tool the sidecar does not serve is
 * refused with a message, not called. The server's own write/side-effect tools
 * ({@code install_skill}, {@code run_pipeline}, the CLI dispatcher, discovery {@code call_tool}) are
 * never allowlisted. Rows are capped here ({@code max_rows}) because OpenBB has no common limit.
 *
 * <p>Auth: OpenBB's {@code server_auth} expects {@code Authorization: Bearer base64(user:pass)};
 * the token field takes either that base64 value or {@code user:pass} (encoded here).
 */
@Component
public class OpenBbConnector extends RemoteMcpConnector {

    static final String ID = "openbb";
    static final int DEFAULT_ROWS = 50;
    static final int MAX_ROWS = 500;

    private static final ConnectorSpec SPEC = new ConnectorSpec(
            ID,
            "OpenBB",
            ConnectorSpec.Category.MARKET_DATA,
            // OpenBB itself is Apache-2.0 (verified 2026-11); the data comes from whichever providers the
            // user configured in their own OpenBB with their own keys, whose terms are mostly personal-use.
            ConnectorSpec.Licence.SELF_HOSTED,
            "Your own OpenBB Platform MCP server (openbb-mcp-server): quotes and price history, company profile, "
                    + "income statements, analyst consensus, insider trading, company and world news, the economic calendar "
                    + "and CPI — from whichever providers your OpenBB has installed and keyed.",
            "Data via OpenBB Platform from the provider named in each result",
            "https://docs.openbb.co/python/extensions/interface/openbb-mcp",
            List.of(Field.text("server_url", "Server URL",
                            "Where your openbb-mcp-server listens (streamable HTTP). Start it with `openbb-mcp`; from Docker use "
                                    + "the host's address instead of 127.0.0.1.",
                            "http://127.0.0.1:8001/mcp"),
                    new Field("token", "Bearer token (optional)",
                            "Only if the server runs with server_auth: the base64 of user:pass, or user:pass itself.",
                            true, true, null)),
            List.of(RateLimit.perSecond(5), RateLimit.perMinute(120)));   // local; the providers behind it have their own limits

    public OpenBbConnector() {
        this(Clock.systemUTC());
    }

    OpenBbConnector(Clock clock) {
        super(clock);
    }

    @Override
    public ConnectorSpec spec() {
        return SPEC;
    }

    @Override
    protected URI endpoint(ConnectorContext ctx) {
        String raw = ctx.require("server_url");
        URI u;
        try {
            u = URI.create(raw);
        } catch (Exception e) {
            throw ConnectorException.badRequest("Server URL must look like http://127.0.0.1:8001/mcp");
        }
        String scheme = u.getScheme() == null ? "" : u.getScheme().toLowerCase(Locale.ROOT);
        if (!(scheme.equals("http") || scheme.equals("https")) || u.getHost() == null || u.getUserInfo() != null) {
            throw ConnectorException.badRequest("Server URL must be an http(s) URL like http://127.0.0.1:8001/mcp");
        }
        String path = u.getPath() == null ? "" : u.getPath();
        if (path.isEmpty() || path.equals("/")) u = u.resolve("/mcp");
        return u;
    }

    @Override
    protected Map<String, String> authHeaders(ConnectorContext ctx) {
        return ctx.field("token").map(t -> Map.of("Authorization", "Bearer " + bearer(t))).orElse(Map.of());
    }

    static String bearer(String token) {
        return token.contains(":") ? Base64.getEncoder().encodeToString(token.getBytes(StandardCharsets.UTF_8)) : token;
    }

    @Override
    public String test(ConnectorContext ctx) {
        int n = offeredCount(ctx);
        if (n == 0) {
            return "Connected, but the server offers none of the " + upstreamTools().size() + " supported tools — "
                    + "is tool discovery on, or are the equity/news/economy extensions missing?";
        }
        return "Connected — your OpenBB server offers " + n + " of " + upstreamTools().size() + " supported tools.";
    }

    @Override
    protected Set<String> localParams() {
        return Set.of("max_rows");
    }

    /** Caps {@code results} (newest rows for price history), drops charts, lifts the OBBject timestamp. */
    @Override
    protected JsonNode shape(UpstreamTool tool, JsonNode args, JsonNode payload) {
        if (!payload.isObject()) return payload;
        ObjectNode o = ((ObjectNode) payload).deepCopy();
        o.remove("chart");
        JsonNode ts = o.path("extra").path("metadata").path("timestamp");
        if (ts.isTextual() && !o.has("timestamp")) o.put("timestamp", ts.asText());
        JsonNode results = o.get("results");
        int max = ToolArgs.intArg(args, "max_rows", DEFAULT_ROWS, 1, MAX_ROWS);
        if (results != null && results.isArray() && results.size() > max) {
            ArrayNode kept = o.putArray("results");
            int from = tool.upstream().equals("equity_price_historical") ? results.size() - max : 0;
            for (int i = from; i < from + max; i++) kept.add(results.get(i));
            o.put("results_total", results.size());
            o.put("results_kept", max);
        }
        return o;
    }

    // ── allowlist ────────────────────────────────────────────────────────────

    static final Map<String, Object> PROVIDER = str("OpenBB provider to use, e.g. fmp, yfinance, intrinio, polygon, benzinga, fred "
            + "(must be installed and keyed in your OpenBB). Omit for its default.");
    static final Map<String, Object> SYMBOL = str("Ticker, e.g. AAPL.");
    static final Map<String, Object> START = str("Start date YYYY-MM-DD.");
    static final Map<String, Object> END = str("End date YYYY-MM-DD.");
    static final Map<String, Object> ROWS = integer("Rows to return (1-500, default 50).");

    private static Map<String, Object> schema(Object... kv) {
        Map<String, Object> p = new LinkedHashMap<>(props(kv));
        p.put("provider", PROVIDER);
        p.put("max_rows", ROWS);
        return p;
    }

    private static final List<UpstreamTool> TOOLS = List.of(
            new UpstreamTool("equity_price_quote", "Latest quote for a ticker (OpenBB equity.price.quote).",
                    object(schema("symbol", SYMBOL), List.of("symbol")), Duration.ofMinutes(1), Map.of(), 0),
            new UpstreamTool("equity_price_historical", "Daily price history for a ticker; returns the newest max_rows bars "
                    + "(OpenBB equity.price.historical).",
                    object(schema("symbol", SYMBOL, "start_date", START, "end_date", END), List.of("symbol")),
                    Duration.ofMinutes(30), Map.of(), 0),
            new UpstreamTool("equity_profile", "Company profile: name, sector, industry, description, exchange (OpenBB equity.profile).",
                    object(schema("symbol", SYMBOL), List.of("symbol")), Duration.ofHours(24), Map.of(), 0),
            new UpstreamTool("equity_fundamental_income", "Income statements, most recent first (OpenBB equity.fundamental.income).",
                    object(schema("symbol", SYMBOL, "limit", integer("Periods to return (1-20, default 5).")), List.of("symbol")),
                    Duration.ofHours(6), Map.of("limit", 5), 20),
            new UpstreamTool("equity_estimates_consensus", "Analyst price-target consensus (OpenBB equity.estimates.consensus).",
                    object(schema("symbol", SYMBOL), List.of("symbol")), Duration.ofHours(6), Map.of(), 0),
            new UpstreamTool("equity_ownership_insider_trading", "Insider transactions (OpenBB equity.ownership.insider_trading).",
                    object(schema("symbol", SYMBOL, "limit", integer("Transactions to return (1-100, default 30).")), List.of("symbol")),
                    Duration.ofMinutes(30), Map.of("limit", 30), 100),
            new UpstreamTool("news_company", "Company news with publication times (OpenBB news.company).",
                    object(schema("symbol", SYMBOL, "start_date", START, "end_date", END,
                            "limit", integer("Articles to return (1-50, default 20).")), List.of("symbol")),
                    Duration.ofMinutes(15), Map.of("limit", 20), 50),
            new UpstreamTool("news_world", "World / market news with publication times (OpenBB news.world).",
                    object(schema("start_date", START, "end_date", END, "limit", integer("Articles to return (1-50, default 20).")), List.of()),
                    Duration.ofMinutes(15), Map.of("limit", 20), 50),
            new UpstreamTool("economy_calendar", "Economic calendar: releases with actual, consensus and previous values "
                    + "(OpenBB economy.calendar).",
                    object(schema("start_date", START, "end_date", END), List.of()), Duration.ofHours(1), Map.of(), 0),
            new UpstreamTool("economy_cpi", "Consumer price index by country (OpenBB economy.cpi).",
                    object(schema("country", str("Country, e.g. united_states (default)."),
                            "transform", str("yoy (default) or another transform your provider supports, e.g. period or index."),
                            "frequency", enumStr("monthly (default), quarter or annual.", List.of("monthly", "quarter", "annual")),
                            "start_date", START, "end_date", END), List.of()),
                    Duration.ofHours(12), Map.of(), 0));

    @Override
    protected List<UpstreamTool> upstreamTools() {
        return TOOLS;
    }
}
