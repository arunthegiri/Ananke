package com.stocktracker.connectors.treasury;

import com.fasterxml.jackson.databind.JsonNode;
import com.stocktracker.connectors.Connector;
import com.stocktracker.connectors.ConnectorContext;
import com.stocktracker.connectors.ConnectorException;
import com.stocktracker.connectors.ConnectorSpec;
import com.stocktracker.connectors.ConnectorSpec.RateLimit;
import com.stocktracker.connectors.ConnectorTool;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import static com.stocktracker.connectors.ToolArgs.date;
import static com.stocktracker.connectors.ToolArgs.enumStr;
import static com.stocktracker.connectors.ToolArgs.intArg;
import static com.stocktracker.connectors.ToolArgs.integer;
import static com.stocktracker.connectors.ToolArgs.object;
import static com.stocktracker.connectors.ToolArgs.oneOf;
import static com.stocktracker.connectors.ToolArgs.optStr;
import static com.stocktracker.connectors.ToolArgs.props;
import static com.stocktracker.connectors.ToolArgs.str;
import static com.stocktracker.connectors.ToolArgs.strArray;
import static com.stocktracker.connectors.ToolArgs.strSet;
import static com.stocktracker.connectors.ToolArgs.url;

/**
 * U.S. Treasury: the daily par yield curve (home.treasury.gov CSV) and four FiscalData datasets —
 * debt to the penny, the Daily Treasury Statement cash balance (TGA), auction results, and the
 * average interest rate on the debt. Keyless.
 *
 * <p>Point-in-time: yield curve rates are end-of-day (~3:30 PM ET indications) published the same
 * evening; the DTS and debt to the penny for day D are published the next business day; auction
 * results at the auction close. Each tool says which.
 */
@Component
public class TreasuryConnector implements Connector {

    static final String ID = "treasury";
    static final String FISCAL_URL = "https://api.fiscaldata.treasury.gov/services/api/fiscal_service";
    static final String RATES_URL = "https://home.treasury.gov/resource-center/data-chart-center/interest-rates/daily-treasury-rates.csv";

    static final String TGA_CLOSING = "Treasury General Account (TGA) Closing Balance";
    static final DateTimeFormatter US_DATE = DateTimeFormatter.ofPattern("MM/dd/yyyy");

    private static final ConnectorSpec SPEC = new ConnectorSpec(
            ID,
            "U.S. Treasury (yield curve & Fiscal Data)",
            ConnectorSpec.Category.MACRO,
            ConnectorSpec.Licence.FREE_PUBLIC,
            "Official daily Treasury par yield curve (nominal and real/TIPS, 1-month to 30-year), Treasury auction "
                    + "results and upcoming auctions (yields, bid-to-cover, dealer/direct/indirect takedown), the Treasury "
                    + "General Account cash balance (liquidity drain/add), total public debt and the average interest rate "
                    + "on the debt. For curve shape, funding stress, auction demand and fiscal-liquidity calls.",
            "Source: U.S. Department of the Treasury (Daily Treasury Par Yield Curve Rates; Fiscal Data, fiscaldata.treasury.gov)",
            "https://fiscaldata.treasury.gov/api-documentation/",
            List.of(),
            List.of(RateLimit.perSecond(4), RateLimit.perMinute(120)));   // no published limit; stay polite

    private final String fiscal;
    private final String rates;
    private final Clock clock;

    public TreasuryConnector() {
        this(FISCAL_URL, RATES_URL, Clock.systemUTC());
    }

    /** Test seam: point at a fake Treasury. */
    TreasuryConnector(String fiscal, String rates, Clock clock) {
        this.fiscal = fiscal;
        this.rates = rates;
        this.clock = clock;
    }

    @Override
    public ConnectorSpec spec() {
        return SPEC;
    }

    @Override
    public String test(ConnectorContext ctx) {
        // The yield curve host is the steadier of the two; Fiscal Data has intermittent outages.
        LocalDate today = LocalDate.now(clock);
        TreeMap<LocalDate, Map<String, Object>> rows = new TreeMap<>();
        String csv = ctx.get(URI.create(url(rates + "/" + today.getYear() + "/all", "type", "daily_treasury_yield_curve",
                "field_tdr_date_value", today.getYear(), "page", "", "_format", "csv")), Map.of(), "text/csv");
        parseCurve(csv, today.withDayOfYear(1), today, Set.of("10Y"), rows);
        return rows.isEmpty()
                ? "Connected — Treasury answered (no yield curve rows yet this year)."
                : "Connected — Treasury answered (10Y par yield " + rows.lastEntry().getValue().get("10Y") + "% on " + rows.lastKey() + ").";
    }

    @Override
    public List<ConnectorTool> tools() {
        return List.of(
                new ConnectorTool(ID + "_yield_curve",
                        "Daily Treasury par yield curve rates (official CMT curve), newest first, in percent. nominal: 1M…30Y; "
                                + "real: TIPS 5Y…30Y. Adds the 10Y-2Y and 10Y-3M spreads for the nominal curve.",
                        object(props(
                                "curve", enumStr("nominal (default) or real (TIPS)", List.of("nominal", "real")),
                                "start", str("From date YYYY-MM-DD (default 30 days before end)"),
                                "end", str("To date YYYY-MM-DD (default today)"),
                                "tenors", strArray("Only these tenors, e.g. [\"3M\",\"2Y\",\"10Y\",\"30Y\"]"),
                                "limit", integer("Max days (1-260, default 30)")), List.of()),
                        Duration.ofHours(1), this::yieldCurve,
                        a -> "Reading the Treasury yield curve…"),
                new ConnectorTool(ID + "_auctions",
                        "Treasury auctions, newest first: results (high yield/rate, coupon, bid-to-cover, amount, and the "
                                + "share taken by primary dealers vs direct vs indirect bidders — weak indirect demand and a "
                                + "big dealer takedown signal a soft auction) or upcoming announced auctions. Covers 2026 onward.",
                        object(props(
                                "status", enumStr("results (default) or upcoming", List.of("results", "upcoming")),
                                "security", str("Filter by type or term, e.g. Bill, Note, Bond, TIPS, FRN, \"10-Year\""),
                                "limit", integer("Max auctions (1-40, default 10)")), List.of()),
                        Duration.ofMinutes(30), this::auctions,
                        a -> "Reading Treasury auctions…"),
                new ConnectorTool(ID + "_cash_balance",
                        "Treasury General Account (TGA) closing balance from the Daily Treasury Statement, newest first, "
                                + "with day-over-day change ($ millions). A rising TGA drains bank reserves/liquidity; a "
                                + "falling one adds it. Published ~4 PM ET the next business day.",
                        object(props("days", integer("Business days (1-60, default 10)")), List.of()),
                        Duration.ofHours(1), this::cashBalance,
                        a -> "Reading the Daily Treasury Statement…"),
                new ConnectorTool(ID + "_debt",
                        "Total public debt outstanding (debt to the penny), debt held by the public and intragovernmental "
                                + "holdings, newest first, with the change since the prior record.",
                        object(props("days", integer("Business days (1-90, default 10)")), List.of()),
                        Duration.ofHours(3), this::debt,
                        a -> "Reading debt to the penny…"),
                new ConnectorTool(ID + "_avg_interest_rates",
                        "Average interest rate on Treasury debt by security (bills, notes, bonds, TIPS, FRN, total "
                                + "marketable…), month-end, newest month first. Tracks the government's rising/falling cost of debt.",
                        object(props(
                                "months", integer("Months (1-24, default 3)"),
                                "type", enumStr("Marketable (default), Non-marketable or all", List.of("Marketable", "Non-marketable", "all"))),
                                List.of()),
                        Duration.ofHours(12), this::avgInterestRates,
                        a -> "Reading average interest rates on Treasury debt…"));
    }

    // ── yield curve ──────────────────────────────────────────────────────────

    Object yieldCurve(ConnectorContext ctx, JsonNode args) {
        String curve = oneOf(args, "curve", "nominal", List.of("nominal", "real"));
        LocalDate end = date(args, "end");
        if (end == null) end = LocalDate.now(clock);
        LocalDate start = date(args, "start");
        if (start == null) start = end.minusDays(30);
        if (start.isAfter(end)) throw ConnectorException.badRequest("start is after end");
        if (end.getYear() - start.getYear() > 4) throw ConnectorException.badRequest("Ask for at most 5 calendar years at a time");
        if (start.getYear() < 1990) throw ConnectorException.badRequest("Par yield curve data starts in 1990");
        int limit = intArg(args, "limit", 30, 1, 260);
        Set<String> want = new java.util.LinkedHashSet<>();
        for (String t : strSet(args, "tenors", true)) want.add(tenor(t));
        String type = curve.equals("real") ? "daily_treasury_real_yield_curve" : "daily_treasury_yield_curve";

        TreeMap<LocalDate, Map<String, Object>> byDate = new TreeMap<>();
        for (int y = end.getYear(); y >= start.getYear(); y--) {
            String csv = ctx.get(URI.create(url(rates + "/" + y + "/all", "type", type, "field_tdr_date_value", y, "page", "", "_format", "csv")),
                    Map.of(), "text/csv");
            parseCurve(csv, start, end, want, byDate);
            if (byDate.size() >= limit) break;
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map.Entry<LocalDate, Map<String, Object>> e : byDate.descendingMap().entrySet()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("date", e.getKey().toString());
            m.put("yields", e.getValue());
            if (curve.equals("nominal")) {
                Map<String, Object> all = e.getValue();
                Object s2 = spread(all.get("10Y"), all.get("2Y"));
                Object s3 = spread(all.get("10Y"), all.get("3M"));
                if (s2 != null) m.put("spread_10y_2y", s2);
                if (s3 != null) m.put("spread_10y_3m", s3);
            }
            rows.add(m);
            if (rows.size() >= limit) break;
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("curve", curve);
        out.put("units", "percent");
        out.put("count", rows.size());
        out.put("rows", rows);
        out.put("note", "Par yields from indicative bid-side quotes near 3:30 PM ET; published the same business day evening.");
        return out;
    }

    /** Adds rows in [start, end] from one year's CSV; columns differ between years, so the header decides. */
    static void parseCurve(String csv, LocalDate start, LocalDate end, Set<String> want, Map<LocalDate, Map<String, Object>> into) {
        if (csv == null || csv.isBlank()) return;
        String[] lines = csv.split("\\r?\\n");
        String[] head = splitCsv(lines[0]);
        if (head.length < 2 || !head[0].equalsIgnoreCase("Date")) {
            throw ConnectorException.upstream("Treasury yield curve file has an unexpected layout.");
        }
        String[] keys = new String[head.length];
        for (int i = 1; i < head.length; i++) keys[i] = tenor(head[i]);
        for (int l = 1; l < lines.length; l++) {
            String[] c = splitCsv(lines[l]);
            if (c.length < 2) continue;
            LocalDate d;
            try {
                d = LocalDate.parse(c[0], US_DATE);
            } catch (Exception e) {
                continue;
            }
            if (d.isBefore(start) || d.isAfter(end)) continue;
            Map<String, Object> y = new LinkedHashMap<>();
            for (int i = 1; i < c.length && i < keys.length; i++) {
                if (!want.isEmpty() && !want.contains(keys[i])) continue;
                Number v = num(c[i]);
                if (v != null) y.put(keys[i], v);
            }
            into.put(d, y);
        }
    }

    /** "1 Mo" → 1M, "1.5 Month" → 1.5M, "10 Yr"/"10 YR"/"10y" → 10Y, "13 weeks" → 13W. */
    static String tenor(String raw) {
        String s = raw.replace("\"", "").strip().toUpperCase(Locale.ROOT);
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("^([0-9.]+)\\s*([A-Z]+)$").matcher(s);
        if (!m.matches()) return s;
        String u = m.group(2);
        String unit = u.startsWith("M") ? "M" : u.startsWith("Y") ? "Y" : u.startsWith("W") ? "W" : u;
        return m.group(1) + unit;
    }

    private static String[] splitCsv(String line) {
        String[] parts = line.split(",", -1);
        for (int i = 0; i < parts.length; i++) parts[i] = parts[i].replace("\"", "").strip();
        return parts;
    }

    private static Object spread(Object a, Object b) {
        if (!(a instanceof Number x) || !(b instanceof Number y)) return null;
        return BigDecimal.valueOf(x.doubleValue()).subtract(BigDecimal.valueOf(y.doubleValue())).setScale(2, RoundingMode.HALF_UP).doubleValue();
    }

    // ── Fiscal Data ──────────────────────────────────────────────────────────

    Object auctions(ConnectorContext ctx, JsonNode args) {
        String status = oneOf(args, "status", "results", List.of("results", "upcoming"));
        String security = optStr(args, "security");
        if (security != null && (security.length() > 40 || !security.matches("^[A-Za-z0-9 .-]+$"))) {
            throw ConnectorException.badRequest("security must be a type or term like Note, TIPS or 10-Year");
        }
        int limit = intArg(args, "limit", 10, 1, 40);
        String today = LocalDate.now(clock).toString();
        JsonNode r = status.equals("results")
                ? fiscalGet(ctx, "/v1/accounting/od/treasury_securities_auctions_v2",
                        "filter", "comp_auction_close_date:lte:" + today, "sort", "-comp_auction_close_date", "page[size]", 200)
                : fiscalGet(ctx, "/v1/accounting/od/treasury_securities_auctions_v2",
                        "filter", "comp_auction_close_date:gte:" + today, "sort", "comp_auction_close_date", "page[size]", 200);
        String needle = security == null ? null : security.toLowerCase(Locale.ROOT).replace("-", " ");
        List<Map<String, Object>> rows = new ArrayList<>();
        for (JsonNode a : r.path("data")) {
            String type = text(a, "security_type");
            String term = text(a, "security_term");
            String desc = text(a, "security_desc");
            if (needle != null) {
                String hay = (type + " " + term + " " + desc).toLowerCase(Locale.ROOT).replace("-", " ");
                if (!hay.contains(needle)) continue;
            }
            boolean done = num(text(a, "bid_to_cover_ratio")) != null;
            if (status.equals("results") && !done) continue;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("security", desc != null ? desc : (term + " " + type));
            m.put("type", type);
            m.put("term", term);
            m.put("cusip", text(a, "cusip"));
            m.put("reopening", "Yes".equalsIgnoreCase(text(a, "reopening")));
            m.put("announced", text(a, "announcemt_date"));
            m.put("auction_date", text(a, "comp_auction_close_date"));
            m.put("auction_close_et", text(a, "comp_auction_close_time"));
            m.put("issue_date", text(a, "issue_date"));
            m.put("maturity_date", text(a, "maturity_date"));
            m.put("offering_amt", num(text(a, "offering_amt")));
            if (done) {
                Number hy = num(text(a, "high_yield"));
                Number hr = num(text(a, "high_rate"));
                Number hdm = num(text(a, "high_discnt_margin"));
                if (hy != null) m.put("high_yield", hy);
                if (hr != null) m.put("high_rate", hr);
                if (hdm != null) m.put("high_discount_margin", hdm);
                Number coupon = num(text(a, "int_rate"));
                if (coupon != null) m.put("coupon", coupon);
                Number inv = num(text(a, "investment_rate"));
                if (inv != null) m.put("investment_rate", inv);
                m.put("bid_to_cover", num(text(a, "bid_to_cover_ratio")));
                m.put("total_awarded", num(text(a, "grand_total_par_awarded")));
                m.put("total_tendered", num(text(a, "grand_total_par_recog")));
                Number pd = num(text(a, "prim_deal_comp_par_awarded"));
                Number di = num(text(a, "dir_bid_comp_par_awarded"));
                Number ind = num(text(a, "indir_bid_comp_par_awarded"));
                if (pd != null && di != null && ind != null) {
                    double comp = pd.doubleValue() + di.doubleValue() + ind.doubleValue();
                    if (comp > 0) {
                        m.put("primary_dealer_pct", pct(pd.doubleValue(), comp));
                        m.put("direct_pct", pct(di.doubleValue(), comp));
                        m.put("indirect_pct", pct(ind.doubleValue(), comp));
                    }
                }
            }
            rows.add(m);
            if (rows.size() >= limit) break;
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("status", status);
        if (security != null) out.put("security", security);
        out.put("count", rows.size());
        out.put("auctions", rows);
        out.put("note", "Amounts in dollars; yields/rates in percent. Results are public at the auction close "
                + "(auction_close_et, Eastern) on auction_date. *_pct = share of competitive awards.");
        return out;
    }

    Object cashBalance(ConnectorContext ctx, JsonNode args) {
        int days = intArg(args, "days", 10, 1, 60);
        JsonNode r = fiscalGet(ctx, "/v1/accounting/dts/operating_cash_balance",
                "filter", "account_type:eq:" + TGA_CLOSING, "sort", "-record_date", "page[size]", days + 1);
        List<Map<String, Object>> rows = new ArrayList<>();
        List<JsonNode> data = new ArrayList<>();
        r.path("data").forEach(data::add);
        for (int i = 0; i < data.size() && rows.size() < days; i++) {
            JsonNode d = data.get(i);
            Number bal = tgaBalance(d);
            Map<String, Object> m = new LinkedHashMap<>();
            String date = text(d, "record_date");
            m.put("date", date);
            m.put("published_est", date == null ? null : nextBusinessDay(LocalDate.parse(date)).toString());
            m.put("tga_closing_balance", bal);
            if (i + 1 < data.size()) {
                Number prev = tgaBalance(data.get(i + 1));
                if (bal != null && prev != null) m.put("change", bal.longValue() - prev.longValue());
            }
            rows.add(m);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("units", "$ millions");
        out.put("count", rows.size());
        out.put("rows", rows);
        out.put("note", "Daily Treasury Statement for 'date' is published ~4 PM ET the next business day (published_est "
                + "ignores federal holidays). Use published_est, not date, for point-in-time backtests.");
        return out;
    }

    /** The DTS puts the TGA closing balance in open_today_bal (close_today_bal is "null") since the 2021 format change. */
    private static Number tgaBalance(JsonNode d) {
        Number close = num(text(d, "close_today_bal"));
        return close != null ? close : num(text(d, "open_today_bal"));
    }

    Object debt(ConnectorContext ctx, JsonNode args) {
        int days = intArg(args, "days", 10, 1, 90);
        JsonNode r = fiscalGet(ctx, "/v2/accounting/od/debt_to_penny", "sort", "-record_date", "page[size]", days + 1);
        List<JsonNode> data = new ArrayList<>();
        r.path("data").forEach(data::add);
        List<Map<String, Object>> rows = new ArrayList<>();
        for (int i = 0; i < data.size() && rows.size() < days; i++) {
            JsonNode d = data.get(i);
            Map<String, Object> m = new LinkedHashMap<>();
            String date = text(d, "record_date");
            m.put("date", date);
            m.put("published_est", date == null ? null : nextBusinessDay(LocalDate.parse(date)).toString());
            Number total = num(text(d, "tot_pub_debt_out_amt"));
            m.put("total_public_debt", total);
            m.put("held_by_public", num(text(d, "debt_held_public_amt")));
            m.put("intragovernmental", num(text(d, "intragov_hold_amt")));
            if (i + 1 < data.size()) {
                Number prev = num(text(data.get(i + 1), "tot_pub_debt_out_amt"));
                if (total != null && prev != null) {
                    m.put("change_total", new BigDecimal(total.toString()).subtract(new BigDecimal(prev.toString())).doubleValue());
                }
            }
            rows.add(m);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("units", "dollars");
        out.put("count", rows.size());
        out.put("rows", rows);
        out.put("note", "Debt for 'date' is published the next business day (published_est ignores holidays).");
        return out;
    }

    Object avgInterestRates(ConnectorContext ctx, JsonNode args) {
        int months = intArg(args, "months", 3, 1, 24);
        String type = oneOf(args, "type", "Marketable", List.of("Marketable", "Non-marketable", "all"));
        LocalDate since = LocalDate.now(clock).withDayOfMonth(1).minusMonths(months + 1L);
        String filter = "record_date:gte:" + since + (type.equals("all") ? "" : ",security_type_desc:eq:" + type);
        JsonNode r = fiscalGet(ctx, "/v2/accounting/od/avg_interest_rates", "filter", filter,
                "sort", "-record_date,src_line_nbr", "page[size]", 600);
        Map<String, Map<String, Object>> byDate = new LinkedHashMap<>();
        for (JsonNode d : r.path("data")) {
            String date = text(d, "record_date");
            if (!byDate.containsKey(date) && byDate.size() >= months) break;
            Map<String, Object> rates = byDate.computeIfAbsent(date, k -> new LinkedHashMap<>());
            String desc = text(d, "security_desc");
            if (type.equals("all")) desc = text(d, "security_type_desc") + ": " + desc;
            rates.put(desc, num(text(d, "avg_interest_rate_amt")));
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        byDate.forEach((date, rates) -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("month_end", date);
            m.put("rates", rates);
            rows.add(m);
        });
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("type", type);
        out.put("units", "percent");
        out.put("months", rows);
        out.put("note", "Month-end values, published early in the following month.");
        return out;
    }

    /**
     * One Fiscal Data GET. Fiscal Data intermittently answers 503 with an outage page; up to two
     * retries with a short backoff smooth that over (each attempt counts a permit).
     */
    JsonNode fiscalGet(ConnectorContext ctx, String path, Object... kv) {
        URI uri = URI.create(url(fiscal + path, kv));
        long[] backoffMs = {500, 1500};
        for (int attempt = 0; ; attempt++) {
            try {
                return ctx.getJson(uri);
            } catch (ConnectorException e) {
                if (e.kind() != ConnectorException.Kind.UPSTREAM) throw e;
                if (attempt >= backoffMs.length) {
                    throw ConnectorException.upstream("Treasury Fiscal Data is unavailable right now (it has intermittent "
                            + "outages); try again in a few minutes.");
                }
                try {
                    Thread.sleep(backoffMs[attempt]);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw e;
                }
            }
        }
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    static LocalDate nextBusinessDay(LocalDate d) {
        LocalDate n = d.plusDays(1);
        while (n.getDayOfWeek() == DayOfWeek.SATURDAY || n.getDayOfWeek() == DayOfWeek.SUNDAY) n = n.plusDays(1);
        return n;
    }

    private static String text(JsonNode n, String field) {
        JsonNode v = n.get(field);
        if (v == null || v.isNull()) return null;
        String s = v.asText().strip();
        return s.isEmpty() || s.equalsIgnoreCase("null") ? null : s;
    }

    private static double pct(double part, double whole) {
        return BigDecimal.valueOf(100 * part / whole).setScale(1, RoundingMode.HALF_UP).doubleValue();
    }

    /** Numeric strings ("4.10", "32824386143424.80", "1,234") → number; blanks, N/A and "null" → null. */
    static Number num(String s) {
        if (s == null) return null;
        String t = s.replace(",", "").strip();
        if (t.isEmpty() || t.equalsIgnoreCase("null") || t.equalsIgnoreCase("N/A")) return null;
        try {
            BigDecimal d = new BigDecimal(t);
            return d.stripTrailingZeros().scale() <= 0 && d.abs().compareTo(BigDecimal.valueOf(Long.MAX_VALUE)) < 0
                    ? (Number) d.longValue() : (Number) d.doubleValue();
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
