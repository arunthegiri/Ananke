package com.stocktracker.connectors.sec;

import com.fasterxml.jackson.databind.JsonNode;
import com.stocktracker.connectors.Connector;
import com.stocktracker.connectors.ConnectorContext;
import com.stocktracker.connectors.ConnectorException;
import com.stocktracker.connectors.ConnectorSpec;
import com.stocktracker.connectors.ConnectorSpec.Field;
import com.stocktracker.connectors.ConnectorSpec.RateLimit;
import com.stocktracker.connectors.ConnectorTool;
import org.springframework.stereotype.Component;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * SEC EDGAR (Connectors roadmap §2): filings, XBRL financials, Form 4 insider trades and
 * full-text search, straight from the SEC's public APIs. Free; the SEC requires every client
 * to declare who it is in the User-Agent and to stay under 10 requests a second.
 *
 * <p>Point-in-time (§0.6): every filing carries the SEC's acceptance time and every XBRL value
 * its {@code filed} date, so a backtest can use only what was public on a given day.
 */
@Component
public class SecEdgarConnector implements Connector {

    static final String ID = "sec_edgar";

    static final String WWW_URL  = "https://www.sec.gov";
    static final String DATA_URL = "https://data.sec.gov";
    static final String EFTS_URL = "https://efts.sec.gov/LATEST/search-index";

    static final Duration TICKERS_TTL = Duration.ofHours(24);

    private static final ConnectorSpec SPEC = new ConnectorSpec(
            ID,
            "SEC EDGAR",
            ConnectorSpec.Category.FILINGS,
            ConnectorSpec.Licence.FREE_PUBLIC,
            "US company filings (10-K, 10-Q, 8-K, S-1, 13D/G…), XBRL financials with filing dates, "
                    + "Form 4 insider trades and full-text search of filings since 2001.",
            "Source: U.S. Securities and Exchange Commission, EDGAR",
            "https://www.sec.gov/search-filings/edgar-application-programming-interfaces",
            List.of(Field.text("identity", "Your name and email",
                    "The SEC requires every program that reads EDGAR to identify itself. Sent as the User-Agent; "
                            + "it is not an account and nothing is signed up for.",
                    "Jane Doe jane@example.com")),
            List.of(RateLimit.perSecond(8)));   // SEC's ceiling is 10/s; keep headroom

    private final Clock clock;
    private final String www;
    private final String data;
    private final String efts;
    private volatile Map<String, Company> tickers;
    private volatile Instant tickersAt;

    public SecEdgarConnector() {
        this(Clock.systemUTC(), WWW_URL, DATA_URL, EFTS_URL);
    }

    /** Test seam: point at a fake EDGAR. */
    SecEdgarConnector(Clock clock, String www, String data, String efts) {
        this.clock = clock;
        this.www   = www;
        this.data  = data;
        this.efts  = efts;
    }

    public record Company(String cik, String ticker, String name) { }

    @Override
    public ConnectorSpec spec() {
        return SPEC;
    }

    @Override
    public String test(ConnectorContext ctx) {
        String identity = ctx.require("identity");
        if (!identity.matches(".*\\S+@\\S+\\.\\S+.*")) {
            throw ConnectorException.badRequest("The SEC asks for a contact email in the identity, e.g. \"Jane Doe jane@example.com\".");
        }
        Company apple = company(ctx, "AAPL");
        return "Connected — EDGAR answered (" + tickers.size() + " tickers; e.g. " + apple.ticker() + " = CIK " + apple.cik() + ").";
    }

    @Override
    public List<ConnectorTool> tools() {
        return List.of(
                new ConnectorTool(ID + "_company",
                        "Look up a US-listed company in EDGAR by ticker: CIK, legal name, industry (SIC), exchanges, "
                                + "fiscal year end and state of incorporation.",
                        schema(Map.of("ticker", str("Ticker symbol, e.g. AAPL")), List.of("ticker")),
                        Duration.ofHours(12), this::companyProfile,
                        a -> "Looking up " + a.path("ticker").asText("company") + " in EDGAR…"),
                new ConnectorTool(ID + "_filings",
                        "Recent SEC filings for a company, newest first, with the SEC acceptance time (when it became "
                                + "public), 8-K item numbers and a link to each document.",
                        schema(Map.of(
                                "ticker", str("Ticker symbol"),
                                "forms", Map.of("type", "array", "items", Map.of("type", "string"),
                                        "description", "Only these form types, e.g. [\"8-K\",\"10-Q\"]. Omit for all."),
                                "since", str("Only filings on/after this date, YYYY-MM-DD"),
                                "limit", intg("Max filings to return (1-50, default 20)")), List.of("ticker")),
                        Duration.ofMinutes(10), this::filings,
                        a -> "Reading " + a.path("ticker").asText("") + " filings on EDGAR…"),
                new ConnectorTool(ID + "_financials",
                        "History of one XBRL financial concept as reported in 10-K/10-Q filings, each value with the date "
                                + "it was filed. Common concepts: Revenues, RevenueFromContractWithCustomerExcludingAssessedTax, "
                                + "NetIncomeLoss, EarningsPerShareDiluted (unit USD/shares), OperatingIncomeLoss, Assets, "
                                + "Liabilities, StockholdersEquity, CashAndCashEquivalentsAtCarryingValue, "
                                + "NetCashProvidedByUsedInOperatingActivities, LongTermDebt, CommonStockSharesOutstanding (unit shares, taxonomy dei uses EntityCommonStockSharesOutstanding).",
                        schema(Map.of(
                                "ticker", str("Ticker symbol"),
                                "concept", str("XBRL concept name, e.g. NetIncomeLoss"),
                                "taxonomy", str("us-gaap (default), dei, ifrs-full or srt"),
                                "unit", str("USD (default), USD/shares, shares, pure…"),
                                "period", str("annual (10-K FY values), quarterly, or all (default)"),
                                "limit", intg("Most recent N values (1-40, default 12)")), List.of("ticker", "concept")),
                        Duration.ofHours(6), this::financials,
                        a -> "Reading " + a.path("concept").asText("financials") + " for " + a.path("ticker").asText("") + "…"),
                new ConnectorTool(ID + "_insider_trades",
                        "Insider transactions from recent Form 4 filings: who (name, title, director/officer/10% owner), "
                                + "what (buy/sell/award/exercise code), shares, price, holdings after, whether under a 10b5-1 "
                                + "plan, and when the SEC accepted the filing.",
                        schema(Map.of(
                                "ticker", str("Ticker symbol"),
                                "limit", intg("How many recent Form 4 filings to read (1-15, default 8)")), List.of("ticker")),
                        Duration.ofMinutes(30), this::insiderTrades,
                        a -> "Reading Form 4 insider filings for " + a.path("ticker").asText("") + "…"),
                new ConnectorTool(ID + "_search",
                        "Full-text search across EDGAR filings and exhibits since 2001 (phrases in quotes, AND/OR). "
                                + "Good for themes across companies: \"tariff\" in 8-Ks, \"going concern\", a product or supplier name.",
                        schema(Map.of(
                                "query", str("Search text, e.g. \"supply chain\" AND tariff"),
                                "forms", Map.of("type", "array", "items", Map.of("type", "string"),
                                        "description", "Form types to search, e.g. [\"8-K\"]. Omit for all."),
                                "from", str("Start date YYYY-MM-DD"),
                                "to", str("End date YYYY-MM-DD"),
                                "ticker", str("Limit to one company"),
                                "limit", intg("Max hits (1-50, default 20)")), List.of("query")),
                        Duration.ofMinutes(30), this::search,
                        a -> "Searching EDGAR for " + a.path("query").asText("") + "…"),
                new ConnectorTool(ID + "_13f_holdings",
                        "An institution's latest 13F-HR holdings (US long equity positions, filed up to 45 days after "
                                + "quarter end): top positions by value with portfolio weight, aggregated across its "
                                + "sub-managers, plus the change versus the previous quarter (new, exited, added, trimmed). "
                                + "Filer by ticker (e.g. BRK-B) or CIK number (e.g. 1067983 for Berkshire, 1350694 for Bridgewater).",
                        schema(Map.of(
                                "filer", str("Institution ticker or CIK number"),
                                "limit", intg("Top N positions by value (1-100, default 25)"),
                                "compare", Map.of("type", "boolean", "description", "Include change vs previous quarter (default true)")),
                                List.of("filer")),
                        Duration.ofHours(12), this::holdings13f,
                        a -> "Reading the latest 13F for " + a.path("filer").asText("") + "…"));
    }

    // ── tools ────────────────────────────────────────────────────────────────

    Object companyProfile(ConnectorContext ctx, JsonNode args) {
        Company c = company(ctx, args.path("ticker").asText());
        JsonNode s = ctx.getJson(URI.create(data + "/submissions/CIK" + c.cik() + ".json"), headers(ctx));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("cik", c.cik());
        out.put("name", s.path("name").asText(c.name()));
        out.put("tickers", list(s.path("tickers")));
        out.put("exchanges", list(s.path("exchanges")));
        out.put("sic", s.path("sic").asText(null));
        out.put("industry", s.path("sicDescription").asText(null));
        out.put("category", s.path("category").asText(null));
        out.put("fiscal_year_end", s.path("fiscalYearEnd").asText(null));
        out.put("state_of_incorporation", s.path("stateOfIncorporation").asText(null));
        out.put("former_names", s.path("formerNames").findValuesAsText("name"));
        out.put("edgar_url", www + "/cgi-bin/browse-edgar?action=getcompany&CIK=" + c.cik());
        return out;
    }

    Object filings(ConnectorContext ctx, JsonNode args) {
        Company c = company(ctx, args.path("ticker").asText());
        Set<String> forms = upperSet(args.path("forms"));
        LocalDate since = date(args.path("since"), "since");
        int limit = clamp(args.path("limit").asInt(20), 1, 50);
        JsonNode s = ctx.getJson(URI.create(data + "/submissions/CIK" + c.cik() + ".json"), headers(ctx));
        List<Map<String, Object>> rows = recent(c, s, f -> (forms.isEmpty() || forms.contains(f.form().toUpperCase(Locale.ROOT)))
                && (since == null || !f.filingDate().isBefore(since)), limit);
        return Map.of("company", c.name(), "cik", c.cik(), "count", rows.size(), "filings", rows);
    }

    Object financials(ConnectorContext ctx, JsonNode args) {
        Company c = company(ctx, args.path("ticker").asText());
        String concept = args.path("concept").asText("").strip();
        if (!concept.matches("^[A-Za-z0-9_]{2,200}$")) throw ConnectorException.badRequest("concept must be an XBRL name like NetIncomeLoss");
        String taxonomy = args.path("taxonomy").asText("us-gaap").strip();
        if (!Set.of("us-gaap", "dei", "ifrs-full", "srt", "invest").contains(taxonomy)) {
            throw ConnectorException.badRequest("taxonomy must be us-gaap, dei, ifrs-full, srt or invest");
        }
        String unit = args.path("unit").asText("USD").strip();
        String period = args.path("period").asText("all").strip().toLowerCase(Locale.ROOT);
        int limit = clamp(args.path("limit").asInt(12), 1, 40);

        JsonNode d;
        try {
            d = ctx.getJson(URI.create(data + "/api/xbrl/companyconcept/CIK" + c.cik() + "/" + taxonomy + "/" + concept + ".json"), headers(ctx));
        } catch (ConnectorException e) {
            if (e.kind() == ConnectorException.Kind.BAD_REQUEST) {
                throw ConnectorException.badRequest(c.ticker() + " has no " + taxonomy + ":" + concept
                        + " in its XBRL filings. Try a related concept (e.g. Revenues vs RevenueFromContractWithCustomerExcludingAssessedTax).");
            }
            throw e;
        }
        JsonNode values = d.path("units").path(unit);
        if (!values.isArray()) {
            List<String> units = new ArrayList<>();
            d.path("units").fieldNames().forEachRemaining(units::add);
            throw ConnectorException.badRequest(concept + " is not reported in " + unit + " for " + c.ticker() + ". Available units: " + units);
        }
        // One row per reporting period, as FIRST reported (what the market knew then). Later 10-Ks
        // re-report prior years as comparatives; a different number there is a restatement and is
        // attached to the row rather than replacing it.
        Map<String, Map<String, Object>> byPeriod = new LinkedHashMap<>();
        List<JsonNode> sorted = new ArrayList<>();
        values.forEach(sorted::add);
        sorted.sort(java.util.Comparator.comparing(v -> v.path("filed").asText()));
        for (JsonNode v : sorted) {
            String form = v.path("form").asText();
            String fp = v.path("fp").asText();
            boolean annualForm = form.startsWith("10-K") || form.startsWith("20-F") || form.startsWith("40-F");
            if (period.equals("annual") && !annualForm) continue;
            if (period.equals("quarterly") && !form.startsWith("10-Q")) continue;
            String start = v.path("start").asText("");
            String end = v.path("end").asText();
            if (period.equals("annual") && !start.isEmpty() && daysBetween(start, end) < 300) continue;   // Q4 slices inside a 10-K
            String key = start + "|" + end;
            Map<String, Object> r = byPeriod.get(key);
            if (r == null) {
                r = new LinkedHashMap<>();
                if (!start.isEmpty()) r.put("start", start);
                r.put("end", end);
                r.put("value", v.path("val").numberValue());
                r.put("fiscal_year", v.path("fy").isNumber() ? v.path("fy").asInt() : null);
                r.put("fiscal_period", fp);
                r.put("form", form);
                r.put("filed", v.path("filed").asText());
                r.put("accession", v.path("accn").asText());
                if (v.has("frame")) r.put("frame", v.path("frame").asText());
                byPeriod.put(key, r);
            } else if (v.path("val").decimalValue().compareTo(new java.math.BigDecimal(r.get("value").toString())) != 0) {
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> restated = (List<Map<String, Object>>) r.computeIfAbsent("restated", k -> new ArrayList<>());
                restated.add(Map.of("value", v.path("val").numberValue(), "filed", v.path("filed").asText(), "form", form));
            }
        }
        List<Map<String, Object>> rows = new ArrayList<>(byPeriod.values());
        rows.sort((a, b) -> {
            int byEnd = ((String) b.get("end")).compareTo((String) a.get("end"));
            return byEnd != 0 ? byEnd : ((String) b.getOrDefault("start", "")).compareTo((String) a.getOrDefault("start", ""));
        });
        List<Map<String, Object>> out = rows.size() > limit ? rows.subList(0, limit) : rows;
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("company", c.name());
        m.put("concept", taxonomy + ":" + concept);
        m.put("label", d.path("label").asText(null));
        m.put("unit", unit);
        m.put("values", out);
        m.put("note", "Each period shows its first-reported value; 'filed' is when it became public — use it, not 'end', "
                + "for point-in-time backtests. 'restated' lists later different numbers for the same period.");
        return m;
    }

    Object insiderTrades(ConnectorContext ctx, JsonNode args) {
        Company c = company(ctx, args.path("ticker").asText());
        int limit = clamp(args.path("limit").asInt(8), 1, 15);
        JsonNode s = ctx.getJson(URI.create(data + "/submissions/CIK" + c.cik() + ".json"), headers(ctx));
        List<Filing> form4s = recentFilings(s).stream()
                .filter(f -> f.form().equals("4") || f.form().equals("4/A")).limit(limit).toList();
        List<Map<String, Object>> trades = new ArrayList<>();
        List<String> skipped = new ArrayList<>();
        for (Filing f : form4s) {
            String doc = f.primaryDocument().replaceFirst("^xsl[^/]*/", "");
            String url = archiveBase(c, f) + doc;
            try {
                String xml = ctx.get(URI.create(url), headers(ctx), "application/xml");
                trades.addAll(parseForm4(xml, f, archiveBase(c, f) + f.primaryDocument()));
            } catch (ConnectorException e) {
                if (e.kind() == ConnectorException.Kind.RATE_LIMITED) throw e;
                skipped.add(f.accession());
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("company", c.name());
        out.put("filings_read", form4s.size());
        out.put("transactions", trades);
        out.put("codes", Map.of("P", "open-market purchase", "S", "open-market sale", "A", "grant/award",
                "M", "option exercise", "F", "shares withheld for tax", "G", "gift", "C", "conversion", "D", "disposition to issuer"));
        if (!skipped.isEmpty()) out.put("unreadable_filings", skipped);
        return out;
    }

    Object search(ConnectorContext ctx, JsonNode args) {
        String q = args.path("query").asText("").strip();
        if (q.isEmpty() || q.length() > 500) throw ConnectorException.badRequest("query is required (max 500 characters)");
        int limit = clamp(args.path("limit").asInt(20), 1, 50);
        StringBuilder url = new StringBuilder(efts).append("?q=").append(enc(q));
        Set<String> forms = upperSet(args.path("forms"));
        if (!forms.isEmpty()) url.append("&forms=").append(enc(String.join(",", forms)));
        LocalDate from = date(args.path("from"), "from");
        LocalDate to = date(args.path("to"), "to");
        if (from != null || to != null) {
            url.append("&dateRange=custom&startdt=").append(from == null ? "2001-01-01" : from)
               .append("&enddt=").append(to == null ? LocalDate.now(clock) : to);
        }
        if (args.hasNonNull("ticker") && !args.path("ticker").asText().isBlank()) {
            url.append("&ciks=").append(company(ctx, args.path("ticker").asText()).cik());
        }
        JsonNode r = ctx.getJson(URI.create(url.toString()), headers(ctx));
        List<Map<String, Object>> hits = new ArrayList<>();
        for (JsonNode h : r.path("hits").path("hits")) {
            if (hits.size() >= limit) break;
            JsonNode src = h.path("_source");
            String id = h.path("_id").asText();
            String adsh = src.path("adsh").asText();
            String file = id.contains(":") ? id.substring(id.indexOf(':') + 1) : "";
            String cik = src.path("ciks").path(0).asText("").replaceFirst("^0+", "");
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("company", src.path("display_names").path(0).asText(null));
            m.put("form", src.path("form").asText(null));
            m.put("file_type", src.path("file_type").asText(null));
            m.put("filed", src.path("file_date").asText(null));
            m.put("period_ending", src.path("period_ending").asText(null));
            m.put("items", list(src.path("items")));
            m.put("accession", adsh);
            if (!cik.isEmpty() && !file.isEmpty()) {
                m.put("url", www + "/Archives/edgar/data/" + cik + "/" + adsh.replace("-", "") + "/" + file);
            }
            hits.add(m);
        }
        return Map.of("query", q, "total", r.path("hits").path("total").path("value").asInt(hits.size()), "hits", hits);
    }

    // ── 13F ──────────────────────────────────────────────────────────────────

    record Position(String issuer, String cusip, String titleOfClass, String putCall, java.math.BigDecimal value, java.math.BigDecimal shares) { }

    Object holdings13f(ConnectorContext ctx, JsonNode args) {
        String filer = args.path("filer").asText("").strip();
        if (filer.isEmpty()) throw ConnectorException.badRequest("filer is required (ticker or CIK)");
        int limit = clamp(args.path("limit").asInt(25), 1, 100);
        boolean compare = !args.has("compare") || args.path("compare").asBoolean(true);
        Company c = filer.matches("^\\d{1,10}$")
                ? new Company(String.format("%010d", Long.parseLong(filer)), null, null)
                : company(ctx, filer);
        JsonNode s = ctx.getJson(URI.create(data + "/submissions/CIK" + c.cik() + ".json"), headers(ctx));
        String name = s.path("name").asText(c.name());
        List<Filing> f13 = recentFilings(s).stream().filter(f -> f.form().equals("13F-HR")).limit(2).toList();
        if (f13.isEmpty()) throw ConnectorException.badRequest(name + " has no 13F-HR filing in its recent EDGAR history (only managers with $100M+ in US equities file).");
        Company owner = new Company(c.cik(), c.ticker(), name);

        Map<String, Position> now = positions(ctx, owner, f13.get(0));
        java.math.BigDecimal total = now.values().stream().map(Position::value).reduce(java.math.BigDecimal.ZERO, java.math.BigDecimal::add);
        List<Position> sorted = new ArrayList<>(now.values());
        sorted.sort((a, b) -> b.value().compareTo(a.value()));

        Map<String, Position> prev = compare && f13.size() > 1 ? positions(ctx, owner, f13.get(1)) : null;
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Position p : sorted.subList(0, Math.min(limit, sorted.size()))) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("issuer", p.issuer());
            m.put("cusip", p.cusip());
            m.put("class", p.titleOfClass());
            if (p.putCall() != null) m.put("put_call", p.putCall());
            m.put("value_usd", p.value());
            m.put("shares", p.shares());
            m.put("weight_pct", pct(p.value(), total));
            if (prev != null) {
                Position before = prev.get(key(p));
                m.put("shares_prev", before == null ? null : before.shares());
                m.put("change", before == null ? "NEW" : changeLabel(before.shares(), p.shares()));
            }
            rows.add(m);
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("filer", name);
        out.put("cik", c.cik());
        out.put("period", f13.get(0).reportDate());
        out.put("filed", f13.get(0).filingDate().toString());
        out.put("accepted_at", f13.get(0).acceptance());
        out.put("total_value_usd", total);
        out.put("positions", now.size());
        out.put("holdings", rows);
        if (prev != null) {
            List<Map<String, Object>> exited = new ArrayList<>();
            prev.values().stream().filter(p -> !now.containsKey(key(p)))
                    .sorted((a, b) -> b.value().compareTo(a.value())).limit(15)
                    .forEach(p -> {
                        Map<String, Object> m = new LinkedHashMap<>();
                        m.put("issuer", p.issuer());
                        m.put("cusip", p.cusip());
                        m.put("value_usd_prev", p.value());
                        m.put("shares_prev", p.shares());
                        exited.add(m);
                    });
            out.put("previous_period", f13.get(1).reportDate());
            out.put("exited", exited);
            out.put("new_positions", now.keySet().stream().filter(k -> !prev.containsKey(k)).count());
        }
        out.put("url", archiveBase(owner, f13.get(0)) + f13.get(0).primaryDocument());
        out.put("note", "13F covers US-listed long equity, options and convertibles only — no shorts, cash or non-US holdings. "
                + "Values are as of the period end; 'filed' is when it became public.");
        return out;
    }

    /** One 13F's information table, aggregated by CUSIP + put/call across sub-managers. */
    private Map<String, Position> positions(ConnectorContext ctx, Company c, Filing f) {
        JsonNode idx = ctx.getJson(URI.create(archiveBase(c, f) + "index.json"), headers(ctx));
        String table = null;
        for (JsonNode item : idx.path("directory").path("item")) {
            String n = item.path("name").asText("");
            if (n.toLowerCase(Locale.ROOT).endsWith(".xml") && !n.equalsIgnoreCase("primary_doc.xml")) {
                table = n;
                break;
            }
        }
        if (table == null) throw ConnectorException.upstream("13F " + f.accession() + " has no information table XML.");
        String xml = ctx.get(URI.create(archiveBase(c, f) + table), headers(ctx), "application/xml");
        return parse13f(xml, f.accession());
    }

    static Map<String, Position> parse13f(String xml, String accession) {
        Document doc;
        try {
            DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
            dbf.setNamespaceAware(true);   // filers use both default and prefixed (ns1:) namespaces
            dbf.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            dbf.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            dbf.setExpandEntityReferences(false);
            doc = dbf.newDocumentBuilder().parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw ConnectorException.upstream("13F " + accession + " information table is not readable XML.");
        }
        Map<String, Position> out = new LinkedHashMap<>();
        NodeList rows = doc.getElementsByTagNameNS("*", "infoTable");
        for (int i = 0; i < rows.getLength(); i++) {
            Element r = (Element) rows.item(i);
            String cusip = ns(r, "cusip");
            if (cusip == null) continue;
            String putCall = ns(r, "putCall");
            java.math.BigDecimal value = dec(ns(r, "value"));
            java.math.BigDecimal shares = dec(ns(r, "sshPrnamt"));
            Position p = new Position(ns(r, "nameOfIssuer"), cusip.toUpperCase(Locale.ROOT), ns(r, "titleOfClass"),
                    putCall == null ? null : putCall.toUpperCase(Locale.ROOT), value, shares);
            out.merge(key(p), p, (a, b) -> new Position(a.issuer(), a.cusip(), a.titleOfClass(), a.putCall(),
                    a.value().add(b.value()), a.shares().add(b.shares())));
        }
        return out;
    }

    private static String key(Position p) {
        return p.cusip() + (p.putCall() == null ? "" : ":" + p.putCall());
    }

    private static String changeLabel(java.math.BigDecimal before, java.math.BigDecimal after) {
        int cmp = after.compareTo(before);
        if (cmp == 0) return "UNCHANGED";
        if (before.signum() == 0) return "ADDED";
        java.math.BigDecimal pct = after.subtract(before).multiply(java.math.BigDecimal.valueOf(100))
                .divide(before, 1, java.math.RoundingMode.HALF_UP);
        return (cmp > 0 ? "ADDED +" : "TRIMMED ") + pct.toPlainString() + "%";
    }

    private static java.math.BigDecimal pct(java.math.BigDecimal part, java.math.BigDecimal total) {
        if (total.signum() == 0) return java.math.BigDecimal.ZERO;
        return part.multiply(java.math.BigDecimal.valueOf(100)).divide(total, 2, java.math.RoundingMode.HALF_UP);
    }

    private static String ns(Element el, String local) {
        NodeList n = el.getElementsByTagNameNS("*", local);
        if (n.getLength() == 0) return null;
        String s = n.item(0).getTextContent();
        return s == null || s.isBlank() ? null : s.strip();
    }

    private static java.math.BigDecimal dec(String s) {
        if (s == null) return java.math.BigDecimal.ZERO;
        try {
            return new java.math.BigDecimal(s.replace(",", ""));
        } catch (NumberFormatException e) {
            return java.math.BigDecimal.ZERO;
        }
    }

    // ── EDGAR plumbing ───────────────────────────────────────────────────────

    record Filing(String accession, String form, LocalDate filingDate, String acceptance, String reportDate,
                  String items, String primaryDocument, String description) { }

    static List<Filing> recentFilings(JsonNode submissions) {
        JsonNode r = submissions.path("filings").path("recent");
        int n = r.path("accessionNumber").size();
        List<Filing> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            String fd = r.path("filingDate").path(i).asText("");
            out.add(new Filing(r.path("accessionNumber").path(i).asText(), r.path("form").path(i).asText(),
                    fd.isEmpty() ? LocalDate.MIN : LocalDate.parse(fd), r.path("acceptanceDateTime").path(i).asText(null),
                    blankToNull(r.path("reportDate").path(i).asText(null)), blankToNull(r.path("items").path(i).asText(null)),
                    r.path("primaryDocument").path(i).asText(""), blankToNull(r.path("primaryDocDescription").path(i).asText(null))));
        }
        return out;
    }

    private List<Map<String, Object>> recent(Company c, JsonNode s, Predicate<Filing> keep, int limit) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Filing f : recentFilings(s)) {
            if (!keep.test(f)) continue;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("form", f.form());
            m.put("filed", f.filingDate().toString());
            m.put("accepted_at", f.acceptance());
            if (f.reportDate() != null) m.put("period", f.reportDate());
            if (f.items() != null) m.put("items", f.items());
            if (f.description() != null) m.put("description", f.description());
            m.put("accession", f.accession());
            m.put("url", archiveBase(c, f) + f.primaryDocument());
            rows.add(m);
            if (rows.size() >= limit) break;
        }
        return rows;
    }

    String archiveBase(Company c, Filing f) {
        return www + "/Archives/edgar/data/" + Long.parseLong(c.cik()) + "/" + f.accession().replace("-", "") + "/";
    }

    /** Ticker → company, from the SEC's ticker file (refreshed daily). */
    Company company(ConnectorContext ctx, String rawTicker) {
        String t = rawTicker == null ? "" : rawTicker.strip().toUpperCase(Locale.ROOT).replace('.', '-');
        if (!t.matches("^[A-Z0-9-]{1,10}$")) throw ConnectorException.badRequest("ticker must look like AAPL or BRK-B");
        Map<String, Company> map = tickers;
        if (map == null || tickersAt.plus(TICKERS_TTL).isBefore(clock.instant())) {
            synchronized (this) {
                if (tickers == null || tickersAt.plus(TICKERS_TTL).isBefore(clock.instant())) {
                    JsonNode j = ctx.getJson(URI.create(www + "/files/company_tickers.json"), headers(ctx));
                    Map<String, Company> m = new HashMap<>();
                    for (JsonNode e : j) {
                        String tk = e.path("ticker").asText().toUpperCase(Locale.ROOT);
                        m.putIfAbsent(tk, new Company(String.format("%010d", e.path("cik_str").asLong()), tk, e.path("title").asText()));
                    }
                    if (m.isEmpty()) throw ConnectorException.upstream("SEC EDGAR returned an empty ticker list.");
                    tickers = m;
                    tickersAt = clock.instant();
                }
                map = tickers;
            }
        }
        Company c = map.get(t);
        if (c == null) throw ConnectorException.badRequest(t + " is not in the SEC's ticker list (US-listed SEC registrants only).");
        return c;
    }

    static Map<String, String> headers(ConnectorContext ctx) {
        return Map.of("User-Agent", ctx.require("identity"));
    }

    // ── Form 4 ───────────────────────────────────────────────────────────────

    static List<Map<String, Object>> parseForm4(String xml, Filing f, String url) {
        Document doc;
        try {
            DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
            dbf.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            dbf.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            dbf.setExpandEntityReferences(false);
            doc = dbf.newDocumentBuilder().parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw ConnectorException.upstream("Form 4 " + f.accession() + " is not readable XML.");
        }
        Element root = doc.getDocumentElement();
        List<String> owners = new ArrayList<>();
        List<String> roles = new ArrayList<>();
        NodeList ro = root.getElementsByTagName("reportingOwner");
        for (int i = 0; i < ro.getLength(); i++) {
            Element o = (Element) ro.item(i);
            owners.add(text(o, "rptOwnerName"));
            List<String> r = new ArrayList<>();
            if (bool(text(o, "isDirector"))) r.add("director");
            if (bool(text(o, "isOfficer"))) r.add(text(o, "officerTitle") == null ? "officer" : text(o, "officerTitle"));
            if (bool(text(o, "isTenPercentOwner"))) r.add("10% owner");
            if (bool(text(o, "isOther"))) r.add(text(o, "otherText") == null ? "other" : text(o, "otherText"));
            roles.add(String.join(", ", r));
        }
        boolean plan = bool(text(root, "aff10b5One"));
        List<Map<String, Object>> out = new ArrayList<>();
        for (String table : List.of("nonDerivativeTransaction", "derivativeTransaction")) {
            NodeList txs = root.getElementsByTagName(table);
            for (int i = 0; i < txs.getLength(); i++) {
                Element t = (Element) txs.item(i);
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("insider", String.join("; ", owners));
                m.put("role", String.join("; ", roles));
                m.put("security", value(t, "securityTitle"));
                m.put("derivative", table.startsWith("derivative"));
                m.put("date", value(t, "transactionDate"));
                m.put("code", text(t, "transactionCode"));
                m.put("acquired_disposed", value(t, "transactionAcquiredDisposedCode"));
                m.put("shares", num(value(t, "transactionShares")));
                m.put("price", num(value(t, "transactionPricePerShare")));
                m.put("shares_after", num(value(t, "sharesOwnedFollowingTransaction")));
                m.put("ownership", value(t, "directOrIndirectOwnership"));
                m.put("plan_10b5_1", plan);
                m.put("accepted_at", f.acceptance());
                m.put("filed", f.filingDate().toString());
                m.put("url", url);
                out.add(m);
            }
        }
        return out;
    }

    /** Text of the first {@code <name>} under {@code el}. */
    private static String text(Element el, String name) {
        NodeList n = el.getElementsByTagName(name);
        if (n.getLength() == 0) return null;
        String s = n.item(0).getTextContent();
        return s == null || s.isBlank() ? null : s.strip();
    }

    /** Text of {@code <name><value>…</value></name>} (Form 4 wraps most values). */
    private static String value(Element el, String name) {
        NodeList n = el.getElementsByTagName(name);
        if (n.getLength() == 0) return null;
        Node first = n.item(0);
        if (first instanceof Element e) {
            String v = text(e, "value");
            if (v != null) return v;
        }
        String s = first.getTextContent();
        return s == null || s.isBlank() ? null : s.strip();
    }

    private static boolean bool(String s) {
        return s != null && (s.equals("1") || s.equalsIgnoreCase("true"));
    }

    private static Number num(String s) {
        if (s == null) return null;
        try {
            return new java.math.BigDecimal(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    // ── small helpers ────────────────────────────────────────────────────────

    private static Map<String, Object> schema(Map<String, Object> props, List<String> required) {
        return Map.of("type", "object", "properties", props, "required", required);
    }

    private static Map<String, Object> str(String d) {
        return Map.of("type", "string", "description", d);
    }

    private static Map<String, Object> intg(String d) {
        return Map.of("type", "integer", "description", d);
    }

    private static List<String> list(JsonNode arr) {
        List<String> out = new ArrayList<>();
        if (arr.isArray()) arr.forEach(n -> { if (!n.asText("").isBlank()) out.add(n.asText()); });
        else if (arr.isTextual() && !arr.asText().isBlank()) out.addAll(List.of(arr.asText().split(",")));
        return out;
    }

    private static Set<String> upperSet(JsonNode arr) {
        return list(arr).stream().map(s -> s.strip().toUpperCase(Locale.ROOT)).filter(s -> !s.isEmpty())
                .collect(Collectors.toCollection(java.util.LinkedHashSet::new));
    }

    private static LocalDate date(JsonNode n, String name) {
        if (n == null || n.isMissingNode() || n.isNull() || n.asText().isBlank()) return null;
        try {
            return LocalDate.parse(n.asText().strip());
        } catch (Exception e) {
            throw ConnectorException.badRequest(name + " must be YYYY-MM-DD");
        }
    }

    private static long daysBetween(String start, String end) {
        try {
            return java.time.temporal.ChronoUnit.DAYS.between(LocalDate.parse(start), LocalDate.parse(end));
        } catch (Exception e) {
            return 0;
        }
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }
}
