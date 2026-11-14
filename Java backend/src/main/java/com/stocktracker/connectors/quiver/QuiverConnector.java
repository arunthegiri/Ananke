package com.stocktracker.connectors.quiver;

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
import java.util.Map;

import static com.stocktracker.connectors.ToolArgs.*;

/**
 * Quiver Quantitative (Connectors roadmap, alt data): congressional trades, corporate lobbying,
 * federal contracts and Form 4 insider trades, from the user's own Quiver API key.
 *
 * <p>Point-in-time: congressional trades carry both the transaction date and the disclosure
 * (report) date — the STOCK Act allows up to 45 days between them, so a backtest must key on
 * {@code disclosed}, never {@code transaction_date}. Insider rows carry the SEC filing time.
 *
 * <p>Endpoints, fields and per-endpoint limits are from Quiver's OpenAPI spec
 * (api.quiverquant.com/docs/openapi.json) and its rate-limit table.
 */
@Component
public class QuiverConnector implements Connector {

    static final String ID = "quiver";
    static final String BASE_URL = "https://api.quiverquant.com";

    private static final ConnectorSpec SPEC = new ConnectorSpec(
            ID,
            "Quiver Quantitative",
            ConnectorSpec.Category.ALT_DATA,
            ConnectorSpec.Licence.BYO_PERSONAL,   // Hobbyist/Trader plans are personal, non-commercial
            "Congressional stock trades (transaction and disclosure dates), corporate lobbying spend, "
                    + "federal government contracts and SEC Form 4 insider trades, mapped to tickers. "
                    + "Needs a Quiver API plan; insider trades need the Trader plan.",
            "Data: Quiver Quantitative (quiverquant.com)",
            "https://api.quiverquant.com/docs/",
            List.of(Field.secret("api_key", "API token",
                    "The Authorization Token from your Quiver API dashboard (api.quiverquant.com/dashboard).")),
            // Per-endpoint limits are 100/min, 10/min, 200/h or 5/h; the per-ticker endpoints used here are 10/min.
            List.of(RateLimit.perMinute(10), new RateLimit(200, Duration.ofHours(1))));

    private final String base;

    public QuiverConnector() {
        this(BASE_URL);
    }

    /** Test seam: point at a fake Quiver. */
    QuiverConnector(String base) {
        this.base = base;
    }

    @Override
    public ConnectorSpec spec() {
        return SPEC;
    }

    @Override
    public String test(ConnectorContext ctx) {
        JsonNode rows = get(ctx, "/beta/live/congresstrading", "normalized", "true");
        return "Connected — Quiver answered (" + list(rows).size() + " recent congressional trades).";
    }

    @Override
    public List<ConnectorTool> tools() {
        return List.of(
                new ConnectorTool(ID + "_congress_trades",
                        "Stock trades by members of the US House and Senate from STOCK Act filings, newest disclosure first. "
                                + "Each row has transaction_date AND disclosed (report date; up to 45 days later) — only "
                                + "the disclosed date is when the trade became public. With a ticker: that stock's full "
                                + "history; without: the latest trades across all tickers.",
                        object(props(
                                "ticker", str("Ticker symbol, e.g. NVDA. Omit for the latest trades across all tickers."),
                                "representative", str("Only this member (name as Quiver spells it, e.g. Nancy Pelosi); live feed only."),
                                "disclosed_since", str("Only trades disclosed on/after this date, YYYY-MM-DD"),
                                "limit", integer("Max rows (1-100, default 25)")), List.of()),
                        Duration.ofHours(1), this::congressTrades,
                        a -> "Reading congressional trades" + suffix(a) + " from Quiver…"),
                new ConnectorTool(ID + "_lobbying",
                        "Lobbying disclosures filed for a company: date reported, amount (USD), client, registrant "
                                + "(lobbying firm), issue category and the specific bills/issues lobbied, newest first.",
                        object(props(
                                "ticker", str("Ticker symbol"),
                                "query", str("Only filings whose issue/summary matches this text, e.g. tariff"),
                                "limit", integer("Max rows (1-100, default 20)")), List.of("ticker")),
                        Duration.ofHours(12), this::lobbying,
                        a -> "Reading lobbying filings" + suffix(a) + " from Quiver…"),
                new ConnectorTool(ID + "_gov_contracts",
                        "Federal contract awards to a company: action date, agency, amount and description, newest "
                                + "first. With quarterly=true: total contract amounts per quarter instead.",
                        object(props(
                                "ticker", str("Ticker symbol"),
                                "quarterly", bool("Return quarterly totals instead of individual awards"),
                                "limit", integer("Max rows (1-100, default 25)")), List.of("ticker")),
                        Duration.ofHours(12), this::govContracts,
                        a -> "Reading government contracts" + suffix(a) + " from Quiver…"),
                new ConnectorTool(ID + "_insider_trades",
                        "Recent SEC Form 4 insider transactions: insider name and title, transaction code, shares, "
                                + "price, holdings after, transaction date and the SEC filing time (when it became public). "
                                + "Needs the Quiver Trader plan.",
                        object(props(
                                "ticker", str("Ticker symbol. Omit for the latest filings across all tickers."),
                                "limit", integer("Max rows (1-100, default 25)")), List.of()),
                        Duration.ofMinutes(30), this::insiderTrades,
                        a -> "Reading insider trades" + suffix(a) + " from Quiver…"));
    }

    // ── tools ────────────────────────────────────────────────────────────────

    Object congressTrades(ConnectorContext ctx, JsonNode args) {
        String ticker = optStr(args, "ticker") == null ? null : ticker(args, "ticker");
        String rep = optStr(args, "representative");
        if (rep != null && rep.length() > 80) throw ConnectorException.badRequest("representative is too long");
        LocalDate since = date(args, "disclosed_since");
        int limit = intArg(args, "limit", 25, 1, 100);

        JsonNode raw = ticker != null
                ? get(ctx, "/beta/historical/congresstrading/" + enc(ticker))
                : get(ctx, "/beta/live/congresstrading", "normalized", "true", "representative", rep);
        List<Map<String, Object>> rows = new ArrayList<>();
        for (JsonNode r : list(raw)) {
            String disclosed = day(r.path("ReportDate"));
            if (since != null && (disclosed == null || LocalDate.parse(disclosed).isBefore(since))) continue;
            if (ticker == null && rep != null && !r.path("Representative").asText("").toLowerCase().contains(rep.toLowerCase())) continue;
            String traded = day(r.has("TransactionDate") ? r.path("TransactionDate") : r.path("Date"));
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("representative", text(r, "Representative"));
            m.put("bioguide_id", text(r, "BioGuideID"));
            m.put("party", text(r, "Party"));
            m.put("chamber", text(r, "House"));
            m.put("district", text(r, "District"));
            m.put("ticker", text(r, "Ticker"));
            m.put("asset_type", text(r, "TickerType"));
            m.put("transaction", text(r, "Transaction"));
            m.put("amount_range", text(r, "Range"));
            m.put("amount_min_usd", num(r.path("Amount")));
            m.put("transaction_date", traded);
            m.put("disclosed", disclosed);
            m.put("disclosure_lag_days", lag(traded, disclosed));
            m.put("description", trim(text(r, "Description"), 200));
            m.put("excess_return_pct", num(r.path("ExcessReturn")));
            m.put("last_modified", text(r, "last_modified"));
            rows.add(m);
        }
        rows.sort(Comparator.comparing((Map<String, Object> m) -> java.util.Objects.toString(m.get("disclosed"), "")).reversed());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ticker", ticker);
        out.put("matched", rows.size());
        out.put("trades", rows.subList(0, Math.min(limit, rows.size())));
        out.put("note", "transaction_date is when the member traded; disclosed is when it became public (STOCK Act: up to 45 days).");
        return out;
    }

    Object lobbying(ConnectorContext ctx, JsonNode args) {
        String ticker = ticker(args, "ticker");
        String query = optStr(args, "query");
        if (query != null && query.length() > 100) throw ConnectorException.badRequest("query is too long");
        int limit = intArg(args, "limit", 20, 1, 100);
        JsonNode raw = get(ctx, "/beta/historical/lobbying/" + enc(ticker), "page", "1", "page_size", String.valueOf(limit), "query", query);
        List<Map<String, Object>> rows = new ArrayList<>();
        for (JsonNode r : list(raw)) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("date", day(r.path("Date")));
            m.put("amount_usd", num(r.path("Amount")));
            m.put("client", text(r, "Client"));
            m.put("registrant", text(r, "Registrant"));
            m.put("issue", text(r, "Issue"));
            m.put("specific_issue", trim(text(r, "Specific_Issue"), 300));
            rows.add(m);
        }
        rows.sort(Comparator.comparing((Map<String, Object> m) -> java.util.Objects.toString(m.get("date"), "")).reversed());
        return Map.of("ticker", ticker, "count", Math.min(limit, rows.size()), "filings", rows.subList(0, Math.min(limit, rows.size())));
    }

    Object govContracts(ConnectorContext ctx, JsonNode args) {
        String ticker = ticker(args, "ticker");
        boolean quarterly = args != null && args.path("quarterly").asBoolean(false);
        int limit = intArg(args, "limit", 25, 1, 100);
        List<Map<String, Object>> rows = new ArrayList<>();
        if (quarterly) {
            for (JsonNode r : list(get(ctx, "/beta/historical/govcontracts/" + enc(ticker)))) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("year", r.path("Year").isMissingNode() ? null : r.path("Year").asInt());
                m.put("quarter", r.path("Qtr").isMissingNode() ? null : r.path("Qtr").asInt());
                m.put("amount_usd", num(r.path("Amount")));
                rows.add(m);
            }
            rows.sort(Comparator.comparing((Map<String, Object> m) -> m.get("year") + "-" + m.get("quarter")).reversed());
        } else {
            for (JsonNode r : list(get(ctx, "/beta/historical/govcontractsall/" + enc(ticker)))) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("action_date", day(r.path("action_date")));
                m.put("date", day(r.path("Date")));
                m.put("agency", text(r, "Agency"));
                m.put("amount_usd", num(r.path("Amount")));
                m.put("description", trim(text(r, "Description"), 300));
                rows.add(m);
            }
            rows.sort(Comparator.comparing((Map<String, Object> m) -> java.util.Objects.toString(m.get("action_date") != null ? m.get("action_date") : m.get("date"), "")).reversed());
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ticker", ticker);
        out.put("kind", quarterly ? "quarterly_totals" : "awards");
        out.put("count", Math.min(limit, rows.size()));
        out.put("contracts", rows.subList(0, Math.min(limit, rows.size())));
        return out;
    }

    Object insiderTrades(ConnectorContext ctx, JsonNode args) {
        String ticker = optStr(args, "ticker") == null ? null : ticker(args, "ticker");
        int limit = intArg(args, "limit", 25, 1, 100);
        JsonNode raw = get(ctx, "/beta/live/insiders", "ticker", ticker, "page", "1", "page_size", String.valueOf(limit));
        List<Map<String, Object>> rows = new ArrayList<>();
        for (JsonNode r : list(raw)) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("ticker", text(r, "Ticker"));
            m.put("name", text(r, "Name"));
            m.put("title", text(r, "officerTitle"));
            m.put("is_director", flag(r, "isDirector"));
            m.put("is_officer", flag(r, "isOfficer"));
            m.put("is_ten_percent_owner", flag(r, "isTenPercentOwner"));
            m.put("transaction_code", text(r, "TransactionCode"));
            m.put("acquired_disposed", text(r, "AcquiredDisposedCode"));
            m.put("shares", num(r.path("Shares")));
            m.put("price", num(r.path("PricePerShare")));
            m.put("shares_owned_after", num(r.path("SharesOwnedFollowing")));
            m.put("ownership", text(r, "directOrIndirectOwnership"));
            m.put("transaction_date", day(r.path("Date")));
            m.put("filed_at", text(r, "fileDate"));
            m.put("uploaded_at", text(r, "uploaded"));
            rows.add(m);
            if (rows.size() >= limit) break;
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ticker", ticker);
        out.put("count", rows.size());
        out.put("trades", rows);
        out.put("note", "filed_at is when the Form 4 became public; transaction codes: P=open-market buy, S=sale, A=award, M=option exercise, F=tax withholding.");
        return out;
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private JsonNode get(ConnectorContext ctx, String path, String... query) {
        Object[] kv = new Object[query.length];
        System.arraycopy(query, 0, kv, 0, query.length);
        URI uri = URI.create(url(base + path, kv));
        return ctx.getJson(uri, Map.of("Authorization", "Bearer " + ctx.require("api_key")));
    }

    /** Quiver answers arrays, or {data:[…], pagination} on paginated endpoints. */
    static List<JsonNode> list(JsonNode n) {
        List<JsonNode> out = new ArrayList<>();
        JsonNode arr = n == null ? null : n.isArray() ? n : n.path("data");
        if (arr != null && arr.isArray()) arr.forEach(out::add);
        else if (n != null && n.has("detail")) throw ConnectorException.upstream("Quiver: " + n.path("detail").asText());
        return out;
    }

    static String suffix(JsonNode a) {
        String t = a == null ? "" : a.path("ticker").asText("");
        return t.isBlank() ? "" : " for " + t.toUpperCase();
    }

    static String text(JsonNode r, String f) {
        JsonNode n = r.path(f);
        if (n.isMissingNode() || n.isNull()) return null;
        String s = n.asText("").strip();
        return s.isEmpty() ? null : s;
    }

    static Boolean flag(JsonNode r, String f) {
        JsonNode n = r.path(f);
        return n.isMissingNode() || n.isNull() ? null : n.asBoolean();
    }

    /** Number from a number or numeric string ("15001.0"); null otherwise. */
    static Double num(JsonNode n) {
        if (n == null || n.isMissingNode() || n.isNull()) return null;
        if (n.isNumber()) return n.asDouble();
        try {
            return Double.parseDouble(n.asText().replace(",", "").replace("$", "").strip());
        } catch (Exception e) {
            return null;
        }
    }

    /** "2024-05-01T00:00:00Z" / "2024-05-01" → "2024-05-01"; null when absent or unparseable. */
    static String day(JsonNode n) {
        if (n == null || n.isMissingNode() || n.isNull()) return null;
        String s = n.asText("").strip();
        if (s.length() < 10) return null;
        try {
            return LocalDate.parse(s.substring(0, 10)).toString();
        } catch (Exception e) {
            return null;
        }
    }

    static Long lag(String from, String to) {
        if (from == null || to == null) return null;
        return ChronoUnit.DAYS.between(LocalDate.parse(from), LocalDate.parse(to));
    }

    static String trim(String s, int max) {
        if (s == null) return null;
        String t = s.replaceAll("\\s+", " ").strip();
        return t.length() > max ? t.substring(0, max) + "…" : t;
    }
}
