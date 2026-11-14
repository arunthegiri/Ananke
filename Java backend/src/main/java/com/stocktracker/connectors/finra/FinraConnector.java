package com.stocktracker.connectors.finra;

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
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import static com.stocktracker.connectors.ToolArgs.date;
import static com.stocktracker.connectors.ToolArgs.intArg;
import static com.stocktracker.connectors.ToolArgs.integer;
import static com.stocktracker.connectors.ToolArgs.object;
import static com.stocktracker.connectors.ToolArgs.props;
import static com.stocktracker.connectors.ToolArgs.str;
import static com.stocktracker.connectors.ToolArgs.strArray;
import static com.stocktracker.connectors.ToolArgs.strSet;
import static com.stocktracker.connectors.ToolArgs.ticker;

/**
 * FINRA short data: Reg SHO daily short-sale volume (off-exchange trades reported to FINRA's TRFs
 * and ORF) and semi-monthly equity short interest. The public datasets of FINRA's Query API answer
 * without credentials today; optional API client credentials are used when linked (FINRA documents
 * authentication as required for data endpoints and may enforce it).
 *
 * <p>Point-in-time: daily short volume for trade date D is posted the same evening (~6 PM ET); short
 * interest for a settlement date is published about seven business days later per FINRA's calendar.
 */
@Component
public class FinraConnector implements Connector {

    static final String ID = "finra";
    static final String API_URL = "https://api.finra.org";
    static final String CDN_URL = "https://cdn.finra.org";
    static final String TOKEN_URL = "https://ews.fip.finra.org/fip/rest/ews/oauth2/access_token?grant_type=client_credentials";
    static final DateTimeFormatter BASIC = DateTimeFormatter.BASIC_ISO_DATE;

    private static final ConnectorSpec SPEC = new ConnectorSpec(
            ID,
            "FINRA short data",
            ConnectorSpec.Category.ALT_DATA,
            ConnectorSpec.Licence.FREE_PUBLIC,
            "FINRA short-sale data for U.S. equities: daily Reg SHO short-sale volume (off-exchange, per symbol and the "
                    + "whole market's most-shorted names) and twice-monthly short interest with days to cover. For squeeze "
                    + "risk, crowded shorts and short-pressure signals. Published free by FINRA; for personal/internal use, "
                    + "not redistribution.",
            "Source: FINRA (Financial Industry Regulatory Authority) — Reg SHO daily short sale volume and equity short interest",
            "https://developer.finra.org/docs",
            List.of(
                    new Field("client_id", "FINRA API client ID (optional)",
                            "Optional. Short data works without an account today. If FINRA starts requiring it, create a free "
                                    + "API client at developer.finra.org (FINRA Gateway) and enter its ID and secret.", false, true, null),
                    new Field("client_secret", "FINRA API client secret (optional)",
                            "Optional; only with the client ID.", true, true, null)),
            List.of(RateLimit.perSecond(8), RateLimit.perMinute(600)));   // FINRA: 1,200 sync requests a minute per IP

    private final String api;
    private final String cdn;
    private final String tokenUrl;
    private final Clock clock;
    private volatile String token;
    private volatile String tokenFor;
    private volatile Instant tokenExpires = Instant.EPOCH;

    public FinraConnector() {
        this(API_URL, CDN_URL, TOKEN_URL, Clock.systemUTC());
    }

    /** Test seam: point at a fake FINRA. */
    FinraConnector(String api, String cdn, String tokenUrl, Clock clock) {
        this.api = api;
        this.cdn = cdn;
        this.tokenUrl = tokenUrl;
        this.clock = clock;
    }

    @Override
    public ConnectorSpec spec() {
        return SPEC;
    }

    @Override
    public String test(ConnectorContext ctx) {
        boolean creds = ctx.field("client_id").isPresent();
        LocalDate today = LocalDate.now(clock);
        JsonNode r = dataset(ctx, "regShoDaily", List.of(eq("securitiesInformationProcessorSymbolIdentifier", "AAPL")),
                range("tradeReportDate", today.minusDays(10), today), 5);
        return "Connected — FINRA answered (" + r.size() + " recent AAPL short-volume rows; "
                + (creds ? "using your API client credentials" : "anonymous public access") + ").";
    }

    @Override
    public List<ConnectorTool> tools() {
        return List.of(
                new ConnectorTool(ID + "_short_volume",
                        "Daily Reg SHO short-sale volume for one stock, newest day first: short volume, short-exempt volume, "
                                + "total off-exchange volume and short ratio, summed across FINRA's trade reporting facilities. "
                                + "Off-exchange (dark pool/internalizer) volume only, and short VOLUME is not short INTEREST — a "
                                + "ratio near 50% is normal market making; watch changes vs the stock's own history.",
                        object(props(
                                "ticker", str("Ticker, e.g. AAPL or BRK.B"),
                                "days", integer("Trading days (1-60, default 10)")), List.of("ticker")),
                        Duration.ofHours(1), this::shortVolume,
                        a -> "Reading FINRA short volume for " + a.path("ticker").asText("") + "…"),
                new ConnectorTool(ID + "_short_volume_market",
                        "One day's consolidated Reg SHO short-sale volume file for the whole market: either the given "
                                + "tickers, or the most-shorted names by short ratio among liquid stocks.",
                        object(props(
                                "date", str("Trade date YYYY-MM-DD (default: latest published)"),
                                "tickers", strArray("Only these tickers (max 50)"),
                                "min_volume", integer("For the ranking: minimum total volume (default 1,000,000)"),
                                "limit", integer("For the ranking: top N (1-50, default 20)")), List.of()),
                        Duration.ofHours(1), this::shortVolumeMarket,
                        a -> "Reading FINRA's daily short volume file…"),
                new ConnectorTool(ID + "_short_interest",
                        "Equity short interest for one stock (FINRA, settlement dates mid- and end-of-month), newest first: "
                                + "shares short, change vs prior period, average daily volume and days to cover.",
                        object(props(
                                "ticker", str("Ticker, e.g. GME"),
                                "periods", integer("Settlement periods (1-24, default 6)")), List.of("ticker")),
                        Duration.ofHours(6), this::shortInterest,
                        a -> "Reading FINRA short interest for " + a.path("ticker").asText("") + "…"));
    }

    // ── tools ────────────────────────────────────────────────────────────────

    Object shortVolume(ConnectorContext ctx, JsonNode args) {
        String t = ticker(args, "ticker");
        String sym = t.replace('.', '/').replace('-', '/');
        int days = intArg(args, "days", 10, 1, 60);
        LocalDate today = LocalDate.now(clock);
        LocalDate from = today.minusDays(days * 7L / 5 + 7);
        JsonNode r = dataset(ctx, "regShoDaily", List.of(eq("securitiesInformationProcessorSymbolIdentifier", sym)),
                range("tradeReportDate", from, today), 5000);
        TreeMap<String, double[]> byDate = new TreeMap<>(Comparator.reverseOrder());
        for (JsonNode row : r) {
            double[] a = byDate.computeIfAbsent(row.path("tradeReportDate").asText(), k -> new double[3]);
            a[0] += row.path("shortParQuantity").asDouble();
            a[1] += row.path("shortExemptParQuantity").asDouble();
            a[2] += row.path("totalParQuantity").asDouble();
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map.Entry<String, double[]> e : byDate.entrySet()) {
            if (rows.size() >= days) break;
            rows.add(volumeRow(e.getKey(), e.getValue()[0], e.getValue()[1], e.getValue()[2]));
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ticker", t);
        out.put("days", rows);
        if (rows.isEmpty()) out.put("warning", "No FINRA short-sale volume for " + t + " in the period — check the ticker.");
        out.put("note", "Shares; FINRA TRF/ORF (off-exchange) trades only. Trade date D is published ~6 PM ET on D.");
        return out;
    }

    Object shortVolumeMarket(ConnectorContext ctx, JsonNode args) {
        LocalDate asked = date(args, "date");
        Set<String> tickers = strSet(args, "tickers", true);
        if (tickers.size() > 50) throw ConnectorException.badRequest("At most 50 tickers");
        Set<String> syms = new java.util.LinkedHashSet<>();
        for (String t : tickers) {
            if (!t.matches("^[A-Z0-9.^/-]{1,12}$")) throw ConnectorException.badRequest("Not a ticker: " + t);
            syms.add(t.replace('.', '/').replace('-', '/'));
        }
        long minVolume = Math.max(0, args == null || !args.hasNonNull("min_volume") ? 1_000_000L : args.path("min_volume").asLong(1_000_000L));
        int limit = intArg(args, "limit", 20, 1, 50);

        LocalDate day = asked != null ? asked : LocalDate.now(clock);
        String body = null;
        for (int tries = 0; tries < (asked != null ? 1 : 7) && body == null; tries++) {
            if (asked == null) {
                while (day.getDayOfWeek() == DayOfWeek.SATURDAY || day.getDayOfWeek() == DayOfWeek.SUNDAY) day = day.minusDays(1);
            }
            body = file(ctx, day);
            if (body == null && asked == null) day = day.minusDays(1);
        }
        if (body == null) {
            throw ConnectorException.badRequest(asked != null
                    ? "FINRA has no short-volume file for " + asked + " (weekend, holiday, or not yet published)."
                    : "No FINRA short-volume file found in the last week.");
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        List<double[]> ranking = new ArrayList<>();
        List<String> rankingSyms = new ArrayList<>();
        for (String line : body.split("\\r?\\n")) {
            String[] c = line.split("\\|");
            if (c.length < 5 || c[0].equals("Date")) continue;
            double shortVol, exempt, total;
            try {
                shortVol = Double.parseDouble(c[2]);
                exempt = Double.parseDouble(c[3]);
                total = Double.parseDouble(c[4]);
            } catch (NumberFormatException e) {
                continue;
            }
            if (!syms.isEmpty()) {
                if (syms.contains(c[1])) {
                    Map<String, Object> m = volumeRow(null, shortVol, exempt, total);
                    m.put("ticker", c[1]);
                    if (c.length > 5) m.put("markets", c[5]);
                    rows.add(m);
                }
            } else if (total >= minVolume && total > 0) {
                ranking.add(new double[] {shortVol / total, shortVol, exempt, total, rankingSyms.size()});
                rankingSyms.add(c[1]);
            }
        }
        if (syms.isEmpty()) {
            ranking.sort((a, b) -> Double.compare(b[0], a[0]));
            for (double[] x : ranking.subList(0, Math.min(limit, ranking.size()))) {
                Map<String, Object> m = volumeRow(null, x[1], x[2], x[3]);
                m.put("ticker", rankingSyms.get((int) x[4]));
                rows.add(m);
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("date", day.toString());
        if (syms.isEmpty()) {
            out.put("ranking", "highest short ratio, total volume >= " + minVolume);
            out.put("eligible_symbols", ranking.size());
        }
        out.put("rows", rows);
        out.put("note", "Consolidated FINRA file (all TRFs + ORF), off-exchange volume only, shares. Published ~6 PM ET on the trade date.");
        return out;
    }

    Object shortInterest(ConnectorContext ctx, JsonNode args) {
        String t = ticker(args, "ticker");
        String sym = t.replaceAll("[.\\-/]", "");
        int periods = intArg(args, "periods", 6, 1, 24);
        LocalDate today = LocalDate.now(clock);
        JsonNode r = dataset(ctx, "consolidatedShortInterest", List.of(eq("symbolCode", sym)),
                range("settlementDate", today.minusDays(periods * 16L + 20), today), 200);
        List<JsonNode> list = new ArrayList<>();
        r.forEach(list::add);
        list.sort((a, b) -> b.path("settlementDate").asText().compareTo(a.path("settlementDate").asText()));
        List<Map<String, Object>> rows = new ArrayList<>();
        String issue = null;
        for (JsonNode x : list) {
            if (rows.size() >= periods) break;
            if (issue == null) issue = x.path("issueName").asText(null);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("settlement_date", x.path("settlementDate").asText());
            m.put("short_interest", x.path("currentShortPositionQuantity").numberValue());
            m.put("previous", x.path("previousShortPositionQuantity").numberValue());
            m.put("change", x.path("changePreviousNumber").numberValue());
            m.put("change_pct", x.path("changePercent").numberValue());
            m.put("avg_daily_volume", x.path("averageDailyVolumeQuantity").numberValue());
            m.put("days_to_cover", x.path("daysToCoverQuantity").numberValue());
            if (x.hasNonNull("revisionFlag")) m.put("revised", x.path("revisionFlag").asText());
            if (x.hasNonNull("stockSplitFlag")) m.put("split", x.path("stockSplitFlag").asText());
            rows.add(m);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ticker", t);
        out.put("issue", issue);
        out.put("market", list.isEmpty() ? null : list.get(0).path("marketClassCode").asText(null));
        out.put("periods", rows);
        if (rows.isEmpty()) out.put("warning", "No FINRA short interest for " + t + " — check the ticker (class shares like BRK.B are BRKB).");
        out.put("note", "Shares short at settlement; published by FINRA about 7 business days after settlement_date "
                + "(see FINRA's short interest calendar) — never use settlement_date itself as the availability date.");
        return out;
    }

    // ── FINRA plumbing ───────────────────────────────────────────────────────

    private static Map<String, Object> volumeRow(String date, double shortVol, double exempt, double total) {
        Map<String, Object> m = new LinkedHashMap<>();
        if (date != null) m.put("date", date);
        m.put("short_volume", Math.round(shortVol));
        m.put("short_exempt_volume", Math.round(exempt));
        m.put("total_volume", Math.round(total));
        m.put("short_ratio_pct", total > 0 ? BigDecimal.valueOf(100 * shortVol / total).setScale(1, RoundingMode.HALF_UP).doubleValue() : null);
        return m;
    }

    static Map<String, Object> eq(String field, String value) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("compareType", "EQUAL");
        m.put("fieldName", field);
        m.put("fieldValue", value);
        return m;
    }

    static Map<String, Object> range(String field, LocalDate from, LocalDate to) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("fieldName", field);
        m.put("startDate", from.toString());
        m.put("endDate", to.toString());
        return m;
    }

    /** POST a synchronous Query API request for an otcMarket dataset → JSON array. */
    JsonNode dataset(ConnectorContext ctx, String name, List<Map<String, Object>> filters, Map<String, Object> dateRange, int limit) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("limit", limit);
        body.put("compareFilters", filters);
        body.put("dateRangeFilters", List.of(dateRange));
        Map<String, String> headers = new LinkedHashMap<>();
        String bearer = bearer(ctx);
        if (bearer != null) headers.put("Authorization", "Bearer " + bearer);
        JsonNode r;
        try {
            r = ctx.postJson(URI.create(api + "/data/group/otcMarket/name/" + name), headers, body);
        } catch (ConnectorException e) {
            if (e.kind() == ConnectorException.Kind.AUTH) {
                token = null;
                throw new ConnectorException(ConnectorException.Kind.AUTH, bearer == null
                        ? "FINRA now requires API credentials for this data. Add a FINRA API client ID and secret in Settings → Connectors."
                        : "FINRA rejected the API credentials. Check the client ID and secret in Settings → Connectors.");
            }
            if (e.kind() == ConnectorException.Kind.UPSTREAM && e.getMessage() != null && e.getMessage().contains("HTTP 400")) {
                throw ConnectorException.badRequest("FINRA rejected the query: " + e.getMessage().replaceFirst("^.*HTTP 400\\s*", ""));
            }
            throw e;
        }
        if (r == null || r.isNull() || r.isMissingNode()) return com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.arrayNode();
        if (!r.isArray()) throw ConnectorException.upstream("FINRA answered something unexpected for " + name + ".");
        return r;
    }

    /** OAuth client-credentials token when credentials are linked; null for anonymous access. */
    String bearer(ConnectorContext ctx) {
        var id = ctx.field("client_id");
        if (id.isEmpty()) return null;
        String secret = ctx.field("client_secret").orElseThrow(() -> new ConnectorException(ConnectorException.Kind.NOT_LINKED,
                "FINRA client ID is set but the client secret is missing. Add it in Settings → Connectors."));
        String key = id.get() + ":" + Integer.toHexString(secret.hashCode());
        if (token != null && key.equals(tokenFor) && clock.instant().isBefore(tokenExpires)) return token;
        synchronized (this) {
            if (token != null && key.equals(tokenFor) && clock.instant().isBefore(tokenExpires)) return token;
            String basic = Base64.getEncoder().encodeToString((id.get() + ":" + secret).getBytes(StandardCharsets.UTF_8));
            JsonNode t;
            try {
                t = ctx.postForm(URI.create(tokenUrl), Map.of("Authorization", "Basic " + basic), Map.of());
            } catch (ConnectorException e) {
                if (e.kind() == ConnectorException.Kind.AUTH || (e.getMessage() != null && e.getMessage().contains("HTTP 400"))) {
                    throw new ConnectorException(ConnectorException.Kind.AUTH,
                            "FINRA rejected the API client ID/secret. Check them in Settings → Connectors.");
                }
                throw e;
            }
            String access = t.path("access_token").asText("");
            if (access.isEmpty()) throw new ConnectorException(ConnectorException.Kind.AUTH, "FINRA did not issue an access token for these credentials.");
            long ttl = Math.min(t.path("expires_in").asLong(1800), 1800) - 60;
            token = access;
            tokenFor = key;
            tokenExpires = clock.instant().plusSeconds(Math.max(60, ttl));
            return access;
        }
    }

    /** One day's consolidated short-volume file, or null when FINRA has none for that date (the CDN answers 403/404). */
    String file(ConnectorContext ctx, LocalDate day) {
        try {
            return ctx.get(URI.create(cdn + "/equity/regsho/daily/CNMSshvol" + day.format(BASIC) + ".txt"), Map.of(), "text/plain");
        } catch (ConnectorException e) {
            if (e.kind() == ConnectorException.Kind.AUTH || e.kind() == ConnectorException.Kind.BAD_REQUEST) return null;
            throw e;
        }
    }
}
