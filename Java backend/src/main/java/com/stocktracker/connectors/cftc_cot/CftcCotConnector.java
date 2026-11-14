package com.stocktracker.connectors.cftc_cot;

import com.fasterxml.jackson.databind.JsonNode;
import com.stocktracker.connectors.Connector;
import com.stocktracker.connectors.ConnectorContext;
import com.stocktracker.connectors.ConnectorException;
import com.stocktracker.connectors.ConnectorSpec;
import com.stocktracker.connectors.ConnectorSpec.Field;
import com.stocktracker.connectors.ConnectorSpec.RateLimit;
import com.stocktracker.connectors.ConnectorTool;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static com.stocktracker.connectors.ToolArgs.bool;
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
 * CFTC Commitments of Traders from the CFTC's public reporting (Socrata) API: legacy,
 * disaggregated (commodities) and Traders in Financial Futures reports, futures-only or
 * futures-and-options combined. Keyless; an optional Socrata app token raises the throttle.
 *
 * <p>Point-in-time: positions are as of Tuesday and published Friday 3:30 PM ET (the next
 * business day after a federal holiday). Rows carry {@code released_est} = report date + 3 days.
 */
@Component
public class CftcCotConnector implements Connector {

    static final String ID = "cftc_cot";
    static final String BASE_URL = "https://publicreporting.cftc.gov/resource";
    static final List<String> REPORTS = List.of("legacy", "disaggregated", "tff");

    /** Socrata dataset ids: report → {futures only, futures + options combined}. */
    static final Map<String, String[]> DATASETS = Map.of(
            "legacy", new String[] {"6dca-aqww", "jun7-fc8e"},
            "disaggregated", new String[] {"72hh-3qpy", "kh3c-gbw2"},
            "tff", new String[] {"gpe5-46if", "yw9f-hn96"});

    /** One trader group: column names in the dataset (null = not reported). The CFTC's own typos are kept. */
    record Group(String name, String longCol, String shortCol, String spreadCol, String chgLong, String chgShort,
                 String tradersLong, String tradersShort) { }

    static final Map<String, List<Group>> GROUPS = Map.of(
            "legacy", List.of(
                    new Group("noncommercial", "noncomm_positions_long_all", "noncomm_positions_short_all", "noncomm_postions_spread_all",
                            "change_in_noncomm_long_all", "change_in_noncomm_short_all", "traders_noncomm_long_all", "traders_noncomm_short_all"),
                    new Group("commercial", "comm_positions_long_all", "comm_positions_short_all", null,
                            "change_in_comm_long_all", "change_in_comm_short_all", "traders_comm_long_all", "traders_comm_short_all"),
                    nonReportable()),
            "disaggregated", List.of(
                    new Group("producer_merchant", "prod_merc_positions_long", "prod_merc_positions_short", null,
                            "change_in_prod_merc_long", "change_in_prod_merc_short", "traders_prod_merc_long_all", "traders_prod_merc_short_all"),
                    new Group("swap_dealer", "swap_positions_long_all", "swap__positions_short_all", "swap__positions_spread_all",
                            "change_in_swap_long_all", "change_in_swap_short_all", "traders_swap_long_all", "traders_swap_short_all"),
                    new Group("managed_money", "m_money_positions_long_all", "m_money_positions_short_all", "m_money_positions_spread",
                            "change_in_m_money_long_all", "change_in_m_money_short_all", "traders_m_money_long_all", "traders_m_money_short_all"),
                    otherReportable(),
                    nonReportable()),
            "tff", List.of(
                    new Group("dealer", "dealer_positions_long_all", "dealer_positions_short_all", "dealer_positions_spread_all",
                            "change_in_dealer_long_all", "change_in_dealer_short_all", "traders_dealer_long_all", "traders_dealer_short_all"),
                    new Group("asset_manager", "asset_mgr_positions_long", "asset_mgr_positions_short", "asset_mgr_positions_spread",
                            "change_in_asset_mgr_long", "change_in_asset_mgr_short", "traders_asset_mgr_long_all", "traders_asset_mgr_short_all"),
                    new Group("leveraged_funds", "lev_money_positions_long", "lev_money_positions_short", "lev_money_positions_spread",
                            "change_in_lev_money_long", "change_in_lev_money_short", "traders_lev_money_long_all", "traders_lev_money_short_all"),
                    otherReportable(),
                    nonReportable()));

    private static Group otherReportable() {
        return new Group("other_reportable", "other_rept_positions_long", "other_rept_positions_short", "other_rept_positions_spread",
                "change_in_other_rept_long", "change_in_other_rept_short", "traders_other_rept_long_all", "traders_other_rept_short");
    }

    private static Group nonReportable() {
        return new Group("nonreportable", "nonrept_positions_long_all", "nonrept_positions_short_all", null,
                "change_in_nonrept_long_all", "change_in_nonrept_short_all", null, null);
    }

    private static final ConnectorSpec SPEC = new ConnectorSpec(
            ID,
            "CFTC Commitments of Traders",
            ConnectorSpec.Category.MACRO,
            ConnectorSpec.Licence.FREE_PUBLIC,
            "Weekly futures positioning from the CFTC: who is long and short (speculators vs hedgers; managed money, "
                    + "swap dealers, producers; asset managers vs leveraged funds) in every U.S. futures market — crude, gold, "
                    + "grains, Treasuries, S&P 500, currencies, VIX. For crowded-positioning and sentiment extremes.",
            "Source: U.S. Commodity Futures Trading Commission, Commitments of Traders (publicreporting.cftc.gov)",
            "https://publicreporting.cftc.gov/stories/s/r4w3-av2u",
            List.of(new Field("app_token", "Socrata app token (optional)",
                    "Optional. Works without one; a free app token from publicreporting.cftc.gov (sign in → Developer settings) "
                            + "gives a higher request allowance.", true, true, null)),
            List.of(RateLimit.perSecond(3), RateLimit.perMinute(60)));   // Socrata throttles anonymous clients per IP

    private final String base;

    public CftcCotConnector() {
        this(BASE_URL);
    }

    /** Test seam: point at a fake Socrata. */
    CftcCotConnector(String base) {
        this.base = base;
    }

    @Override
    public ConnectorSpec spec() {
        return SPEC;
    }

    @Override
    public String test(ConnectorContext ctx) {
        JsonNode r = query(ctx, DATASETS.get("legacy")[0], "$select", "max(report_date_as_yyyy_mm_dd) as latest");
        String latest = r.path(0).path("latest").asText("");
        if (latest.isEmpty()) throw ConnectorException.upstream("CFTC answered without data.");
        return "Connected — CFTC public reporting answered (latest COT report as of " + latest.substring(0, Math.min(10, latest.length())) + ").";
    }

    @Override
    public List<ConnectorTool> tools() {
        return List.of(
                new ConnectorTool(ID + "_markets",
                        "Find COT markets by name: contract market code, full name and the date of its latest report "
                                + "(inactive markets show old dates). E.g. \"CRUDE OIL\", \"GOLD\", \"S&P 500\", \"10-YEAR\", \"EURO FX\".",
                        object(props(
                                "query", str("Words in the market name, e.g. GOLD"),
                                "report", enumStr("legacy (default), disaggregated (commodities) or tff (financial futures)", REPORTS),
                                "limit", integer("Max markets (1-30, default 15)")), List.of("query")),
                        Duration.ofHours(12), this::markets,
                        a -> "Searching COT markets for " + a.path("query").asText("") + "…"),
                new ConnectorTool(ID + "_report",
                        "Commitments of Traders for one market, newest week first: open interest and, per trader group, "
                                + "long/short/spread, net, net as % of open interest, weekly changes and trader counts. "
                                + "legacy: noncommercial (speculators) vs commercial (hedgers); disaggregated: producer/merchant, "
                                + "swap dealer, managed money, other; tff: dealer, asset manager, leveraged funds, other.",
                        object(props(
                                "market", str("Contract market code (e.g. 067651 WTI, 088691 gold, 13874A E-mini S&P 500, "
                                        + "043602 10-year note) or a market name (resolved via cftc_cot_markets)"),
                                "report", enumStr("legacy (default), disaggregated or tff", REPORTS),
                                "combined", bool("true = futures and options combined; false (default) = futures only"),
                                "weeks", integer("Weeks (1-52, default 8)")), List.of("market")),
                        Duration.ofHours(3), this::report,
                        a -> "Reading Commitments of Traders for " + a.path("market").asText("") + "…"));
    }

    // ── tools ────────────────────────────────────────────────────────────────

    Object markets(ConnectorContext ctx, JsonNode args) {
        String q = name(requireStr(args, "query", 80));
        String report = oneOf(args, "report", "legacy", REPORTS);
        int limit = intArg(args, "limit", 15, 1, 30);
        JsonNode r = query(ctx, DATASETS.get(report)[0],
                "$select", "cftc_contract_market_code,market_and_exchange_names,max(report_date_as_yyyy_mm_dd) as latest",
                "$where", "upper(market_and_exchange_names) like '%" + q.replace("'", "''") + "%'",
                "$group", "cftc_contract_market_code,market_and_exchange_names",
                "$order", "latest DESC",
                "$limit", limit);
        List<Map<String, Object>> rows = new ArrayList<>();
        for (JsonNode m : r) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("code", m.path("cftc_contract_market_code").asText().strip());
            row.put("market", m.path("market_and_exchange_names").asText());
            row.put("latest_report", day(m.path("latest").asText(null)));
            rows.add(row);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("query", q);
        out.put("report", report);
        out.put("markets", rows);
        return out;
    }

    Object report(ConnectorContext ctx, JsonNode args) {
        String market = requireStr(args, "market", 80);
        String report = oneOf(args, "report", "legacy", REPORTS);
        boolean combined = args != null && args.path("combined").asBoolean(false);
        int weeks = intArg(args, "weeks", 8, 1, 52);
        String dataset = DATASETS.get(report)[combined ? 1 : 0];
        String code = resolveCode(ctx, dataset, market);

        JsonNode r = query(ctx, dataset,
                "$where", "cftc_contract_market_code='" + code + "'",
                "$order", "report_date_as_yyyy_mm_dd DESC",
                "$limit", weeks);
        if (!r.isArray() || r.isEmpty()) throw ConnectorException.badRequest("No " + report + " COT data for market code " + code);
        List<Map<String, Object>> rows = new ArrayList<>();
        for (JsonNode w : r) {
            Map<String, Object> row = new LinkedHashMap<>();
            String date = day(w.path("report_date_as_yyyy_mm_dd").asText(null));
            row.put("report_date", date);
            if (date != null) row.put("released_est", LocalDate.parse(date).plusDays(3).toString());
            Number oi = num(w, "open_interest_all");
            row.put("open_interest", oi);
            row.put("change_open_interest", num(w, "change_in_open_interest_all"));
            Map<String, Object> groups = new LinkedHashMap<>();
            for (Group g : GROUPS.get(report)) {
                Map<String, Object> m = new LinkedHashMap<>();
                Number lg = num(w, g.longCol());
                Number sh = num(w, g.shortCol());
                m.put("long", lg);
                m.put("short", sh);
                if (g.spreadCol() != null) m.put("spread", num(w, g.spreadCol()));
                if (lg != null && sh != null) {
                    long net = lg.longValue() - sh.longValue();
                    m.put("net", net);
                    if (oi != null && oi.longValue() > 0) {
                        m.put("net_pct_oi", BigDecimal.valueOf(100.0 * net / oi.longValue()).setScale(1, RoundingMode.HALF_UP).doubleValue());
                    }
                }
                m.put("change_long", num(w, g.chgLong()));
                m.put("change_short", num(w, g.chgShort()));
                if (g.tradersLong() != null) {
                    m.put("traders_long", num(w, g.tradersLong()));
                    m.put("traders_short", num(w, g.tradersShort()));
                }
                groups.put(g.name(), m);
            }
            row.put("groups", groups);
            rows.add(row);
        }
        JsonNode first = r.path(0);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("market", first.path("market_and_exchange_names").asText(null));
        out.put("code", code);
        out.put("report", report + (combined ? " (futures and options combined)" : " (futures only)"));
        out.put("contract_units", first.path("contract_units").asText(null));
        out.put("weeks", rows);
        out.put("note", "Positions in contracts as of report_date (Tuesday); public on released_est (Friday 3:30 PM ET, "
                + "later after holidays). Use released_est for point-in-time backtests.");
        return out;
    }

    /** A 6-character contract code passes through; a name is resolved to the most recently reported matching market. */
    String resolveCode(ConnectorContext ctx, String dataset, String market) {
        String m = market.strip().toUpperCase(Locale.ROOT);
        if (m.matches("^[0-9A-Z]{6}$") && m.matches(".*\\d.*")) return m;
        String q = name(m);
        JsonNode r = query(ctx, dataset,
                "$select", "cftc_contract_market_code,market_and_exchange_names,max(report_date_as_yyyy_mm_dd) as latest",
                "$where", "upper(market_and_exchange_names) like '%" + q.replace("'", "''") + "%'",
                "$group", "cftc_contract_market_code,market_and_exchange_names",
                "$order", "latest DESC",
                "$limit", 20);
        if (!r.isArray() || r.isEmpty()) throw ConnectorException.badRequest("No COT market matches \"" + market + "\"; try cftc_cot_markets");
        String newest = r.path(0).path("latest").asText("");
        List<JsonNode> active = new ArrayList<>();
        for (JsonNode x : r) if (x.path("latest").asText("").equals(newest)) active.add(x);
        // Prefer an exact contract name ("GOLD - COMMODITY EXCHANGE INC." for "gold").
        for (JsonNode x : active) {
            if (x.path("market_and_exchange_names").asText("").toUpperCase(Locale.ROOT).startsWith(q + " - ")) {
                return x.path("cftc_contract_market_code").asText().strip();
            }
        }
        if (active.size() == 1) return active.get(0).path("cftc_contract_market_code").asText().strip();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < Math.min(8, active.size()); i++) {
            JsonNode x = active.get(i);
            sb.append(i == 0 ? "" : "; ").append(x.path("cftc_contract_market_code").asText().strip()).append(" = ")
              .append(x.path("market_and_exchange_names").asText());
        }
        throw ConnectorException.badRequest("\"" + market + "\" matches several markets — pass one code: " + sb);
    }

    // ── plumbing ─────────────────────────────────────────────────────────────

    JsonNode query(ConnectorContext ctx, String dataset, Object... kv) {
        Map<String, String> headers = new LinkedHashMap<>();
        ctx.field("app_token").ifPresent(t -> headers.put("X-App-Token", t));
        try {
            return ctx.getJson(URI.create(url(base + "/" + dataset + ".json", kv)), headers);
        } catch (ConnectorException e) {
            if (e.kind() == ConnectorException.Kind.AUTH) {
                throw new ConnectorException(ConnectorException.Kind.AUTH,
                        "CFTC public reporting rejected the app token. Check it in Settings → Connectors, or remove it.");
            }
            throw e;
        }
    }

    private static String name(String raw) {
        String s = raw.strip().toUpperCase(Locale.ROOT);
        if (!s.matches("^[A-Z0-9 &.,/#()+'-]{2,80}$")) {
            throw ConnectorException.badRequest("Market names may use letters, digits, spaces and & . , / # ( ) + ' -");
        }
        return s;
    }

    private static String day(String ts) {
        return ts == null || ts.length() < 10 ? ts : ts.substring(0, 10);
    }

    private static Number num(JsonNode row, String col) {
        if (col == null) return null;
        JsonNode v = row.get(col);
        if (v == null || v.isNull()) return null;
        String t = v.asText().strip();
        if (t.isEmpty() || t.equals(".")) return null;
        try {
            BigDecimal d = new BigDecimal(t);
            return d.scale() <= 0 ? (Number) d.longValue() : (Number) d.doubleValue();
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
