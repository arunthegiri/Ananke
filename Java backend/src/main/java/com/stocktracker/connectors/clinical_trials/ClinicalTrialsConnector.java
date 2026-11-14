package com.stocktracker.connectors.clinical_trials;

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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static com.stocktracker.connectors.ToolArgs.date;
import static com.stocktracker.connectors.ToolArgs.enumStr;
import static com.stocktracker.connectors.ToolArgs.intArg;
import static com.stocktracker.connectors.ToolArgs.integer;
import static com.stocktracker.connectors.ToolArgs.object;
import static com.stocktracker.connectors.ToolArgs.oneOf;
import static com.stocktracker.connectors.ToolArgs.optStr;
import static com.stocktracker.connectors.ToolArgs.props;
import static com.stocktracker.connectors.ToolArgs.requireStr;
import static com.stocktracker.connectors.ToolArgs.str;
import static com.stocktracker.connectors.ToolArgs.strArray;
import static com.stocktracker.connectors.ToolArgs.strSet;
import static com.stocktracker.connectors.ToolArgs.url;

/**
 * ClinicalTrials.gov API v2 (NIH/NLM): registered studies by sponsor, condition and intervention,
 * with phase, status and primary completion dates — the calendar of upcoming data readouts.
 * Keyless.
 *
 * <p>Point-in-time: every study carries its first-posted and last-update-posted dates, and the
 * primary completion date says whether it is ESTIMATED or ACTUAL. Sponsors move estimated dates,
 * so a backtest must use the record as it was posted, not today's.
 */
@Component
public class ClinicalTrialsConnector implements Connector {

    static final String ID = "clinical_trials";
    static final String BASE_URL = "https://clinicaltrials.gov/api/v2";
    static final ZoneId EASTERN = ZoneId.of("America/New_York");

    static final List<String> PHASES = List.of("EARLY_PHASE1", "PHASE1", "PHASE2", "PHASE3", "PHASE4", "NA");
    static final List<String> STATUSES = List.of("RECRUITING", "ACTIVE_NOT_RECRUITING", "NOT_YET_RECRUITING",
            "ENROLLING_BY_INVITATION", "COMPLETED", "TERMINATED", "SUSPENDED", "WITHDRAWN", "UNKNOWN");

    static final String LIST_FIELDS = String.join(",", "NCTId", "BriefTitle", "Acronym", "OverallStatus", "Phase",
            "LeadSponsorName", "Condition", "InterventionName", "EnrollmentCount", "StartDate", "PrimaryCompletionDate",
            "PrimaryCompletionDateType", "CompletionDate", "StudyFirstPostDate", "LastUpdatePostDate", "ResultsFirstPostDate",
            "HasResults");
    static final String DETAIL_FIELDS = LIST_FIELDS + "," + String.join(",", "OfficialTitle", "CollaboratorName",
            "EnrollmentType", "InterventionType", "PrimaryOutcomeMeasure", "PrimaryOutcomeTimeFrame", "DesignAllocation",
            "DesignPrimaryPurpose", "BriefSummary", "WhyStopped", "CompletionDateType");

    private static final ConnectorSpec SPEC = new ConnectorSpec(
            ID,
            "ClinicalTrials.gov",
            ConnectorSpec.Category.ALT_DATA,
            ConnectorSpec.Licence.FREE_PUBLIC,
            "Registered clinical trials by sponsor, condition or drug: phase, status, enrollment and primary "
                    + "completion dates — the calendar of upcoming biotech/pharma data readouts, plus terminations "
                    + "and delays (moved completion dates) that often precede news.",
            "Source: ClinicalTrials.gov, U.S. National Library of Medicine",
            "https://clinicaltrials.gov/data-api/api",
            List.of(),
            // Documented: about 50 requests per minute per IP.
            List.of(RateLimit.perMinute(40)));

    private final String base;
    private final Clock clock;

    public ClinicalTrialsConnector() {
        this(BASE_URL, Clock.systemUTC());
    }

    /** Test seam: point at a fake ClinicalTrials.gov. */
    ClinicalTrialsConnector(String base, Clock clock) {
        this.base = base;
        this.clock = clock;
    }

    @Override
    public ConnectorSpec spec() {
        return SPEC;
    }

    @Override
    public String test(ConnectorContext ctx) {
        JsonNode v = ctx.getJson(URI.create(base + "/version"));
        return "Connected — ClinicalTrials.gov answered (API " + v.path("apiVersion").asText("?")
                + ", data as of " + v.path("dataTimestamp").asText("?") + ").";
    }

    @Override
    public List<ConnectorTool> tools() {
        Map<String, Object> sponsor = str("Sponsor or collaborator, e.g. \"Vertex\", \"Eli Lilly\"");
        Map<String, Object> condition = str("Condition / disease, e.g. \"obesity\", \"NSCLC\"");
        Map<String, Object> intervention = str("Drug / intervention, e.g. \"retatrutide\", \"LY3437943\"");
        Map<String, Object> phases = strArray("Phases: EARLY_PHASE1, PHASE1, PHASE2, PHASE3, PHASE4, NA");
        return List.of(
                new ConnectorTool(ID + "_search",
                        "Search registered clinical trials by sponsor, condition and/or intervention, filtered by phase and "
                                + "status. Each study: NCT id, title, phase, status, enrollment, start and primary completion "
                                + "(estimated/actual) dates, first posted and last updated.",
                        object(props("sponsor", sponsor, "condition", condition, "intervention", intervention,
                                "phases", phases,
                                "statuses", strArray("Statuses, e.g. RECRUITING, ACTIVE_NOT_RECRUITING, COMPLETED, TERMINATED"),
                                "sort", enumStr("updated (default, newest first), start (newest first) or primary_completion (soonest first)",
                                        List.of("updated", "start", "primary_completion")),
                                "limit", integer("Max studies (1-25, default 10)")), List.of()),
                        Duration.ofHours(6), this::search,
                        a -> "Searching ClinicalTrials.gov…"),
                new ConnectorTool(ID + "_readouts",
                        "Upcoming readouts: trials whose primary completion date falls in a window (default the next 6 months), "
                                + "soonest first, Phase 2 and 3 by default. Primary completion is when the primary endpoint data "
                                + "are in — topline results often follow within weeks to months.",
                        object(props("sponsor", sponsor, "condition", condition, "intervention", intervention,
                                "phases", strArray("Phases (default PHASE2 and PHASE3)"),
                                "from", str("Window start YYYY-MM-DD (default today)"),
                                "to", str("Window end YYYY-MM-DD (default from + 180 days; max 3 years)"),
                                "limit", integer("Max studies (1-30, default 15)")), List.of()),
                        Duration.ofHours(6), this::readouts,
                        a -> "Finding upcoming trial readouts…"),
                new ConnectorTool(ID + "_study",
                        "One trial by NCT id: design, primary outcome and its time frame, enrollment, all key dates, "
                                + "why it stopped (if it did) and whether results are posted.",
                        object(props("nct_id", str("NCT number, e.g. NCT07232719")), List.of("nct_id")),
                        Duration.ofHours(6), this::study,
                        a -> "Reading trial " + a.path("nct_id").asText("") + "…"));
    }

    // ── tools ────────────────────────────────────────────────────────────────

    Object search(ConnectorContext ctx, JsonNode args) {
        Terms t = terms(args);
        Set<String> phases = enumSet(args, "phases", PHASES);
        Set<String> statuses = enumSet(args, "statuses", STATUSES);
        String sort = oneOf(args, "sort", "updated", List.of("updated", "start", "primary_completion"));
        int limit = intArg(args, "limit", 10, 1, 25);
        String advanced = phases.isEmpty() ? null : phaseFilter(phases);
        String sortParam = switch (sort) {
            case "start" -> "StartDate:desc";
            case "primary_completion" -> "PrimaryCompletionDate:asc";
            default -> "LastUpdatePostDate:desc";
        };
        JsonNode d = ctx.getJson(URI.create(url(base + "/studies", "query.spons", t.sponsor(), "query.cond", t.condition(),
                "query.intr", t.intervention(), "filter.overallStatus", statuses.isEmpty() ? null : String.join(",", statuses),
                "filter.advanced", advanced, "sort", sortParam, "pageSize", limit, "countTotal", "true", "fields", LIST_FIELDS)));
        return listResult(t, d, limit, null, null);
    }

    Object readouts(ConnectorContext ctx, JsonNode args) {
        Terms t = terms(args);
        Set<String> phases = enumSet(args, "phases", PHASES);
        if (phases.isEmpty()) phases = new LinkedHashSet<>(List.of("PHASE2", "PHASE3"));
        LocalDate from = date(args, "from");
        if (from == null) from = LocalDate.now(clock.withZone(EASTERN));
        LocalDate to = date(args, "to");
        if (to == null) to = from.plusDays(180);
        if (to.isBefore(from)) throw ConnectorException.badRequest("to must be on or after from");
        if (to.isAfter(from.plusYears(3))) throw ConnectorException.badRequest("window is limited to 3 years");
        int limit = intArg(args, "limit", 15, 1, 30);
        String advanced = "AREA[PrimaryCompletionDate]RANGE[" + from + "," + to + "] AND " + phaseFilter(phases);
        JsonNode d = ctx.getJson(URI.create(url(base + "/studies", "query.spons", t.sponsor(), "query.cond", t.condition(),
                "query.intr", t.intervention(), "filter.advanced", advanced, "sort", "PrimaryCompletionDate:asc",
                "pageSize", limit, "countTotal", "true", "fields", LIST_FIELDS)));
        Map<String, Object> out = listResult(t, d, limit, from, to);
        out.put("phases", phases);
        out.put("note", "primary_completion with type ESTIMATED is the sponsor's latest guess and often slips; "
                + "ACTUAL means the primary data are in.");
        return out;
    }

    Object study(ConnectorContext ctx, JsonNode args) {
        String id = requireStr(args, "nct_id", 20).toUpperCase(Locale.ROOT);
        if (!id.matches("^NCT\\d{8}$")) throw ConnectorException.badRequest("nct_id must look like NCT01234567");
        JsonNode s = ctx.getJson(URI.create(url(base + "/studies/" + id, "fields", DETAIL_FIELDS)));
        Map<String, Object> r = studyRow(s);
        JsonNode p = s.path("protocolSection");
        r.put("official_title", trim(text(p.path("identificationModule"), "officialTitle"), 300));
        List<String> collab = new ArrayList<>();
        p.path("sponsorCollaboratorsModule").path("collaborators").forEach(c -> collab.add(c.path("name").asText()));
        r.put("collaborators", collab);
        r.put("enrollment_type", text(p.path("designModule").path("enrollmentInfo"), "type"));
        r.put("allocation", text(p.path("designModule").path("designInfo"), "allocation"));
        r.put("purpose", text(p.path("designModule").path("designInfo"), "primaryPurpose"));
        List<Map<String, Object>> outcomes = new ArrayList<>();
        for (JsonNode o : p.path("outcomesModule").path("primaryOutcomes")) {
            if (outcomes.size() >= 5) break;
            Map<String, Object> x = new LinkedHashMap<>();
            x.put("measure", trim(text(o, "measure"), 200));
            x.put("time_frame", trim(text(o, "timeFrame"), 120));
            outcomes.add(x);
        }
        r.put("primary_outcomes", outcomes);
        r.put("completion_type", text(p.path("statusModule").path("completionDateStruct"), "type"));
        r.put("why_stopped", trim(text(p.path("statusModule"), "whyStopped"), 200));
        r.put("summary", trim(text(p.path("descriptionModule"), "briefSummary"), 500));
        return r;
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    record Terms(String sponsor, String condition, String intervention) { }

    static Terms terms(JsonNode args) {
        String s = term(args, "sponsor");
        String c = term(args, "condition");
        String i = term(args, "intervention");
        if (s == null && c == null && i == null) throw ConnectorException.badRequest("Give at least one of sponsor, condition or intervention");
        return new Terms(s, c, i);
    }

    static String term(JsonNode args, String name) {
        String v = optStr(args, name);
        if (v != null && v.length() > 100) throw ConnectorException.badRequest(name + " is too long (max 100 characters)");
        return v;
    }

    static Set<String> enumSet(JsonNode args, String name, List<String> allowed) {
        Set<String> out = new LinkedHashSet<>();
        for (String v : strSet(args, name, true)) {
            String norm = v.replace(' ', '_');
            if (norm.matches("^PHASE_\\d$")) norm = norm.replace("_", "");
            if (!allowed.contains(norm)) throw ConnectorException.badRequest(name + " must be from " + allowed);
            out.add(norm);
        }
        return out;
    }

    static String phaseFilter(Set<String> phases) {
        return "AREA[Phase](" + String.join(" OR ", phases) + ")";
    }

    Map<String, Object> listResult(Terms t, JsonNode d, int limit, LocalDate from, LocalDate to) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (JsonNode s : d.path("studies")) {
            if (rows.size() >= limit) break;
            rows.add(studyRow(s));
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("sponsor", t.sponsor());
        out.put("condition", t.condition());
        out.put("intervention", t.intervention());
        if (from != null) {
            out.put("from", from.toString());
            out.put("to", to.toString());
        }
        out.put("total", d.path("totalCount").isNumber() ? d.path("totalCount").asInt() : null);
        out.put("count", rows.size());
        out.put("studies", rows);
        return out;
    }

    static Map<String, Object> studyRow(JsonNode s) {
        JsonNode p = s.path("protocolSection");
        JsonNode st = p.path("statusModule");
        Map<String, Object> r = new LinkedHashMap<>();
        String id = text(p.path("identificationModule"), "nctId");
        r.put("nct_id", id);
        r.put("title", trim(text(p.path("identificationModule"), "briefTitle"), 200));
        String acronym = text(p.path("identificationModule"), "acronym");
        if (acronym != null) r.put("acronym", acronym);
        r.put("sponsor", text(p.path("sponsorCollaboratorsModule").path("leadSponsor"), "name"));
        r.put("phase", list(p.path("designModule").path("phases"), 3));
        r.put("status", text(st, "overallStatus"));
        r.put("conditions", list(p.path("conditionsModule").path("conditions"), 5));
        List<String> interventions = new ArrayList<>();
        for (JsonNode i : p.path("armsInterventionsModule").path("interventions")) {
            if (interventions.size() >= 5) break;
            String n = i.path("name").asText("");
            String type = i.path("type").asText("");
            interventions.add(type.isEmpty() ? n : n + " (" + type.toLowerCase(Locale.ROOT) + ")");
        }
        r.put("interventions", interventions);
        JsonNode enroll = p.path("designModule").path("enrollmentInfo").path("count");
        r.put("enrollment", enroll.isNumber() ? enroll.asInt() : null);
        r.put("start", text(st.path("startDateStruct"), "date"));
        r.put("primary_completion", text(st.path("primaryCompletionDateStruct"), "date"));
        r.put("primary_completion_type", text(st.path("primaryCompletionDateStruct"), "type"));
        r.put("completion", text(st.path("completionDateStruct"), "date"));
        r.put("first_posted", text(st.path("studyFirstPostDateStruct"), "date"));
        r.put("last_updated", text(st.path("lastUpdatePostDateStruct"), "date"));
        String results = text(st.path("resultsFirstPostDateStruct"), "date");
        r.put("results_posted", results);
        r.put("url", id == null ? null : "https://clinicaltrials.gov/study/" + id);
        return r;
    }

    static List<String> list(JsonNode arr, int max) {
        List<String> out = new ArrayList<>();
        for (JsonNode n : arr) {
            if (out.size() >= max) break;
            out.add(n.asText());
        }
        return out;
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
