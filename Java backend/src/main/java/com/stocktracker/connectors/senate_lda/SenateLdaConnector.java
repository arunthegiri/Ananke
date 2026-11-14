package com.stocktracker.connectors.senate_lda;

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
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import static com.stocktracker.connectors.ToolArgs.enumStr;
import static com.stocktracker.connectors.ToolArgs.intArg;
import static com.stocktracker.connectors.ToolArgs.integer;
import static com.stocktracker.connectors.ToolArgs.object;
import static com.stocktracker.connectors.ToolArgs.oneOf;
import static com.stocktracker.connectors.ToolArgs.optStr;
import static com.stocktracker.connectors.ToolArgs.props;
import static com.stocktracker.connectors.ToolArgs.requireStr;
import static com.stocktracker.connectors.ToolArgs.str;
import static com.stocktracker.connectors.ToolArgs.url;

/**
 * Lobbying Disclosure Act filings (LD-1 registrations, LD-2 quarterly reports) from the joint
 * Senate/House API. The legacy {@code lda.senate.gov} API was sunset on 2026-07-31; the same
 * REST API v1 now lives at {@code lda.gov}. Keyless, or with a free API key for higher limits.
 *
 * <p>Point-in-time: every filing carries {@code dt_posted}, when it became public — quarterly
 * reports are due 20 days after the quarter, so use that, not the period.
 */
@Component
public class SenateLdaConnector implements Connector {

    static final String ID = "senate_lda";
    static final String BASE_URL = "https://lda.gov/api/v1";
    static final ZoneId EASTERN = ZoneId.of("America/New_York");

    static final List<String> PERIODS = List.of("first_quarter", "second_quarter", "third_quarter", "fourth_quarter",
            "mid_year", "year_end");
    static final Set<String> REGISTRATIONS = Set.of("RR", "RA");

    private static final ConnectorSpec SPEC = new ConnectorSpec(
            ID,
            "Lobbying Disclosure (LDA)",
            ConnectorSpec.Category.ALT_DATA,
            ConnectorSpec.Licence.FREE_PUBLIC,
            "Federal lobbying filings by client company: quarterly spend (in-house and outside firms), the issues "
                    + "and bills lobbied, agencies contacted and new registrations. Flags policy exposure and rising "
                    + "regulatory stakes (defense budgets, drug pricing, tariffs, AI, crypto) for a company or sector.",
            "Source: Lobbying Disclosure Act filings, U.S. Senate Office of Public Records and Clerk of the House (LDA.gov)",
            "https://lda.gov/api/redoc/v1/",
            List.of(new Field("api_key", "API key (optional)",
                    "Optional free key from lda.gov/api/register — raises the limit from 15 to 120 requests a minute. "
                            + "Leave blank to use the API anonymously.", true, true, null)),
            // Anonymous: 15/min per IP; with a key 120/min. Enforce the anonymous ceiling with headroom.
            List.of(RateLimit.perMinute(12)))
            .withKeyedLimits(List.of(RateLimit.perMinute(100)));   // documented: 15/min anonymous, 120/min with a key

    private final String base;
    private final Clock clock;

    public SenateLdaConnector() {
        this(BASE_URL, Clock.systemUTC());
    }

    /** Test seam: point at a fake LDA API. */
    SenateLdaConnector(String base, Clock clock) {
        this.base = base;
        this.clock = clock;
    }

    @Override
    public ConnectorSpec spec() {
        return SPEC;
    }

    @Override
    public String test(ConnectorContext ctx) {
        int year = year();
        JsonNode d = ctx.getJson(URI.create(url(base + "/filings/", "filing_year", year, "page_size", 1)), headers(ctx));
        return "Connected — LDA.gov answered (" + d.path("count").asInt() + " filings for " + year + ", "
                + (ctx.field("api_key").isPresent() ? "with your API key" : "anonymous: 15 requests/min") + ").";
    }

    @Override
    public List<ConnectorTool> tools() {
        Map<String, Object> client = str("Client (company) name, matched as a substring, e.g. \"Lockheed\", \"Pfizer\"");
        return List.of(
                new ConnectorTool(ID + "_filings",
                        "Recent lobbying filings for a client company, newest first: who lobbied (in-house or outside firm), "
                                + "amount (income or expenses), issue codes, the specific issues/bills, agencies contacted, "
                                + "when it was posted and a link to the filing.",
                        object(props(
                                "client", client,
                                "year", integer("Filing year (default: current year)"),
                                "period", enumStr("Filing period", PERIODS),
                                "issue", str("Text to find in the specific lobbying issues, e.g. \"NDAA\", \"drug pricing\" (quote phrases)"),
                                "issue_code", str("General issue code, e.g. DEF, HCR, TAX, TRD, BUD, PHA, CPI, SCI"),
                                "kind", enumStr("reports (default), registrations or all", List.of("reports", "registrations", "all")),
                                "limit", integer("Max filings (1-25, default 10)")), List.of("client")),
                        Duration.ofHours(6), this::filings,
                        a -> "Reading lobbying filings for " + a.path("client").asText("") + "…"),
                new ConnectorTool(ID + "_spend",
                        "Lobbying spend for a client company per year and quarter (amendments de-duplicated), split by "
                                + "lobbying firm, with the issue codes lobbied most. Up to 2 years per call.",
                        object(props(
                                "client", client,
                                "year", integer("Last year to include (default: current year)"),
                                "years", integer("How many years ending at `year` (1-2, default 2)")), List.of("client")),
                        Duration.ofHours(12), this::spend,
                        a -> "Totalling lobbying spend for " + a.path("client").asText("") + "…"));
    }

    // ── tools ────────────────────────────────────────────────────────────────

    Object filings(ConnectorContext ctx, JsonNode args) {
        String client = clientArg(args);
        int year = yearArg(args);
        String period = oneOf(args, "period", null, PERIODS);
        String issue = optStr(args, "issue");
        if (issue != null && issue.length() > 200) throw ConnectorException.badRequest("issue is too long (max 200 characters)");
        String code = issueCode(args);
        String kind = oneOf(args, "kind", "reports", List.of("reports", "registrations", "all"));
        int limit = intArg(args, "limit", 10, 1, 25);
        JsonNode d = ctx.getJson(URI.create(url(base + "/filings/", "client_name", client, "filing_year", year,
                "filing_period", period, "filing_specific_lobbying_issues", issue, "ordering", "-dt_posted",
                "page_size", 25)), headers(ctx));
        List<Map<String, Object>> rows = new ArrayList<>();
        for (JsonNode f : d.path("results")) {
            if (rows.size() >= limit) break;
            boolean reg = REGISTRATIONS.contains(f.path("filing_type").asText());
            if (kind.equals("reports") && reg || kind.equals("registrations") && !reg) continue;
            if (code != null && !codes(f).contains(code)) continue;
            rows.add(filingRow(f));
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("client", client);
        out.put("year", year);
        out.put("matching_filings", d.path("count").asInt());
        out.put("scanned", d.path("results").size());
        out.put("count", rows.size());
        out.put("filings", rows);
        out.put("note", "amount = expenses for in-house (self-filed) reports, income for outside firms. "
                + "posted_at is when the filing became public.");
        return out;
    }

    Object spend(ConnectorContext ctx, JsonNode args) {
        String client = clientArg(args);
        int last = yearArg(args);
        int years = intArg(args, "years", 2, 1, 2);
        Map<String, JsonNode> latest = new HashMap<>();   // one report per registrant/client/year/period: the latest amendment
        Map<Integer, Integer> seen = new TreeMap<>();
        boolean truncated = false;
        for (int y = last - years + 1; y <= last; y++) {
            int page = 1;
            while (true) {
                JsonNode d = ctx.getJson(URI.create(url(base + "/filings/", "client_name", client, "filing_year", y,
                        "page_size", 25, "page", page)), headers(ctx));
                for (JsonNode f : d.path("results")) {
                    seen.merge(y, 1, Integer::sum);
                    if (REGISTRATIONS.contains(f.path("filing_type").asText())) continue;
                    String key = f.path("registrant").path("id").asText() + "|" + f.path("client").path("id").asText()
                            + "|" + y + "|" + f.path("filing_period").asText();
                    JsonNode prev = latest.get(key);
                    if (prev == null || f.path("dt_posted").asText().compareTo(prev.path("dt_posted").asText()) > 0) latest.put(key, f);
                }
                boolean more = d.hasNonNull("next") && !d.path("next").asText().isEmpty();
                if (!more) break;
                if (page >= 3) {
                    truncated = true;
                    break;
                }
                page++;
            }
        }

        Map<Integer, Map<String, Double>> byYear = new TreeMap<>();
        Map<String, Double> byRegistrant = new HashMap<>();
        Map<String, Integer> issues = new HashMap<>();
        Map<String, String> issueNames = new HashMap<>();
        Map<String, String> lastPosted = new HashMap<>();
        for (JsonNode f : latest.values()) {
            Double amt = amount(f);
            int y = f.path("filing_year").asInt();
            String period = f.path("filing_period").asText();
            byYear.computeIfAbsent(y, k -> new TreeMap<>(Comparator.comparingInt(PERIODS::indexOf)))
                    .merge(period, amt == null ? 0 : amt, Double::sum);
            String reg = f.path("registrant").path("name").asText("?");
            byRegistrant.merge(reg, amt == null ? 0 : amt, Double::sum);
            for (JsonNode a : f.path("lobbying_activities")) {
                String c = a.path("general_issue_code").asText("");
                if (c.isEmpty()) continue;
                issues.merge(c, 1, Integer::sum);
                issueNames.putIfAbsent(c, a.path("general_issue_code_display").asText(c));
            }
            String posted = f.path("dt_posted").asText("");
            lastPosted.merge("max", posted, (x, z) -> x.compareTo(z) >= 0 ? x : z);
        }

        List<Map<String, Object>> yearRows = new ArrayList<>();
        byYear.forEach((y, periods) -> {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("year", y);
            r.put("total", round(periods.values().stream().mapToDouble(Double::doubleValue).sum()));
            Map<String, Object> p = new LinkedHashMap<>();
            periods.forEach((k, v) -> p.put(k, round(v)));
            r.put("by_period", p);
            yearRows.add(r);
        });
        List<Map<String, Object>> firms = byRegistrant.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed()).limit(10)
                .map(e -> {
                    Map<String, Object> r = new LinkedHashMap<>();
                    r.put("registrant", e.getKey());
                    r.put("amount", round(e.getValue()));
                    return r;
                }).toList();
        List<Map<String, Object>> topIssues = issues.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed()).limit(10)
                .map(e -> {
                    Map<String, Object> r = new LinkedHashMap<>();
                    r.put("code", e.getKey());
                    r.put("issue", issueNames.get(e.getKey()));
                    r.put("reports", e.getValue());
                    return r;
                }).toList();

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("client", client);
        out.put("years", yearRows);
        out.put("by_registrant", firms);
        out.put("top_issues", topIssues);
        out.put("reports_counted", latest.size());
        out.put("filings_scanned", seen);
        out.put("latest_posted_at", lastPosted.get("max"));
        out.put("truncated", truncated);
        out.put("note", "Sums the latest version of each quarterly report (in-house expenses + outside-firm income). "
                + "client is a substring match, so similarly named clients are included — check by_registrant. "
                + (truncated ? "Truncated: more than 75 filings in a year; totals are partial." : ""));
        return out;
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    Map<String, Object> filingRow(JsonNode f) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("posted_at", text(f, "dt_posted"));
        r.put("type", text(f, "filing_type_display"));
        r.put("year", f.path("filing_year").asInt());
        r.put("period", text(f, "filing_period"));
        r.put("client", f.path("client").path("name").asText(null));
        r.put("registrant", f.path("registrant").path("name").asText(null));
        r.put("amount", amount(f));
        List<Map<String, Object>> acts = new ArrayList<>();
        for (JsonNode a : f.path("lobbying_activities")) {
            if (acts.size() >= 8) break;
            Map<String, Object> x = new LinkedHashMap<>();
            x.put("code", text(a, "general_issue_code"));
            x.put("issue", text(a, "general_issue_code_display"));
            x.put("specific", trim(text(a, "description"), 240));
            List<String> ents = new ArrayList<>();
            for (JsonNode g : a.path("government_entities")) if (ents.size() < 6) ents.add(g.path("name").asText());
            x.put("contacted", ents);
            x.put("lobbyists", a.path("lobbyists").size());
            acts.add(x);
        }
        r.put("activities", acts);
        if (f.path("lobbying_activities").size() > acts.size()) r.put("more_activities", f.path("lobbying_activities").size() - acts.size());
        r.put("termination_date", text(f, "termination_date"));
        r.put("url", text(f, "filing_document_url"));
        return r;
    }

    static List<String> codes(JsonNode f) {
        List<String> out = new ArrayList<>();
        for (JsonNode a : f.path("lobbying_activities")) out.add(a.path("general_issue_code").asText());
        return out;
    }

    static Double amount(JsonNode f) {
        Double e = dec(f.path("expenses"));
        return e != null ? e : dec(f.path("income"));
    }

    static Double dec(JsonNode n) {
        if (n == null || n.isNull() || n.isMissingNode()) return null;
        if (n.isNumber()) return n.asDouble();
        try {
            return Double.valueOf(n.asText());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    Map<String, String> headers(ConnectorContext ctx) {
        return ctx.field("api_key").map(k -> Map.of("Authorization", "Token " + k)).orElse(Map.of());
    }

    int year() {
        return LocalDate.now(clock.withZone(EASTERN)).getYear();
    }

    int yearArg(JsonNode args) {
        JsonNode n = args == null ? null : args.get("year");
        if (n == null || n.isNull()) return year();
        int y = n.asInt(-1);
        if (y < 1999 || y > year()) throw ConnectorException.badRequest("year must be between 1999 and " + year());
        return y;
    }

    static String clientArg(JsonNode args) {
        String c = requireStr(args, "client", 100);
        if (c.length() < 2) throw ConnectorException.badRequest("client must be at least 2 characters");
        return c;
    }

    static String issueCode(JsonNode args) {
        String c = optStr(args, "issue_code");
        if (c == null) return null;
        c = c.toUpperCase(Locale.ROOT);
        if (!c.matches("^[A-Z]{3}$")) throw ConnectorException.badRequest("issue_code must be a 3-letter LDA code like DEF or HCR");
        return c;
    }

    static String text(JsonNode n, String field) {
        JsonNode v = n.path(field);
        if (v.isMissingNode() || v.isNull()) return null;
        String s = v.asText("").strip();
        return s.isEmpty() ? null : s;
    }

    static String trim(String s, int max) {
        if (s == null) return null;
        String t = s.replaceAll("\\s+", " ").strip();
        return t.length() > max ? t.substring(0, max) + "…" : t;
    }

    static double round(double v) {
        return Math.round(v * 100) / 100.0;
    }
}
