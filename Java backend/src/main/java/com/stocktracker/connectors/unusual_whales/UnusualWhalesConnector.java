package com.stocktracker.connectors.unusual_whales;

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
import java.net.URI;
import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static com.stocktracker.connectors.ToolArgs.*;

/**
 * Unusual Whales (Connectors roadmap, options flow): rule-based options flow alerts, dark pool
 * prints, Market Tide net premium, congressional trades and dealer Greek exposure, with the
 * user's own API token.
 *
 * <p>Endpoints, parameters and response fields follow the published OpenAPI document
 * (api.unusualwhales.com/api/openapi). UW sends most numbers as strings ("186705"); they are
 * converted to numbers here. Timestamps are normalised to ISO-8601 UTC.
 */
@Component
public class UnusualWhalesConnector implements Connector {

    static final String ID = "unusual_whales";
    static final String BASE_URL = "https://api.unusualwhales.com";

    private static final ConnectorSpec SPEC = new ConnectorSpec(
            ID,
            "Unusual Whales",
            ConnectorSpec.Category.OPTIONS_FLOW,
            ConnectorSpec.Licence.BYO_PERSONAL,   // self-serve API plans are personal use; redistribution needs an enterprise licence
            "Options flow alerts (sweeps, repeated hits, unusual size vs open interest), dark pool prints, "
                    + "Market Tide net call/put premium, congressional trades with filing dates, and per-ticker "
                    + "dealer Greek exposure (gamma, delta, vanna, charm).",
            "Data: Unusual Whales (unusualwhales.com)",
            "https://api.unusualwhales.com/docs",
            List.of(Field.secret("api_token", "API token",
                    "Your Unusual Whales API token (a UUID) from unusualwhales.com/dashboard/api.")),
            // Personal plans publish 120 requests/minute and a daily cap (from ~30k/day); keep headroom.
            List.of(RateLimit.perMinute(100), RateLimit.perDay(20_000)));

    private static final DateTimeFormatter UW_TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss[.SSSSSS][.SSS] X", Locale.ROOT);
    private static final DateTimeFormatter UW_TS_OFFSET = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss[.SSSSSS][.SSS] xx", Locale.ROOT);

    private final String base;

    public UnusualWhalesConnector() {
        this(BASE_URL);
    }

    /** Test seam: point at a fake Unusual Whales. */
    UnusualWhalesConnector(String base) {
        this.base = base;
    }

    @Override
    public ConnectorSpec spec() {
        return SPEC;
    }

    @Override
    public String test(ConnectorContext ctx) {
        List<JsonNode> tide = data(get(ctx, "/api/market/market-tide", "interval_5m", "true"));
        String day = tide.isEmpty() ? null : tide.get(0).path("date").asText(null);
        return "Connected — Unusual Whales answered (Market Tide: " + tide.size() + " points" + (day == null ? "" : " for " + day) + ").";
    }

    @Override
    public List<ConnectorTool> tools() {
        return List.of(
                new ConnectorTool(ID + "_flow_alerts",
                        "Options flow alerts: rule-based aggregations of the options tape (RepeatedHits, sweeps, floor "
                                + "trades, size > open interest), newest first. Each alert: contract, call/put, strike, expiry, "
                                + "total premium with ask-side vs bid-side split, size, volume/OI, underlying price, time.",
                        object(props(
                                "tickers", strArray("Only these tickers (max 10). Omit for the whole market."),
                                "side", enumStr("calls, puts or both (default)", List.of("calls", "puts", "both")),
                                "min_premium", integer("Minimum total premium in USD, e.g. 250000"),
                                "unusual", bool("Use UW's 'unusual' preset (volume>OI, size>OI, opening, OTM)"),
                                "sweeps_only", bool("Only intermarket sweeps"),
                                "limit", integer("Max alerts (1-100, default 25)")), List.of()),
                        Duration.ofMinutes(2), this::flowAlerts,
                        a -> "Reading options flow alerts from Unusual Whales…"),
                new ConnectorTool(ID + "_darkpool",
                        "Dark pool (off-exchange, TRF-reported) prints for one ticker on a trading day, newest first: "
                                + "price, size, premium, NBBO at the time, and execution time.",
                        object(props(
                                "ticker", str("Ticker symbol"),
                                "date", str("Trading day YYYY-MM-DD (default: latest)"),
                                "min_premium", integer("Minimum print premium in USD"),
                                "limit", integer("Max prints (1-100, default 25)")), List.of("ticker")),
                        Duration.ofMinutes(5), this::darkpool,
                        a -> "Reading dark pool prints for " + a.path("ticker").asText("") + "…"),
                new ConnectorTool(ID + "_market_tide",
                        "Market Tide: market-wide net call premium vs net put premium through a trading day (UW's "
                                + "proprietary options-sentiment gauge). Returned downsampled to at most `points` "
                                + "timestamps, plus the latest reading.",
                        object(props(
                                "date", str("Trading day YYYY-MM-DD (default: latest)"),
                                "otm_only", bool("Only out-of-the-money transactions"),
                                "points", integer("Max points to return (5-120, default 40)")), List.of()),
                        Duration.ofMinutes(5), this::marketTide,
                        a -> "Reading Market Tide from Unusual Whales…"),
                new ConnectorTool(ID + "_congress_trades",
                        "Recent trades by members of Congress: member, chamber, ticker, buy/sell, amount range, "
                                + "transaction_date and filed_at (the disclosure date — when it became public).",
                        object(props(
                                "ticker", str("Only this ticker"),
                                "date", str("Only reports for this transaction date YYYY-MM-DD"),
                                "limit", integer("Max rows (1-100, default 25)")), List.of()),
                        Duration.ofHours(1), this::congressTrades,
                        a -> "Reading congressional trades from Unusual Whales…"),
                new ConnectorTool(ID + "_greek_exposure",
                        "Daily dealer (market-maker) Greek exposure for a ticker: call/put gamma, delta, vanna and "
                                + "charm exposure and net gamma, newest first. Positive net gamma = dealers long gamma.",
                        object(props(
                                "ticker", str("Ticker symbol"),
                                "date", str("As of trading day YYYY-MM-DD (default: latest)"),
                                "limit", integer("Days to return (1-120, default 20)")), List.of("ticker")),
                        Duration.ofMinutes(30), this::greekExposure,
                        a -> "Reading Greek exposure for " + a.path("ticker").asText("") + "…"));
    }

    // ── tools ────────────────────────────────────────────────────────────────

    Object flowAlerts(ConnectorContext ctx, JsonNode args) {
        Set<String> tickers = strSet(args, "tickers", true);
        if (tickers.size() > 10) throw ConnectorException.badRequest("tickers: at most 10");
        for (String t : tickers) if (!t.matches("^[A-Z0-9.^/-]{1,12}$")) throw ConnectorException.badRequest("tickers must look like AAPL or BRK.B");
        String side = oneOf(args, "side", "both", List.of("calls", "puts", "both"));
        int limit = intArg(args, "limit", 25, 1, 100);
        JsonNode minPrem = args == null ? null : args.get("min_premium");
        Long min = minPrem == null || minPrem.isNull() ? null : Math.max(0, minPrem.asLong());
        JsonNode raw = get(ctx, "/api/option-trades/flow-alerts",
                "ticker_symbol", tickers.isEmpty() ? null : String.join(",", tickers),
                "is_call", side.equals("calls") ? "true" : null,
                "is_put", side.equals("puts") ? "true" : null,
                "min_premium", min == null ? null : String.valueOf(min),
                "unusual", flag(args, "unusual") ? "true" : null,
                "is_sweep", flag(args, "sweeps_only") ? "true" : null,
                "limit", String.valueOf(limit));
        List<Map<String, Object>> rows = new ArrayList<>();
        for (JsonNode r : data(raw)) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("created_at", iso(r.path("created_at").asText(null)));
            m.put("ticker", text(r, "ticker"));
            m.put("type", text(r, "type"));
            m.put("option_chain", text(r, "option_chain"));
            m.put("strike", num(r.path("strike")));
            m.put("expiry", text(r, "expiry"));
            m.put("alert_rule", text(r, "alert_rule"));
            m.put("total_premium", num(r.path("total_premium")));
            m.put("ask_side_premium", num(r.path("total_ask_side_prem")));
            m.put("bid_side_premium", num(r.path("total_bid_side_prem")));
            m.put("total_size", num(r.path("total_size")));
            m.put("trade_count", num(r.path("trade_count")));
            m.put("volume", num(r.path("volume")));
            m.put("open_interest", num(r.path("open_interest")));
            m.put("volume_oi_ratio", round(num(r.path("volume_oi_ratio")), 3));
            m.put("price", num(r.path("price")));
            m.put("underlying_price", num(r.path("underlying_price")));
            m.put("has_sweep", r.path("has_sweep").isBoolean() ? r.path("has_sweep").asBoolean() : null);
            m.put("has_floor", r.path("has_floor").isBoolean() ? r.path("has_floor").asBoolean() : null);
            m.put("all_opening", r.path("all_opening_trades").isBoolean() ? r.path("all_opening_trades").asBoolean() : null);
            rows.add(m);
            if (rows.size() >= limit) break;
        }
        return Map.of("count", rows.size(), "alerts", rows);
    }

    Object darkpool(ConnectorContext ctx, JsonNode args) {
        String ticker = ticker(args, "ticker");
        LocalDate date = date(args, "date");
        int limit = intArg(args, "limit", 25, 1, 100);
        JsonNode minPrem = args.get("min_premium");
        JsonNode raw = get(ctx, "/api/darkpool/" + enc(ticker),
                "date", date == null ? null : date.toString(),
                "min_premium", minPrem == null || minPrem.isNull() ? null : String.valueOf(Math.max(0, minPrem.asLong())),
                "limit", String.valueOf(limit));
        List<Map<String, Object>> rows = new ArrayList<>();
        for (JsonNode r : data(raw)) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("executed_at", iso(r.path("executed_at").asText(null)));
            m.put("price", num(r.path("price")));
            m.put("size", num(r.path("size")));
            m.put("premium", num(r.path("premium")));
            m.put("nbbo_bid", num(r.path("nbbo_bid")));
            m.put("nbbo_ask", num(r.path("nbbo_ask")));
            m.put("day_volume", num(r.path("volume")));
            m.put("market_center", text(r, "market_center"));
            m.put("extended_hours", text(r, "ext_hour_sold_codes"));
            m.put("canceled", r.path("canceled").isBoolean() ? r.path("canceled").asBoolean() : null);
            m.put("trf_reported_at", iso(r.path("trf_executed_at").asText(null)));
            rows.add(m);
            if (rows.size() >= limit) break;
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ticker", ticker);
        out.put("date", date == null ? null : date.toString());
        out.put("count", rows.size());
        out.put("prints", rows);
        return out;
    }

    Object marketTide(ConnectorContext ctx, JsonNode args) {
        LocalDate date = date(args, "date");
        int points = intArg(args, "points", 40, 5, 120);
        JsonNode raw = get(ctx, "/api/market/market-tide",
                "date", date == null ? null : date.toString(),
                "otm_only", flag(args, "otm_only") ? "true" : null,
                "interval_5m", "true");
        List<Map<String, Object>> all = new ArrayList<>();
        for (JsonNode r : data(raw)) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("timestamp", iso(r.path("timestamp").asText(null)));
            m.put("net_call_premium", num(r.path("net_call_premium")));
            m.put("net_put_premium", num(r.path("net_put_premium")));
            m.put("net_volume", num(r.path("net_volume")));
            all.add(m);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("date", data(raw).isEmpty() ? (date == null ? null : date.toString()) : data(raw).get(0).path("date").asText(null));
        out.put("latest", all.isEmpty() ? null : all.get(all.size() - 1));
        out.put("points_total", all.size());
        out.put("points", downsample(all, points));
        return out;
    }

    Object congressTrades(ConnectorContext ctx, JsonNode args) {
        String ticker = optStr(args, "ticker") == null ? null : ticker(args, "ticker");
        LocalDate date = date(args, "date");
        int limit = intArg(args, "limit", 25, 1, 100);
        JsonNode raw = get(ctx, "/api/congress/recent-trades",
                "ticker", ticker, "date", date == null ? null : date.toString(), "limit", String.valueOf(limit));
        List<Map<String, Object>> rows = new ArrayList<>();
        for (JsonNode r : data(raw)) {
            Map<String, Object> m = new LinkedHashMap<>();
            String traded = text(r, "transaction_date");
            String filed = text(r, "filed_at_date");
            m.put("name", text(r, "name"));
            m.put("reporter", text(r, "reporter"));
            m.put("chamber", text(r, "member_type"));
            m.put("ticker", text(r, "ticker"));
            m.put("transaction", text(r, "txn_type"));
            m.put("amount_range", text(r, "amounts"));
            m.put("owner", text(r, "issuer"));
            m.put("transaction_date", traded);
            m.put("filed_at", filed);
            m.put("disclosure_lag_days", lag(traded, filed));
            m.put("notes", trim(text(r, "notes"), 200));
            rows.add(m);
            if (rows.size() >= limit) break;
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("count", rows.size());
        out.put("trades", rows);
        out.put("note", "filed_at is the disclosure date (when the trade became public); transaction_date can be up to 45 days earlier.");
        if (raw.has("total_pages")) out.put("total_pages", raw.path("total_pages").asInt());
        return out;
    }

    Object greekExposure(ConnectorContext ctx, JsonNode args) {
        String ticker = ticker(args, "ticker");
        LocalDate date = date(args, "date");
        int limit = intArg(args, "limit", 20, 1, 120);
        JsonNode raw = get(ctx, "/api/stock/" + enc(ticker) + "/greek-exposure", "date", date == null ? null : date.toString());
        List<Map<String, Object>> rows = new ArrayList<>();
        for (JsonNode r : data(raw)) {
            Map<String, Object> m = new LinkedHashMap<>();
            Double cg = num(r.path("call_gamma"));
            Double pg = num(r.path("put_gamma"));
            m.put("date", text(r, "date"));
            m.put("net_gamma", cg == null || pg == null ? null : round(cg + pg, 2));
            m.put("call_gamma", cg);
            m.put("put_gamma", pg);
            m.put("call_delta", num(r.path("call_delta")));
            m.put("put_delta", num(r.path("put_delta")));
            m.put("call_vanna", num(r.path("call_vanna")));
            m.put("put_vanna", num(r.path("put_vanna")));
            m.put("call_charm", num(r.path("call_charm")));
            m.put("put_charm", num(r.path("put_charm")));
            rows.add(m);
        }
        rows.sort((a, b) -> String.valueOf(b.get("date")).compareTo(String.valueOf(a.get("date"))));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ticker", ticker);
        out.put("count", Math.min(limit, rows.size()));
        out.put("days", rows.subList(0, Math.min(limit, rows.size())));
        return out;
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private JsonNode get(ConnectorContext ctx, String path, String... query) {
        Object[] kv = new Object[query.length];
        System.arraycopy(query, 0, kv, 0, query.length);
        Map<String, String> headers = Map.of("Authorization", "Bearer " + ctx.require("api_token"));
        try {
            return ctx.getJson(URI.create(url(base + path, kv)), headers);
        } catch (ConnectorException e) {
            // 402 = token deactivated for an unpaid subscription (documented); treat as a credential problem.
            if (e.kind() == ConnectorException.Kind.UPSTREAM && e.getMessage().contains("HTTP 402")) {
                throw new ConnectorException(ConnectorException.Kind.AUTH,
                        "Unusual Whales: the API subscription is unpaid (HTTP 402); the token works again once payment succeeds.");
            }
            throw e;
        }
    }

    static List<JsonNode> data(JsonNode n) {
        List<JsonNode> out = new ArrayList<>();
        JsonNode arr = n == null ? null : n.isArray() ? n : n.path("data");
        if (arr != null && arr.isArray()) arr.forEach(out::add);
        return out;
    }

    static boolean flag(JsonNode args, String name) {
        return args != null && args.path(name).asBoolean(false);
    }

    static String text(JsonNode r, String f) {
        JsonNode n = r.path(f);
        if (n.isMissingNode() || n.isNull()) return null;
        String s = n.asText("").strip();
        return s.isEmpty() ? null : s;
    }

    static Double num(JsonNode n) {
        if (n == null || n.isMissingNode() || n.isNull()) return null;
        if (n.isNumber()) return n.asDouble();
        try {
            return new BigDecimal(n.asText().strip()).doubleValue();
        } catch (Exception e) {
            return null;
        }
    }

    static Double round(Double v, int places) {
        if (v == null) return null;
        double f = Math.pow(10, places);
        return Math.round(v * f) / f;
    }

    /** "2023-12-12 16:35:52 UTC" / "2023-09-08 09:30:00 -0400" / ISO → ISO-8601 UTC; unparseable values pass through. */
    static String iso(String s) {
        if (s == null || s.isBlank()) return null;
        String t = s.strip();
        try {
            if (t.endsWith(" UTC")) return OffsetDateTime.parse(t.replace(" UTC", " Z"), UW_TS).withOffsetSameInstant(ZoneOffset.UTC).toInstant().toString();
            if (t.matches(".* [+-]\\d{4}$")) return OffsetDateTime.parse(t, UW_TS_OFFSET).toInstant().toString();
            return OffsetDateTime.parse(t).toInstant().toString();
        } catch (Exception e) {
            return t;
        }
    }

    static Long lag(String from, String to) {
        try {
            return from == null || to == null ? null : ChronoUnit.DAYS.between(LocalDate.parse(from), LocalDate.parse(to));
        } catch (Exception e) {
            return null;
        }
    }

    static String trim(String s, int max) {
        if (s == null) return null;
        String t = s.replaceAll("\\s+", " ").strip();
        return t.length() > max ? t.substring(0, max) + "…" : t;
    }

    /** Evenly spaced picks that always keep the first and last element. */
    static <T> List<T> downsample(List<T> in, int max) {
        if (in.size() <= max) return in;
        List<T> out = new ArrayList<>(max);
        for (int i = 0; i < max; i++) out.add(in.get((int) Math.round(i * (in.size() - 1) / (double) (max - 1))));
        return out;
    }
}
