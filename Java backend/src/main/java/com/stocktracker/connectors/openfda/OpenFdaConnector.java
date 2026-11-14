package com.stocktracker.connectors.openfda;

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
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

import static com.stocktracker.connectors.ToolArgs.bool;
import static com.stocktracker.connectors.ToolArgs.date;
import static com.stocktracker.connectors.ToolArgs.enumStr;
import static com.stocktracker.connectors.ToolArgs.intArg;
import static com.stocktracker.connectors.ToolArgs.integer;
import static com.stocktracker.connectors.ToolArgs.object;
import static com.stocktracker.connectors.ToolArgs.oneOf;
import static com.stocktracker.connectors.ToolArgs.optStr;
import static com.stocktracker.connectors.ToolArgs.props;
import static com.stocktracker.connectors.ToolArgs.str;
import static com.stocktracker.connectors.ToolArgs.url;

/**
 * openFDA: Drugs@FDA approvals (original and supplemental — new indications), recalls /
 * enforcement reports, and FAERS adverse-event counts. Keyless; an optional free key raises the
 * daily quota.
 *
 * <p>Point-in-time: approvals carry the submission status date (and the action-letter date),
 * recalls their report date, adverse events their FDA receive date; every result also carries
 * openFDA's {@code last_updated} for the dataset.
 */
@Component
public class OpenFdaConnector implements Connector {

    static final String ID = "openfda";
    static final String BASE_URL = "https://api.fda.gov";
    static final ZoneId EASTERN = ZoneId.of("America/New_York");
    static final DateTimeFormatter BASIC = DateTimeFormatter.BASIC_ISO_DATE;

    private static final ConnectorSpec SPEC = new ConnectorSpec(
            ID,
            "openFDA",
            ConnectorSpec.Category.ALT_DATA,
            ConnectorSpec.Licence.FREE_PUBLIC,
            "FDA drug approvals (new drugs, new indications, generics) by company or drug, product recalls and "
                    + "enforcement actions, and adverse-event report trends — biotech/pharma catalysts and safety "
                    + "overhangs. Not for medical decisions; openFDA data is unvalidated.",
            "Source: U.S. Food and Drug Administration, openFDA (open.fda.gov). Not validated for clinical use.",
            "https://open.fda.gov/apis/",
            List.of(new Field("api_key", "API key (optional)",
                    "Optional free key from open.fda.gov/apis/authentication — raises the daily limit from 1,000 to "
                            + "120,000 requests. Leave blank to use openFDA without one.", true, true, null)),
            // Documented: 240 requests/min per IP; 1,000/day without a key (120,000/day with one).
            List.of(RateLimit.perMinute(60), RateLimit.perDay(1000)));

    private final String base;
    private final Clock clock;

    public OpenFdaConnector() {
        this(BASE_URL, Clock.systemUTC());
    }

    /** Test seam: point at a fake openFDA. */
    OpenFdaConnector(String base, Clock clock) {
        this.base = base;
        this.clock = clock;
    }

    @Override
    public ConnectorSpec spec() {
        return SPEC;
    }

    @Override
    public String test(ConnectorContext ctx) {
        JsonNode d = fetch(ctx, "/drug/drugsfda.json", "search", "sponsor_name:PFIZER", "limit", 1);
        return "Connected — openFDA answered (Drugs@FDA updated " + d.path("meta").path("last_updated").asText("?")
                + (ctx.field("api_key").isPresent() ? ", with your API key" : ", keyless: 1,000 requests/day") + ").";
    }

    @Override
    public List<ConnectorTool> tools() {
        Map<String, Object> company = str("Company / sponsor name, e.g. \"Vertex\", \"Eli Lilly\"");
        Map<String, Object> drug = str("Brand or generic drug name, e.g. \"Mounjaro\", \"semaglutide\"");
        return List.of(
                new ConnectorTool(ID + "_approvals",
                        "FDA approval actions (Drugs@FDA) for a company or drug in a window, newest first: original approvals "
                                + "and supplements (efficacy = new indication, labeling, manufacturing), review priority, the "
                                + "action date and links to the approval letter / label. NDAs and BLAs by default.",
                        object(props(
                                "company", company, "drug", drug,
                                "since", str("Only actions on/after YYYY-MM-DD (default 1 year ago)"),
                                "until", str("Only actions on/before YYYY-MM-DD (default today)"),
                                "kind", enumStr("all (default), original (new approvals only) or efficacy (new indications + originals)",
                                        List.of("all", "original", "efficacy")),
                                "include_generics", bool("Include ANDA generic approvals (default false)"),
                                "limit", integer("Max actions (1-30, default 15)")), List.of()),
                        Duration.ofHours(6), this::approvals,
                        a -> "Reading FDA approvals…"),
                new ConnectorTool(ID + "_recalls",
                        "FDA recalls / enforcement reports for a company or product, newest first: class (I = most serious), "
                                + "status, reason, product, distribution and the report date.",
                        object(props(
                                "company", str("Recalling firm, e.g. \"Pfizer\""),
                                "product", str("Text in the product description, e.g. a drug or device name"),
                                "category", enumStr("drug (default), device or food", List.of("drug", "device", "food")),
                                "since", str("Only reports on/after YYYY-MM-DD (default 2 years ago)"),
                                "limit", integer("Max recalls (1-25, default 10)")), List.of()),
                        Duration.ofHours(6), this::recalls,
                        a -> "Reading FDA recalls…"),
                new ConnectorTool(ID + "_adverse_events",
                        "FAERS adverse-event reports for a drug (or a company's drugs) in a window: total, serious share, "
                                + "monthly trend and the most reported reactions (or, for a company, its most reported drugs). "
                                + "Counts are reports, not incidence; reporting spikes after publicity.",
                        object(props(
                                "drug", drug, "company", company,
                                "since", str("Received on/after YYYY-MM-DD (default 1 year ago)"),
                                "until", str("Received on/before YYYY-MM-DD (default today)"),
                                "limit", integer("Top reactions / drugs to list (1-25, default 10)")), List.of()),
                        Duration.ofHours(12), this::adverseEvents,
                        a -> "Counting FDA adverse-event reports…"));
    }

    // ── tools ────────────────────────────────────────────────────────────────

    Object approvals(ConnectorContext ctx, JsonNode args) {
        String company = name(args, "company");
        String drug = name(args, "drug");
        if (company == null && drug == null) throw ConnectorException.badRequest("Give a company or a drug");
        LocalDate until = dateOr(args, "until", today());
        LocalDate since = dateOr(args, "since", until.minusYears(1));
        if (since.isAfter(until)) throw ConnectorException.badRequest("since must be on or before until");
        String kind = oneOf(args, "kind", "all", List.of("all", "original", "efficacy"));
        boolean generics = args != null && args.path("include_generics").asBoolean(false);
        int limit = intArg(args, "limit", 15, 1, 30);

        List<String> clauses = new ArrayList<>();
        if (company != null) clauses.add(companyClause(company));
        if (drug != null) clauses.add(drugClause("openfda.", drug));
        if (!generics) clauses.add("(application_number:NDA* OR application_number:BLA*)");
        clauses.add("submissions.submission_status_date:[" + BASIC.format(since) + " TO " + BASIC.format(until) + "]");
        JsonNode d = fetchOrEmpty(ctx, "/drug/drugsfda.json", "search", String.join(" AND ", clauses), "limit", 100,
                "sort", "submissions.submission_status_date:desc");

        List<Map<String, Object>> rows = new ArrayList<>();
        for (JsonNode app : d.path("results")) {
            for (JsonNode s : app.path("submissions")) {
                LocalDate when = basic(s.path("submission_status_date").asText());
                if (when == null || when.isBefore(since) || when.isAfter(until)) continue;
                if (!"AP".equals(s.path("submission_status").asText()) && !"TA".equals(s.path("submission_status").asText())) continue;
                String type = s.path("submission_type").asText();
                String cls = s.path("submission_class_code").asText("");
                if (kind.equals("original") && !type.equals("ORIG")) continue;
                if (kind.equals("efficacy") && !type.equals("ORIG") && !cls.equals("EFFICACY")) continue;
                Map<String, Object> r = new LinkedHashMap<>();
                r.put("action_date", when.toString());
                r.put("status", "TA".equals(s.path("submission_status").asText()) ? "tentative approval" : "approved");
                r.put("application", app.path("application_number").asText());
                r.put("sponsor", app.path("sponsor_name").asText());
                r.put("brand", first(app.path("openfda").path("brand_name"), firstProduct(app, "brand_name")));
                r.put("generic", first(app.path("openfda").path("generic_name"), null));
                r.put("submission", type + " " + s.path("submission_number").asText(""));
                r.put("class", text(s, "submission_class_code_description"));
                r.put("priority", text(s, "review_priority"));
                String letter = null, label = null, published = null;
                for (JsonNode doc : s.path("application_docs")) {
                    String t = doc.path("type").asText("");
                    if (t.equals("Letter") && letter == null) letter = text(doc, "url");
                    if (t.equals("Label") && label == null) label = text(doc, "url");
                    LocalDate dd = basic(doc.path("date").asText());
                    if (dd != null && (published == null || dd.toString().compareTo(published) < 0)) published = dd.toString();
                }
                r.put("docs_posted", published);
                r.put("letter_url", letter);
                r.put("label_url", label);
                rows.add(r);
            }
        }
        rows.sort((a, b) -> ((String) b.get("action_date")).compareTo((String) a.get("action_date")));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("company", company);
        out.put("drug", drug);
        out.put("since", since.toString());
        out.put("until", until.toString());
        out.put("applications_matched", d.path("meta").path("results").path("total").asInt(0));
        out.put("count", Math.min(limit, rows.size()));
        out.put("approvals", rows.subList(0, Math.min(limit, rows.size())));
        out.put("data_updated", text(d.path("meta"), "last_updated"));
        out.put("note", "action_date = FDA action (submission status) date; class Efficacy = new indication / population.");
        return out;
    }

    Object recalls(ConnectorContext ctx, JsonNode args) {
        String company = name(args, "company");
        String product = name(args, "product");
        if (company == null && product == null) throw ConnectorException.badRequest("Give a company or a product");
        String category = oneOf(args, "category", "drug", List.of("drug", "device", "food"));
        LocalDate until = today();
        LocalDate since = dateOr(args, "since", until.minusYears(2));
        if (since.isAfter(until)) throw ConnectorException.badRequest("since must not be in the future");
        int limit = intArg(args, "limit", 10, 1, 25);
        List<String> clauses = new ArrayList<>();
        if (company != null) clauses.add("recalling_firm:\"" + company + "\"");
        if (product != null) clauses.add("product_description:\"" + product + "\"");
        clauses.add("report_date:[" + BASIC.format(since) + " TO " + BASIC.format(until) + "]");
        JsonNode d = fetchOrEmpty(ctx, "/" + category + "/enforcement.json", "search", String.join(" AND ", clauses),
                "limit", limit, "sort", "report_date:desc");
        List<Map<String, Object>> rows = new ArrayList<>();
        for (JsonNode r : d.path("results")) {
            Map<String, Object> x = new LinkedHashMap<>();
            x.put("report_date", iso(r.path("report_date").asText()));
            x.put("initiated", iso(r.path("recall_initiation_date").asText()));
            x.put("classification", text(r, "classification"));
            x.put("status", text(r, "status"));
            x.put("firm", text(r, "recalling_firm"));
            x.put("product", trim(text(r, "product_description"), 200));
            x.put("reason", trim(text(r, "reason_for_recall"), 240));
            x.put("quantity", trim(text(r, "product_quantity"), 80));
            x.put("distribution", trim(text(r, "distribution_pattern"), 120));
            x.put("voluntary", text(r, "voluntary_mandated"));
            x.put("recall_number", text(r, "recall_number"));
            String brand = first(r.path("openfda").path("brand_name"), null);
            if (brand != null) x.put("brand", brand);
            rows.add(x);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("category", category);
        out.put("company", company);
        out.put("product", product);
        out.put("since", since.toString());
        out.put("total", d.path("meta").path("results").path("total").asInt(0));
        out.put("count", rows.size());
        out.put("recalls", rows);
        out.put("data_updated", text(d.path("meta"), "last_updated"));
        return out;
    }

    Object adverseEvents(ConnectorContext ctx, JsonNode args) {
        String drug = name(args, "drug");
        String company = name(args, "company");
        if (company == null && drug == null) throw ConnectorException.badRequest("Give a drug or a company");
        LocalDate until = dateOr(args, "until", today());
        LocalDate since = dateOr(args, "since", until.minusYears(1));
        if (since.isAfter(until)) throw ConnectorException.badRequest("since must be on or before until");
        if (since.isBefore(until.minusYears(5))) throw ConnectorException.badRequest("window is limited to 5 years");
        int limit = intArg(args, "limit", 10, 1, 25);
        List<String> clauses = new ArrayList<>();
        if (drug != null) clauses.add(drugClause("patient.drug.openfda.", drug));
        if (company != null) clauses.add("patient.drug.openfda.manufacturer_name:\"" + company + "\"");
        clauses.add("receivedate:[" + BASIC.format(since) + " TO " + BASIC.format(until) + "]");
        String search = String.join(" AND ", clauses);

        JsonNode daily = fetchOrEmpty(ctx, "/drug/event.json", "search", search, "count", "receivedate");
        Map<String, Long> monthly = new TreeMap<>();
        long total = 0;
        for (JsonNode r : daily.path("results")) {
            String t = r.path("time").asText("");
            long c = r.path("count").asLong();
            total += c;
            if (t.length() >= 6) monthly.merge(t.substring(0, 4) + "-" + t.substring(4, 6), c, Long::sum);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("drug", drug);
        out.put("company", company);
        out.put("since", since.toString());
        out.put("until", until.toString());
        out.put("reports", total);
        if (total == 0) {
            out.put("note", "No FAERS reports matched. Try the brand or the generic name.");
            return out;
        }
        JsonNode serious = fetchOrEmpty(ctx, "/drug/event.json", "search", search, "count", "serious");
        long seriousN = 0;
        for (JsonNode r : serious.path("results")) if (r.path("term").asInt() == 1) seriousN = r.path("count").asLong();
        out.put("serious", seriousN);
        out.put("serious_share", Math.round((double) seriousN / total * 1000) / 1000.0);
        out.put("by_month", monthly);
        boolean byDrug = drug == null;
        JsonNode top = fetchOrEmpty(ctx, "/drug/event.json", "search", search,
                "count", byDrug ? "patient.drug.openfda.brand_name.exact" : "patient.reaction.reactionmeddrapt.exact", "limit", limit);
        List<Map<String, Object>> terms = new ArrayList<>();
        for (JsonNode r : top.path("results")) {
            Map<String, Object> x = new LinkedHashMap<>();
            x.put(byDrug ? "drug" : "reaction", r.path("term").asText());
            x.put("reports", r.path("count").asLong());
            terms.add(x);
        }
        out.put(byDrug ? "top_drugs" : "top_reactions", terms);
        out.put("data_updated", text(daily.path("meta"), "last_updated"));
        out.put("note", "By FDA receive date. FAERS lags ~1 quarter and the latest month is partial; a report can list several drugs.");
        return out;
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    JsonNode fetch(ConnectorContext ctx, String path, Object... kv) {
        Object[] all = new Object[kv.length + 2];
        System.arraycopy(kv, 0, all, 0, kv.length);
        all[kv.length] = "api_key";
        all[kv.length + 1] = ctx.field("api_key").orElse(null);
        return ctx.getJson(URI.create(url(base + path, all)));
    }

    /** openFDA answers 404 NOT_FOUND for "no matches"; that is an empty result here, not an error. */
    JsonNode fetchOrEmpty(ConnectorContext ctx, String path, Object... kv) {
        try {
            return fetch(ctx, path, kv);
        } catch (ConnectorException e) {
            if (e.kind() == ConnectorException.Kind.BAD_REQUEST && e.getMessage().contains("HTTP 404")) return ctx.mapper().createObjectNode();
            throw e;
        }
    }

    /** Sponsor names in Drugs@FDA are upper-case abbreviations ("ELI LILLY CO"): prefix match, or the SPL manufacturer name. */
    static String companyClause(String company) {
        String prefix = company.toUpperCase(Locale.ROOT).replace(" ", "\\ ");
        return "(sponsor_name:" + prefix + "* OR openfda.manufacturer_name:\"" + company + "\")";
    }

    static String drugClause(String prefix, String drug) {
        return "(" + prefix + "brand_name:\"" + drug + "\" OR " + prefix + "generic_name:\"" + drug + "\")";
    }

    /** Free-text names go inside openFDA query syntax: letters, digits, spaces and a little punctuation only. */
    static String name(JsonNode args, String field) {
        String s = optStr(args, field);
        if (s == null) return null;
        if (s.length() > 80) throw ConnectorException.badRequest(field + " is too long (max 80 characters)");
        String clean = s.replaceAll("[^A-Za-z0-9 .&'-]", " ").replaceAll("\\s+", " ").strip();
        if (clean.length() < 2) throw ConnectorException.badRequest(field + " must be a name like \"Pfizer\"");
        return clean;
    }

    LocalDate today() {
        return LocalDate.now(clock.withZone(EASTERN));
    }

    static LocalDate dateOr(JsonNode args, String field, LocalDate dflt) {
        LocalDate d = date(args, field);
        return d == null ? dflt : d;
    }

    static LocalDate basic(String s) {
        try {
            return s == null || s.length() != 8 ? null : LocalDate.parse(s, BASIC);
        } catch (Exception e) {
            return null;
        }
    }

    static String iso(String s) {
        LocalDate d = basic(s);
        return d == null ? null : d.toString();
    }

    static String first(JsonNode arr, String dflt) {
        return arr.isArray() && !arr.isEmpty() ? arr.get(0).asText() : dflt;
    }

    static String firstProduct(JsonNode app, String field) {
        JsonNode p = app.path("products");
        return p.isArray() && !p.isEmpty() ? text(p.get(0), field) : null;
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
}
