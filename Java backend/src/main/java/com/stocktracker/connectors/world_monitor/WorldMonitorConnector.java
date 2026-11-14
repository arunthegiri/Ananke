package com.stocktracker.connectors.world_monitor;

import com.fasterxml.jackson.databind.JsonNode;
import com.stocktracker.connectors.ConnectorContext;
import com.stocktracker.connectors.ConnectorSpec;
import com.stocktracker.connectors.ConnectorSpec.Field;
import com.stocktracker.connectors.ConnectorSpec.RateLimit;
import com.stocktracker.connectors.remote.RemoteMcpConnector;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.stocktracker.connectors.ToolArgs.bool;
import static com.stocktracker.connectors.ToolArgs.enumStr;
import static com.stocktracker.connectors.ToolArgs.integer;
import static com.stocktracker.connectors.ToolArgs.number;
import static com.stocktracker.connectors.ToolArgs.object;
import static com.stocktracker.connectors.ToolArgs.props;
import static com.stocktracker.connectors.ToolArgs.str;
import static com.stocktracker.connectors.ToolArgs.strArray;

/**
 * World Monitor ("God's Eye", worldmonitor.app) over its hosted MCP server (Connectors roadmap §1).
 * Called as an API only — the project is AGPL-3.0 and none of its code is used here.
 *
 * <p>The allowlist is the finance-relevant read-only tools: market snapshot, rates/calendars,
 * country macro, debt, the Composite Instability Index, conflict events, threat news and the
 * hourly world brief. Paid-panel controls ({@code panel_request}, {@code refresh},
 * {@code request_id}), {@code jmespath} and spoken mode are never forwarded. Verified against the
 * live {@code tools/list} (fixture {@code connectors/world_monitor_tools_list.json}).
 *
 * <p>Auth: a {@code wm_…} key in {@code X-WorldMonitor-Key}. World Monitor issues keys on API
 * Starter and higher (commercial internal-use licence); Pro/Pro Business reach MCP through OAuth,
 * which Ananke does not do yet. Every cache payload carries {@code cached_at} and {@code stale},
 * lifted to {@code as_of}/{@code stale} in each result.
 */
@Component
public class WorldMonitorConnector extends RemoteMcpConnector {

    static final String ID = "world_monitor";
    static final String MCP_URL = "https://worldmonitor.app/mcp";
    static final String KEY_HEADER = "X-WorldMonitor-Key";
    static final String ALLOWANCE_TOOL = "get_mcp_allowance";

    private static final ConnectorSpec SPEC = new ConnectorSpec(
            ID,
            "World Monitor",
            ConnectorSpec.Category.GEOPOLITICS,
            ConnectorSpec.Licence.BYO_COMMERCIAL,
            "Global situational awareness from World Monitor: market and commodity snapshot, rates and economic "
                    + "calendars, IMF/Eurostat country macro, national debt, the Composite Instability Index, armed-conflict "
                    + "and unrest events, AI-classified threat news and the hourly world brief. Each result carries the "
                    + "source's cached_at time and a stale flag.",
            "Source: World Monitor (worldmonitor.app)",
            "https://www.worldmonitor.app/docs/mcp-overview",
            List.of(Field.secret("api_key", "API key (wm_…)",
                    "Create one at worldmonitor.app → Settings → API Keys (API Starter plan or higher; its licence covers "
                            + "internal use in your organisation). An MCP call counts as 1–3 requests of the daily allowance.")),
            // API Starter: 1,000 units/day, 60/min burst; a call costs 1–3 HTTP requests here (initialize,
            // notification, list every 6 h, call). Keep far below both.
            List.of(RateLimit.perMinute(20), RateLimit.perDay(300)));

    private final URI endpoint;

    public WorldMonitorConnector() {
        this(Clock.systemUTC(), MCP_URL);
    }

    /** Test seam: point at a fake MCP server. */
    WorldMonitorConnector(Clock clock, String endpoint) {
        super(clock);
        this.endpoint = URI.create(endpoint);
    }

    @Override
    public ConnectorSpec spec() {
        return SPEC;
    }

    @Override
    protected URI endpoint(ConnectorContext ctx) {
        return endpoint;
    }

    @Override
    protected Map<String, String> authHeaders(ConnectorContext ctx) {
        return Map.of(KEY_HEADER, ctx.require("api_key"));
    }

    @Override
    protected Set<String> testOnlyTools() {
        return Set.of(ALLOWANCE_TOOL);
    }

    /** Lists tools (proves the key: initialize needs it), then reads the allowance, which spends none. */
    @Override
    public String test(ConnectorContext ctx) {
        int n = offeredCount(ctx);
        JsonNode r = callUpstream(ctx, ALLOWANCE_TOOL, ctx.mapper().createObjectNode());
        JsonNode a = r.path("structuredContent");
        String allowance = a.has("used")
                ? " Allowance today: " + a.path("used").asText() + " used of " + (a.path("limit").isNull() ? "unlimited" : a.path("limit").asText())
                  + " (" + a.path("access").asText("?") + "), resets " + a.path("resetsAt").asText("at 00:00 UTC") + "."
                : "";
        return "Connected — " + n + " of " + upstreamTools().size() + " allowlisted tools available." + allowance;
    }

    // ── allowlist ────────────────────────────────────────────────────────────

    static final Map<String, Object> LIMIT = number("Max items per list (1-25, default 10).");
    static final Map<String, Object> SUMMARY = bool("Counts plus 3-item samples instead of full lists — cheap way to see what is there.");

    private static Map<String, Object> enumArray(String description, List<String> values) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", "array");
        m.put("items", Map.of("type", "string", "enum", values));
        m.put("description", description);
        return m;
    }

    private static final List<UpstreamTool> TOOLS = List.of(
            new UpstreamTool("get_market_data",
                    "World Monitor market snapshot: curated equity, commodity, crypto and Gulf quotes, sector performance, "
                            + "ETF flows and the Fear & Greed index (quotes stale after 30 min; curated symbols only, not arbitrary tickers).",
                    object(props(
                            "symbols", strArray("Keep only these curated symbols, e.g. [\"GC=F\",\"BTC\",\"AAPL\"]. Omit for the snapshot."),
                            "asset_class", enumArray("Datasets to keep. Omit for all.",
                                    List.of("equity", "commodity", "crypto", "sectors", "etf", "gulf", "sentiment")),
                            "limit", LIMIT,
                            "summary", SUMMARY), List.of()),
                    Duration.ofMinutes(15), Map.of("limit", 10), 25),
            new UpstreamTool("get_economic_data",
                    "Rates and calendars: Fed funds, economic and earnings calendars, ECB FX and Bank of Russia rates, EU yield "
                            + "curves, COT positioning, BIS debt-service ratios and property prices, fuel prices, China macro.",
                    object(props(
                            "dataset", enumArray("Sub-datasets to return. Omit for the whole bundle (large — prefer naming one).",
                                    List.of("fedfunds", "econ-calendar", "china-macro", "china-release-calendar", "fuel-prices",
                                            "ecb-fx-rates", "cbr-rates", "yield-curve-eu", "spending", "earnings-calendar",
                                            "cot", "dsr", "property-residential", "property-commercial")),
                            "country", str("ISO alpha-2/alpha-3 code or country name for the country-keyed datasets."),
                            "limit", LIMIT,
                            "summary", SUMMARY), List.of()),
                    Duration.ofHours(1), Map.of("limit", 10), 25),
            new UpstreamTool("get_country_macro",
                    "Country macro: IMF WEO latest-year inflation, GDP growth, balances, unemployment, external accounts "
                            + "(~210 countries) and Eurostat house prices, quarterly government debt (% GDP) and industrial production.",
                    object(props(
                            "dataset", enumArray("Datasets to return. Omit for all seven.",
                                    List.of("macro", "growth", "labor", "external", "house-prices", "gov-debt-q", "industrial-production")),
                            "countries", strArray("ISO alpha-2 codes to keep, e.g. [\"US\",\"DE\"]; EA20 / EU27_2020 for aggregates."),
                            "limit", integer("Max countries per dataset when no countries filter is given (1-25, default 10)."),
                            "summary", SUMMARY), List.of()),
                    Duration.ofHours(12), Map.of("limit", 10), 25),
            new UpstreamTool("get_price_and_debt_indicators",
                    "National debt levels by country, the FAO Food Price Index and the Big Mac index (debt and FAO refresh "
                            + "within 60 days, Big Mac weekly).",
                    object(props(
                            "dataset", enumArray("Sub-datasets. Omit for all three.", List.of("bigmac", "fao-ffpi", "national-debt")),
                            "country", str("ISO alpha-2/alpha-3 code or country name."),
                            "limit", LIMIT,
                            "summary", SUMMARY), List.of()),
                    Duration.ofHours(12), Map.of("limit", 10), 25),
            new UpstreamTool("get_country_risk",
                    "Composite Instability Index (0-100) for one country with its four components, travel-advisory level and "
                            + "OFAC sanctions exposure; optional daily CII history. Requires a paid World Monitor plan.",
                    object(props(
                            "country_code", str("ISO alpha-2 (e.g. \"IQ\"), alpha-3 or country name."),
                            "history_days", integer("Add up to 90 daily CII closes (1-90). Omit for none.")), List.of("country_code")),
                    Duration.ofHours(1), Map.of(), 0),
            new UpstreamTool("get_conflict_events",
                    "Armed-conflict (UCDP, Iran tracker) and unrest events with coordinates, fatalities and dates, plus country "
                            + "risk scores. Filter by country, area (lat/lon/radius_km) or recency.",
                    object(props(
                            "country", str("Country name, or ISO alpha-2 for risk scores."),
                            "min_fatalities", number("Drop events with fewer deaths."),
                            "since_days", integer("Only events within the last N days (1-365)."),
                            "lat", number("Search centre latitude (with lon and radius_km)."),
                            "lon", number("Search centre longitude."),
                            "radius_km", number("Search radius in km (max 2000)."),
                            "limit", LIMIT,
                            "summary", SUMMARY), List.of()),
                    Duration.ofHours(1), Map.of("limit", 10), 25),
            new UpstreamTool("get_news_intelligence",
                    "Live threat-news feed: AI-classified stories ranked by importance with corroboration and source "
                            + "credibility, GDELT signals by topic, cross-source signals and government security advisories.",
                    object(props(
                            "topic", enumStr("GDELT signal topic.", List.of("conflict", "economy", "cyber", "nuclear", "intelligence", "maritime")),
                            "category", str("Story category, e.g. conflict or economy."),
                            "country", str("Country code or name named in stories."),
                            "query", str("Case-insensitive text in headlines (live window only, not an archive)."),
                            "alerts_only", bool("Only stories flagged as alerts."),
                            "min_importance", number("Minimum importance score."),
                            "limit", LIMIT,
                            "summary", SUMMARY), List.of()),
                    Duration.ofMinutes(30), Map.of("limit", 10), 25),
            new UpstreamTool("get_world_brief",
                    "The hourly AI world brief: top headlines with their corroboration evidence (no request-time generation; "
                            + "stale after 60 min). Requires a paid World Monitor plan.",
                    object(props(), List.of()),
                    Duration.ofHours(1), Map.of(), 0));

    @Override
    protected List<UpstreamTool> upstreamTools() {
        return TOOLS;
    }
}
