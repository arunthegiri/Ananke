package com.stocktracker.connectors.orats;

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
import java.time.Duration;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static com.stocktracker.connectors.ToolArgs.*;

/**
 * ORATS Data API (Connectors roadmap, options): implied-volatility summaries, IV rank/percentile,
 * the monthly implied-vol term structure, and earnings implied moves, with the user's own token.
 *
 * <p>Uses the delayed/end-of-day {@code /datav2} endpoints (available on every Data API plan) and
 * the documented {@code fields} parameter so ORATS returns only the columns used here. Every row
 * carries ORATS' {@code tradeDate} and {@code updatedAt} (when the value was computed).
 * Endpoints and field names follow orats.com/openapi.json.
 */
@Component
public class OratsConnector implements Connector {

    static final String ID = "orats";
    static final String BASE_URL = "https://api.orats.io";

    static final String SUMMARY_FIELDS = "ticker,tradeDate,stockPrice,iv10d,iv20d,iv30d,iv60d,iv90d,iv6m,iv1y,exErnIv30d,"
            + "rVol30,impliedMove,ieeEarnEffect,skewing,contango,confidence,updatedAt";
    static final String RANK_FIELDS = "ticker,tradeDate,iv,ivRank1m,ivPct1m,ivRank1y,ivPct1y,updatedAt";
    static final String TERM_FIELDS = "ticker,tradeDate,expirDate,stockPrice,atmiv,calVol,unadjVol,earnEffect,slope,vol25,vol50,vol75,confidence,updatedAt";
    static final String EARN_FIELDS = "ticker,tradeDate,lastErn,nextErn,absAvgErnMv,impErnMv,impliedEarningsMove,impliedIee,"
            + "ernMv1,ernMv2,ernMv3,ernMv4,ernMvStdv,ivEarnReturn,updatedAt";

    private static final ConnectorSpec SPEC = new ConnectorSpec(
            ID,
            "ORATS",
            ConnectorSpec.Category.OPTIONS_FLOW,
            ConnectorSpec.Licence.BYO_PERSONAL,
            "Options implied volatility from ORATS: 10-day to 1-year IV (with and without earnings), IV rank "
                    + "and percentile, the monthly ATM term structure with skew points, and earnings implied moves "
                    + "vs. historical earnings moves. Delayed/end-of-day data on any ORATS Data API plan.",
            "Data: ORATS (Option Research & Technology Services)",
            "https://orats.com/docs",
            List.of(Field.secret("token", "API token", "Your ORATS Data API token from dashboard.orats.com/api-console.")),
            // Documented: 1,000 requests/minute on the Data APIs plus a monthly quota by plan. Stay well under both.
            List.of(RateLimit.perMinute(60), RateLimit.perDay(2_000)));

    private final String base;

    public OratsConnector() {
        this(BASE_URL);
    }

    /** Test seam: point at a fake ORATS. */
    OratsConnector(String base) {
        this.base = base;
    }

    @Override
    public ConnectorSpec spec() {
        return SPEC;
    }

    @Override
    public String test(ConnectorContext ctx) {
        List<JsonNode> rows = data(get(ctx, "/datav2/ivrank", "ticker", "SPY", "fields", "ticker,tradeDate,ivRank1y"));
        String day = rows.isEmpty() ? null : rows.get(0).path("tradeDate").asText(null);
        return "Connected — ORATS answered" + (day == null ? "." : " (SPY IV rank as of " + day + ").");
    }

    @Override
    public List<ConnectorTool> tools() {
        return List.of(
                new ConnectorTool(ID + "_iv_summary",
                        "Implied-volatility snapshot for up to 10 tickers: IV at 10d/20d/30d/60d/90d/6m/1y, 30d IV ex-earnings, "
                                + "30d realised vol, IV rank and percentile (1 month and 1 year), the implied earnings move, "
                                + "skew and contango. Vols are decimals (0.25 = 25%), IV rank/percentile 0-100.",
                        object(props("tickers", strArray("Tickers, e.g. [\"AAPL\",\"TSLA\"] (max 10)")), List.of("tickers")),
                        Duration.ofMinutes(15), this::ivSummary,
                        a -> "Reading ORATS implied volatility…"),
                new ConnectorTool(ID + "_term_structure",
                        "The implied-volatility term structure for a ticker: per monthly expiry the ATM IV, smoothed IV "
                                + "with and without the earnings effect, the earnings effect, skew slope and IV at the "
                                + "25/50/75 call-delta points, with days to expiry.",
                        object(props(
                                "ticker", str("Ticker symbol"),
                                "limit", integer("Max expiries (1-24, default 12)")), List.of("ticker")),
                        Duration.ofMinutes(15), this::termStructure,
                        a -> "Reading the ORATS term structure for " + a.path("ticker").asText("") + "…"),
                new ConnectorTool(ID + "_earnings",
                        "Earnings for a ticker: the options-implied earnings move now vs. the average absolute move on "
                                + "past earnings (and the last four), last/next earnings date, plus a list of past "
                                + "earnings dates with announcement time. nextErn may need a separate ORATS subscription.",
                        object(props(
                                "ticker", str("Ticker symbol"),
                                "history", integer("Past earnings dates to include (0-40, default 8)")), List.of("ticker")),
                        Duration.ofHours(6), this::earnings,
                        a -> "Reading ORATS earnings data for " + a.path("ticker").asText("") + "…"),
                new ConnectorTool(ID + "_iv_rank_history",
                        "Daily history of IV and IV rank/percentile for a ticker over a date range (end-of-day), oldest first.",
                        object(props(
                                "ticker", str("Ticker symbol"),
                                "from", str("Start date YYYY-MM-DD (default: 90 days before 'to')"),
                                "to", str("End date YYYY-MM-DD (default: latest)"),
                                "limit", integer("Max days (1-260, default 90)")), List.of("ticker")),
                        Duration.ofHours(6), this::ivRankHistory,
                        a -> "Reading ORATS IV rank history for " + a.path("ticker").asText("") + "…"));
    }

    // ── tools ────────────────────────────────────────────────────────────────

    Object ivSummary(ConnectorContext ctx, JsonNode args) {
        Set<String> tickers = strSet(args, "tickers", true);
        if (tickers.isEmpty()) throw ConnectorException.badRequest("tickers is required");
        if (tickers.size() > 10) throw ConnectorException.badRequest("tickers: at most 10 (ORATS limit)");
        for (String t : tickers) if (!t.matches("^[A-Z0-9.^/-]{1,12}$")) throw ConnectorException.badRequest("tickers must look like AAPL");
        String csv = String.join(",", tickers);
        Map<String, Map<String, Object>> by = new LinkedHashMap<>();
        for (String t : tickers) by.put(t, null);
        for (JsonNode r : data(get(ctx, "/datav2/summaries", "ticker", csv, "fields", SUMMARY_FIELDS))) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("ticker", r.path("ticker").asText());
            m.put("trade_date", text(r, "tradeDate"));
            m.put("stock_price", num(r, "stockPrice"));
            m.put("iv10d", num(r, "iv10d"));
            m.put("iv20d", num(r, "iv20d"));
            m.put("iv30d", num(r, "iv30d"));
            m.put("iv60d", num(r, "iv60d"));
            m.put("iv90d", num(r, "iv90d"));
            m.put("iv6m", num(r, "iv6m"));
            m.put("iv1y", num(r, "iv1y"));
            m.put("iv30d_ex_earnings", num(r, "exErnIv30d"));
            m.put("realized_vol30", num(r, "rVol30"));
            m.put("implied_earnings_move", num(r, "impliedMove"));
            m.put("earnings_effect", num(r, "ieeEarnEffect"));
            m.put("skewing", num(r, "skewing"));
            m.put("contango", num(r, "contango"));
            m.put("confidence", num(r, "confidence"));
            m.put("updated_at", text(r, "updatedAt"));
            by.put(r.path("ticker").asText().toUpperCase(Locale.ROOT), m);
        }
        for (JsonNode r : data(get(ctx, "/datav2/ivrank", "ticker", csv, "fields", RANK_FIELDS))) {
            Map<String, Object> m = by.get(r.path("ticker").asText().toUpperCase(Locale.ROOT));
            if (m == null) continue;
            m.put("iv_rank_1m", num(r, "ivRank1m"));
            m.put("iv_pct_1m", num(r, "ivPct1m"));
            m.put("iv_rank_1y", num(r, "ivRank1y"));
            m.put("iv_pct_1y", num(r, "ivPct1y"));
            m.put("iv_rank_updated_at", text(r, "updatedAt"));
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        List<String> missing = new ArrayList<>();
        by.forEach((t, m) -> {
            if (m == null) missing.add(t);
            else rows.add(m);
        });
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("count", rows.size());
        out.put("tickers", rows);
        if (!missing.isEmpty()) out.put("not_found", missing);
        out.put("note", "Vols are annualised decimals (0.25 = 25%); implied_earnings_move is a fraction of the stock price.");
        return out;
    }

    Object termStructure(ConnectorContext ctx, JsonNode args) {
        String ticker = ticker(args, "ticker");
        int limit = intArg(args, "limit", 12, 1, 24);
        List<Map<String, Object>> rows = new ArrayList<>();
        String tradeDate = null;
        for (JsonNode r : data(get(ctx, "/datav2/monies/implied", "ticker", ticker, "fields", TERM_FIELDS))) {
            tradeDate = text(r, "tradeDate");
            Map<String, Object> m = new LinkedHashMap<>();
            String exp = text(r, "expirDate");
            m.put("expiry", exp);
            m.put("dte", days(tradeDate, exp));
            m.put("atm_iv", num(r, "atmiv"));
            m.put("smoothed_iv", num(r, "calVol"));
            m.put("smoothed_iv_ex_earnings", num(r, "unadjVol"));
            m.put("earnings_effect", num(r, "earnEffect"));
            m.put("skew_slope", num(r, "slope"));
            m.put("iv_25_delta", num(r, "vol25"));
            m.put("iv_50_delta", num(r, "vol50"));
            m.put("iv_75_delta", num(r, "vol75"));
            m.put("confidence", num(r, "confidence"));
            m.put("updated_at", text(r, "updatedAt"));
            rows.add(m);
        }
        rows.sort(Comparator.comparing(m -> String.valueOf(m.get("expiry"))));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ticker", ticker);
        out.put("trade_date", tradeDate);
        out.put("count", Math.min(limit, rows.size()));
        out.put("expiries", rows.subList(0, Math.min(limit, rows.size())));
        return out;
    }

    Object earnings(ConnectorContext ctx, JsonNode args) {
        String ticker = ticker(args, "ticker");
        int history = intArg(args, "history", 8, 0, 40);
        List<JsonNode> core = data(get(ctx, "/datav2/cores", "ticker", ticker, "fields", EARN_FIELDS));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ticker", ticker);
        if (!core.isEmpty()) {
            JsonNode r = core.get(0);
            out.put("trade_date", text(r, "tradeDate"));
            out.put("last_earnings", text(r, "lastErn"));
            out.put("next_earnings", text(r, "nextErn"));
            out.put("implied_move_pct", firstNum(r, "impErnMv", "impliedEarningsMove"));
            out.put("avg_abs_move_pct", num(r, "absAvgErnMv"));
            out.put("move_stdev_pct", num(r, "ernMvStdv"));
            List<Double> last = new ArrayList<>();
            for (String f : List.of("ernMv1", "ernMv2", "ernMv3", "ernMv4")) last.add(num(r, f));
            out.put("last_four_moves_pct", last);
            out.put("implied_earnings_effect", num(r, "impliedIee"));
            out.put("iv_earnings_return", num(r, "ivEarnReturn"));
            out.put("updated_at", text(r, "updatedAt"));
        }
        if (history > 0) {
            List<Map<String, Object>> dates = new ArrayList<>();
            for (JsonNode r : data(get(ctx, "/datav2/hist/earnings", "ticker", ticker))) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("date", text(r, "earnDate"));
                m.put("announce_time", announce(text(r, "anncTod")));
                dates.add(m);
            }
            dates.sort(Comparator.comparing((Map<String, Object> m) -> String.valueOf(m.get("date"))).reversed());
            out.put("past_earnings", dates.subList(0, Math.min(history, dates.size())));
        }
        out.put("note", "Moves are percentages of the stock price. announce_time is US Eastern (e.g. 16:30 = after the close).");
        return out;
    }

    Object ivRankHistory(ConnectorContext ctx, JsonNode args) {
        String ticker = ticker(args, "ticker");
        LocalDate to = date(args, "to");
        LocalDate from = date(args, "from");
        int limit = intArg(args, "limit", 90, 1, 260);
        if (from != null && to != null && from.isAfter(to)) throw ConnectorException.badRequest("from must be on or before to");
        List<Map<String, Object>> rows = new ArrayList<>();
        for (JsonNode r : data(get(ctx, "/datav2/hist/ivrank", "ticker", ticker, "fields", RANK_FIELDS))) {
            String d = text(r, "tradeDate");
            if (d == null) continue;
            LocalDate day;
            try {
                day = LocalDate.parse(d.substring(0, 10));
            } catch (Exception e) {
                continue;
            }
            if (to != null && day.isAfter(to)) continue;
            if (from != null && day.isBefore(from)) continue;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("date", day.toString());
            m.put("iv", num(r, "iv"));
            m.put("iv_rank_1m", num(r, "ivRank1m"));
            m.put("iv_pct_1m", num(r, "ivPct1m"));
            m.put("iv_rank_1y", num(r, "ivRank1y"));
            m.put("iv_pct_1y", num(r, "ivPct1y"));
            rows.add(m);
        }
        rows.sort(Comparator.comparing(m -> String.valueOf(m.get("date"))));
        if (from == null && !rows.isEmpty()) {
            LocalDate end = LocalDate.parse((String) rows.get(rows.size() - 1).get("date"));
            LocalDate start = end.minusDays(90);
            rows.removeIf(m -> LocalDate.parse((String) m.get("date")).isBefore(start));
        }
        List<Map<String, Object>> tail = rows.subList(Math.max(0, rows.size() - limit), rows.size());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ticker", ticker);
        out.put("count", tail.size());
        out.put("days", tail);
        out.put("note", "iv is in vol points (18.3 = 18.3%); rank/percentile 0-100.");
        return out;
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private JsonNode get(ConnectorContext ctx, String path, String... query) {
        Object[] kv = new Object[query.length + 2];
        kv[0] = "token";
        kv[1] = ctx.require("token");
        System.arraycopy(query, 0, kv, 2, query.length);
        JsonNode r = ctx.getJson(URI.create(url(base + path, kv)), Map.of());
        // ORATS errors are {"message": "..."}; guard against one arriving with a 2xx.
        if (r.isObject() && !r.has("data") && r.hasNonNull("message")) {
            String msg = r.path("message").asText();
            String lower = msg.toLowerCase(Locale.ROOT);
            boolean auth = lower.contains("token") || lower.contains("authoriz") || lower.contains("forbidden");
            throw new ConnectorException(auth ? ConnectorException.Kind.AUTH : ConnectorException.Kind.UPSTREAM, "ORATS: " + msg);
        }
        return r;
    }

    static List<JsonNode> data(JsonNode n) {
        List<JsonNode> out = new ArrayList<>();
        JsonNode arr = n == null ? null : n.isArray() ? n : n.path("data");
        if (arr != null && arr.isArray()) arr.forEach(out::add);
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
                return Double.parseDouble(n.asText().strip());
            } catch (Exception e) {
                return null;
            }
        }
        return null;
    }

    static Double firstNum(JsonNode r, String... fields) {
        for (String f : fields) {
            Double v = num(r, f);
            if (v != null) return v;
        }
        return null;
    }

    static Long days(String from, String to) {
        try {
            return from == null || to == null ? null : ChronoUnit.DAYS.between(LocalDate.parse(from.substring(0, 10)), LocalDate.parse(to.substring(0, 10)));
        } catch (Exception e) {
            return null;
        }
    }

    /** ORATS announcement time-of-day "1630" → "16:30". */
    static String announce(String tod) {
        if (tod == null || !tod.matches("^\\d{3,4}$")) return tod;
        String t = tod.length() == 3 ? "0" + tod : tod;
        return t.substring(0, 2) + ":" + t.substring(2);
    }
}
