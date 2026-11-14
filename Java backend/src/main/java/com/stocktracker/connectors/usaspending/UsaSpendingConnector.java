package com.stocktracker.connectors.usaspending;

import com.fasterxml.jackson.databind.JsonNode;
import com.stocktracker.connectors.Connector;
import com.stocktracker.connectors.ConnectorContext;
import com.stocktracker.connectors.ConnectorException;
import com.stocktracker.connectors.ConnectorSpec;
import com.stocktracker.connectors.ConnectorSpec.RateLimit;
import com.stocktracker.connectors.ConnectorTool;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.stocktracker.connectors.ToolArgs.date;
import static com.stocktracker.connectors.ToolArgs.enumStr;
import static com.stocktracker.connectors.ToolArgs.intArg;
import static com.stocktracker.connectors.ToolArgs.integer;
import static com.stocktracker.connectors.ToolArgs.object;
import static com.stocktracker.connectors.ToolArgs.oneOf;
import static com.stocktracker.connectors.ToolArgs.props;
import static com.stocktracker.connectors.ToolArgs.requireStr;
import static com.stocktracker.connectors.ToolArgs.str;

/**
 * USAspending.gov (U.S. Treasury): federal contract awards and obligations by recipient — a demand
 * signal for defense, government-IT, health and infrastructure contractors. Keyless.
 *
 * <p>Point-in-time: awards carry their base obligation date and USAspending's last-modified time;
 * transactions their action date. Agencies report with a lag (DoD contract data up to ~90 days),
 * so a recent window under-counts.
 */
@Component
public class UsaSpendingConnector implements Connector {

    static final String ID = "usaspending";
    static final String BASE_URL = "https://api.usaspending.gov";
    static final LocalDate EARLIEST = LocalDate.of(2007, 10, 1);
    static final ZoneId EASTERN = ZoneId.of("America/New_York");

    static final Map<String, List<String>> TYPES = Map.of(
            "contracts", List.of("A", "B", "C", "D"),
            "idvs", List.of("IDV_A", "IDV_B", "IDV_B_A", "IDV_B_B", "IDV_B_C", "IDV_C", "IDV_D", "IDV_E"),
            "grants", List.of("02", "03", "04", "05"));

    private static final ConnectorSpec SPEC = new ConnectorSpec(
            ID,
            "USAspending.gov",
            ConnectorSpec.Category.ALT_DATA,
            ConnectorSpec.Licence.FREE_PUBLIC,
            "Federal contract awards, task orders and obligations by company and time window, and which agencies "
                    + "pay them. Tracks government demand for defense, gov-IT, healthcare and infrastructure names — "
                    + "new large awards, an agency ramping or cutting spend, contract-backlog trends.",
            "Source: USAspending.gov, U.S. Department of the Treasury",
            "https://api.usaspending.gov/docs/endpoints",
            List.of(),
            // No published limit; searches are heavy, so stay polite.
            List.of(RateLimit.perSecond(2), RateLimit.perMinute(60)));

    private final String base;
    private final Clock clock;

    public UsaSpendingConnector() {
        this(BASE_URL, Clock.systemUTC());
    }

    /** Test seam: point at a fake USAspending. */
    UsaSpendingConnector(String base, Clock clock) {
        this.base = base;
        this.clock = clock;
    }

    @Override
    public ConnectorSpec spec() {
        return SPEC;
    }

    @Override
    public String test(ConnectorContext ctx) {
        JsonNode d = ctx.getJson(URI.create(base + "/api/v2/awards/last_updated/"));
        return "Connected — USAspending answered (data last updated " + d.path("last_updated").asText("?") + ").";
    }

    @Override
    public List<ConnectorTool> tools() {
        Map<String, Object> recipient = str("Company / recipient name to match, e.g. \"Palantir\", \"Lockheed Martin\"");
        Map<String, Object> from = str("Window start YYYY-MM-DD (default: 1 year before `to`; earliest 2007-10-01)");
        Map<String, Object> to = str("Window end YYYY-MM-DD (default: today)");
        Map<String, Object> types = enumStr("contracts (default), idvs (IDIQ/GWAC vehicles) or grants", List.of("contracts", "idvs", "grants"));
        return List.of(
                new ConnectorTool(ID + "_awards",
                        "Federal awards to a company with activity in a date window: award id, total amount, outlays, "
                                + "agency/sub-agency, description, NAICS/PSC, period of performance, base obligation date and "
                                + "last-modified time. Sort by amount (default) or most recent.",
                        object(props("recipient", recipient, "from", from, "to", to, "award_type", types,
                                "sort", enumStr("amount (default) or recent", List.of("amount", "recent")),
                                "limit", integer("Max awards (1-25, default 10)")), List.of("recipient")),
                        Duration.ofHours(6), this::awards,
                        a -> "Searching USAspending awards for " + a.path("recipient").asText("") + "…"),
                new ConnectorTool(ID + "_transactions",
                        "Individual contract actions (new awards, modifications, de-obligations) for a company in a window, "
                                + "each with its action date and obligated amount — the freshest signal of new government money.",
                        object(props("recipient", recipient, "from", from, "to", to, "award_type", types,
                                "sort", enumStr("amount (default) or recent", List.of("amount", "recent")),
                                "limit", integer("Max actions (1-25, default 10)")), List.of("recipient")),
                        Duration.ofHours(6), this::transactions,
                        a -> "Reading USAspending contract actions for " + a.path("recipient").asText("") + "…"),
                new ConnectorTool(ID + "_agencies",
                        "Which federal agencies obligated money to a company in a window, ranked by amount (concentration "
                                + "and customer-mix signal), plus the total.",
                        object(props("recipient", recipient, "from", from, "to", to, "award_type", types,
                                "limit", integer("Max agencies (1-15, default 8)")), List.of("recipient")),
                        Duration.ofHours(6), this::agencies,
                        a -> "Ranking agencies paying " + a.path("recipient").asText("") + "…"),
                new ConnectorTool(ID + "_recipients",
                        "Recipient entities matching a name (parent and subsidiaries, each UEI separately) with the amount "
                                + "obligated to each in the window — use it to check which legal entities a search covers.",
                        object(props("recipient", recipient, "from", from, "to", to, "award_type", types,
                                "limit", integer("Max entities (1-20, default 10)")), List.of("recipient")),
                        Duration.ofHours(6), this::recipients,
                        a -> "Matching USAspending recipients for " + a.path("recipient").asText("") + "…"));
    }

    // ── tools ────────────────────────────────────────────────────────────────

    Object awards(ConnectorContext ctx, JsonNode args) {
        Query q = query(args);
        int limit = intArg(args, "limit", 10, 1, 25);
        boolean recent = oneOf(args, "sort", "amount", List.of("amount", "recent")).equals("recent");
        boolean contracts = !q.type().equals("grants");
        List<String> fields = new ArrayList<>(List.of("Award ID", "Recipient Name", "Award Amount", "Total Outlays", "Description",
                "Start Date", "End Date", "Awarding Agency", "Awarding Sub Agency", "Last Modified Date", "Base Obligation Date",
                "generated_internal_id"));
        if (contracts) fields.addAll(List.of("Contract Award Type", "NAICS", "PSC"));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("filters", q.filters());
        body.put("fields", fields);
        body.put("limit", limit);
        body.put("page", 1);
        body.put("sort", recent ? "Base Obligation Date" : "Award Amount");
        body.put("order", "desc");
        JsonNode d = ctx.postJson(URI.create(base + "/api/v2/search/spending_by_award/"), Map.of(), body);
        List<Map<String, Object>> rows = new ArrayList<>();
        for (JsonNode a : d.path("results")) {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("award_id", text(a, "Award ID"));
            r.put("recipient", text(a, "Recipient Name"));
            r.put("amount", num(a, "Award Amount"));
            r.put("outlays", num(a, "Total Outlays"));
            r.put("agency", text(a, "Awarding Agency"));
            r.put("sub_agency", text(a, "Awarding Sub Agency"));
            if (contracts) r.put("type", text(a, "Contract Award Type"));
            r.put("description", trim(text(a, "Description"), 200));
            if (contracts) {
                r.put("naics", codeDesc(a.path("NAICS")));
                r.put("psc", codeDesc(a.path("PSC")));
            }
            r.put("start", text(a, "Start Date"));
            r.put("end", text(a, "End Date"));
            r.put("base_obligation_date", text(a, "Base Obligation Date"));
            r.put("last_modified", text(a, "Last Modified Date"));
            String gid = text(a, "generated_internal_id");
            r.put("url", gid == null ? null : "https://www.usaspending.gov/award/" + gid);
            rows.add(r);
        }
        Map<String, Object> out = header(q);
        out.put("sort", recent ? "recent" : "amount");
        out.put("has_more", d.path("page_metadata").path("hasNext").asBoolean(false));
        out.put("count", rows.size());
        out.put("awards", rows);
        out.put("note", "amount = the award's total obligated value to date, not just within the window.");
        return out;
    }

    Object transactions(ConnectorContext ctx, JsonNode args) {
        Query q = query(args);
        int limit = intArg(args, "limit", 10, 1, 25);
        boolean recent = oneOf(args, "sort", "amount", List.of("amount", "recent")).equals("recent");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("filters", q.filters());
        body.put("fields", List.of("Award ID", "Recipient Name", "Action Date", "Transaction Amount", "Awarding Agency",
                "Awarding Sub Agency", "Transaction Description", "Mod", "Award Type", "generated_internal_id"));
        body.put("limit", limit);
        body.put("page", 1);
        body.put("sort", recent ? "Action Date" : "Transaction Amount");
        body.put("order", "desc");
        JsonNode d = ctx.postJson(URI.create(base + "/api/v2/search/spending_by_transaction/"), Map.of(), body);
        List<Map<String, Object>> rows = new ArrayList<>();
        for (JsonNode a : d.path("results")) {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("action_date", text(a, "Action Date"));
            r.put("amount", num(a, "Transaction Amount"));
            r.put("award_id", text(a, "Award ID"));
            r.put("modification", text(a, "Mod"));
            r.put("type", text(a, "Award Type"));
            r.put("recipient", text(a, "Recipient Name"));
            r.put("agency", text(a, "Awarding Agency"));
            r.put("sub_agency", text(a, "Awarding Sub Agency"));
            r.put("description", trim(text(a, "Transaction Description"), 200));
            String gid = text(a, "generated_internal_id");
            r.put("url", gid == null ? null : "https://www.usaspending.gov/award/" + gid);
            rows.add(r);
        }
        Map<String, Object> out = header(q);
        out.put("sort", recent ? "recent" : "amount");
        out.put("has_more", d.path("page_metadata").path("hasNext").asBoolean(false));
        out.put("count", rows.size());
        out.put("transactions", rows);
        out.put("note", "modification 0 = the original award; negative amounts are de-obligations.");
        return out;
    }

    Object agencies(ConnectorContext ctx, JsonNode args) {
        Query q = query(args);
        int limit = intArg(args, "limit", 8, 1, 15);
        JsonNode d = category(ctx, "awarding_agency", q, 100);
        double total = 0;
        List<Map<String, Object>> rows = new ArrayList<>();
        for (JsonNode a : d.path("results")) {
            double amt = a.path("amount").asDouble(0);
            total += amt;
            if (rows.size() >= limit) continue;
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("agency", text(a, "name"));
            r.put("code", text(a, "code"));
            r.put("amount", amt);
            rows.add(r);
        }
        for (Map<String, Object> r : rows) r.put("share", total == 0 ? null : Math.round((double) r.get("amount") / total * 1000) / 1000.0);
        Map<String, Object> out = header(q);
        out.put("total_obligated", total);
        out.put("agency_count", d.path("results").size());
        out.put("agencies", rows);
        out.put("note", "Amounts are obligations dated inside the window (all agencies' transactions summed).");
        return out;
    }

    Object recipients(ConnectorContext ctx, JsonNode args) {
        Query q = query(args);
        int limit = intArg(args, "limit", 10, 1, 20);
        JsonNode d = category(ctx, "recipient", q, limit);
        List<Map<String, Object>> rows = new ArrayList<>();
        for (JsonNode a : d.path("results")) {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("name", text(a, "name"));
            r.put("uei", text(a, "uei"));
            r.put("amount", num(a, "amount"));
            String id = text(a, "recipient_id");
            r.put("url", id == null ? null : "https://www.usaspending.gov/recipient/" + id + "/latest");
            rows.add(r);
        }
        Map<String, Object> out = header(q);
        out.put("count", rows.size());
        out.put("recipients", rows);
        return out;
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    record Query(String recipient, LocalDate from, LocalDate to, String type, Map<String, Object> filters) { }

    Query query(JsonNode args) {
        String recipient = requireStr(args, "recipient", 100);
        if (recipient.length() < 3) throw ConnectorException.badRequest("recipient must be at least 3 characters");
        LocalDate to = date(args, "to");
        if (to == null) to = LocalDate.now(clock.withZone(EASTERN));
        LocalDate from = date(args, "from");
        if (from == null) from = to.minusYears(1);
        if (from.isBefore(EARLIEST)) throw ConnectorException.badRequest("from must be on or after 2007-10-01 (USAspending search limit)");
        if (from.isAfter(to)) throw ConnectorException.badRequest("from must be on or before to");
        String type = oneOf(args, "award_type", "contracts", List.of("contracts", "idvs", "grants"));
        Map<String, Object> period = new LinkedHashMap<>();
        period.put("start_date", from.toString());
        period.put("end_date", to.toString());
        Map<String, Object> filters = new LinkedHashMap<>();
        filters.put("recipient_search_text", List.of(recipient));
        filters.put("time_period", List.of(period));
        filters.put("award_type_codes", TYPES.get(type));
        return new Query(recipient, from, to, type, filters);
    }

    JsonNode category(ConnectorContext ctx, String category, Query q, int limit) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("filters", q.filters());
        body.put("limit", limit);
        body.put("page", 1);
        return ctx.postJson(URI.create(base + "/api/v2/search/spending_by_category/" + category + "/"), Map.of(), body);
    }

    static Map<String, Object> header(Query q) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("recipient", q.recipient());
        out.put("from", q.from().toString());
        out.put("to", q.to().toString());
        out.put("award_type", q.type());
        return out;
    }

    static String codeDesc(JsonNode n) {
        if (n == null || n.isMissingNode() || n.isNull()) return null;
        if (n.isTextual()) return n.asText();
        String code = n.path("code").asText("");
        String desc = n.path("description").asText("");
        String s = (code + " " + desc).strip();
        return s.isEmpty() ? null : trim(s, 100);
    }

    static String text(JsonNode n, String field) {
        JsonNode v = n.path(field);
        if (v.isMissingNode() || v.isNull()) return null;
        String s = v.asText("").strip();
        return s.isEmpty() ? null : s;
    }

    static Double num(JsonNode n, String field) {
        JsonNode v = n.path(field);
        return v.isNumber() ? v.asDouble() : null;
    }

    static String trim(String s, int max) {
        if (s == null) return null;
        String t = s.replaceAll("\\s+", " ").strip();
        return t.length() > max ? t.substring(0, max) + "…" : t;
    }
}
