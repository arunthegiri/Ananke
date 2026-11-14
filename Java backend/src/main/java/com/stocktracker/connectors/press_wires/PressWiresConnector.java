package com.stocktracker.connectors.press_wires;

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
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.stocktracker.connectors.ToolArgs.intArg;
import static com.stocktracker.connectors.ToolArgs.integer;
import static com.stocktracker.connectors.ToolArgs.object;
import static com.stocktracker.connectors.ToolArgs.optStr;
import static com.stocktracker.connectors.ToolArgs.props;
import static com.stocktracker.connectors.ToolArgs.str;
import static com.stocktracker.connectors.ToolArgs.strArray;
import static com.stocktracker.connectors.ToolArgs.strSet;

/**
 * Press-release wires and regulator press rooms over their public RSS feeds: PR Newswire,
 * Business Wire, the Federal Reserve Board and the SEC. Keyless. GlobeNewswire is not included:
 * its feeds refuse non-browser clients (HTTP 403).
 *
 * <p>Feeds are XML from the open web, so parsing is hardened like the SEC Form 4 parser: no
 * DOCTYPE, no external entities. Point-in-time: every item carries its feed {@code published_at}.
 */
@Component
public class PressWiresConnector implements Connector {

    static final String ID = "press_wires";

    /** One feed: id → URL. Order is the order sources are listed and read. */
    public record Feed(String id, String name, String url) { }

    static final List<Feed> FEEDS = List.of(
            new Feed("prnewswire", "PR Newswire — all news releases", "https://www.prnewswire.com/rss/news-releases-list.rss"),
            new Feed("businesswire", "Business Wire — all news", "https://feed.businesswire.com/rss/home/?rss=G1QFDERJXkJeEFpRXg=="),
            new Feed("fed", "Federal Reserve Board — all press releases", "https://www.federalreserve.gov/feeds/press_all.xml"),
            new Feed("sec", "SEC — press releases", "https://www.sec.gov/news/pressreleases.rss"));

    private static final ConnectorSpec SPEC = new ConnectorSpec(
            ID,
            "Press wires & regulators",
            ConnectorSpec.Category.NEWS,
            ConnectorSpec.Licence.FREE_PUBLIC,
            "Latest press releases from PR Newswire and Business Wire (company announcements: earnings dates, deals, "
                    + "guidance, product news) and from the Federal Reserve Board and the SEC, via their public RSS feeds. "
                    + "Filter by company or keyword.",
            "Headlines from PR Newswire, Business Wire, the Federal Reserve Board and the U.S. SEC (public RSS feeds); "
                    + "full releases on the publishers' sites",
            "https://www.prnewswire.com/rss/",
            List.of(new Field("contact", "Contact (optional)",
                    "Sent in the User-Agent so feed operators (notably the SEC) can reach you; e.g. a name and email.",
                    false, true, "Jane Doe jane@example.com")),
            List.of(RateLimit.perSecond(2), RateLimit.perMinute(30)));

    private static final String LANG_HELP =
            "Language of releases to keep, e.g. en (default), de, fr, or any. Business Wire mixes languages in one feed.";

    private final List<Feed> feeds;

    public PressWiresConnector() {
        this(FEEDS);
    }

    /** Test seam: point at fake feeds. */
    PressWiresConnector(List<Feed> feeds) {
        this.feeds = List.copyOf(feeds);
    }

    @Override
    public ConnectorSpec spec() {
        return SPEC;
    }

    @Override
    public String test(ConnectorContext ctx) {
        Feed f = feeds.stream().filter(x -> x.id().equals("fed")).findFirst().orElse(feeds.get(0));
        List<Map<String, Object>> items = read(ctx, f);
        return "Connected — " + f.name() + " answered (" + items.size() + " items).";
    }

    @Override
    public List<ConnectorTool> tools() {
        List<String> ids = feeds.stream().map(Feed::id).toList();
        return List.of(
                new ConnectorTool(ID + "_latest",
                        "Latest items from press-release wires and regulators, newest first: title, short summary, "
                                + "publish time and link. Sources: " + String.join(", ", ids)
                                + " (prnewswire and businesswire = company releases; fed = Federal Reserve Board; sec = SEC).",
                        object(props(
                                "sources", strArray("Which sources, e.g. [\"prnewswire\",\"businesswire\"]. Omit for all."),
                                "lang", str(LANG_HELP),
                                "limit", integer("Max items in total (1-60, default 25)")), List.of()),
                        Duration.ofMinutes(5), this::latest,
                        a -> "Reading press-release feeds…"),
                new ConnectorTool(ID + "_search",
                        "Filter the current press-release feeds for a company name, ticker or keyword (case-insensitive, "
                                + "matched in title and summary; any of several terms). Feeds only hold the most recent ~20-170 "
                                + "items each, so this finds today's/this week's releases, not an archive.",
                        object(props(
                                "terms", strArray("Words to look for, e.g. [\"Nvidia\",\"NVDA\"]"),
                                "sources", strArray("Which sources. Omit for all."),
                                "lang", str(LANG_HELP),
                                "limit", integer("Max items (1-60, default 25)")), List.of("terms")),
                        Duration.ofMinutes(5), this::search,
                        a -> "Searching press-release feeds…"));
    }

    // ── tools ────────────────────────────────────────────────────────────────

    Object latest(ConnectorContext ctx, JsonNode args) {
        List<Feed> chosen = sources(args);
        int limit = intArg(args, "limit", 25, 1, 60);
        return result(ctx, chosen, null, lang(args), limit);
    }

    Object search(ConnectorContext ctx, JsonNode args) {
        Set<String> terms = strSet(args, "terms", false);
        if (terms.isEmpty()) throw ConnectorException.badRequest("terms is required");
        if (terms.size() > 10) throw ConnectorException.badRequest("terms: at most 10");
        List<Pattern> pats = new ArrayList<>();
        for (String t : terms) {
            if (t.length() < 2 || t.length() > 80) throw ConnectorException.badRequest("each term must be 2-80 characters");
            pats.add(Pattern.compile("(?<![\\p{L}\\p{N}])" + Pattern.quote(t) + "(?![\\p{L}\\p{N}])", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE));
        }
        List<Feed> chosen = sources(args);
        int limit = intArg(args, "limit", 25, 1, 60);
        Map<String, Object> out = result(ctx, chosen, pats, lang(args), limit);
        out.put("terms", terms);
        return out;
    }

    private static String lang(JsonNode args) {
        String l = optStr(args, "lang");
        if (l == null) return "en";
        l = l.toLowerCase(Locale.ROOT);
        if (!l.equals("any") && !l.matches("^[a-z]{2}$")) throw ConnectorException.badRequest("lang must be a two-letter code like en, or any");
        return l;
    }

    private Map<String, Object> result(ConnectorContext ctx, List<Feed> chosen, List<Pattern> pats, String lang, int limit) {
        List<Map<String, Object>> all = new ArrayList<>();
        List<String> failed = new ArrayList<>();
        for (Feed f : chosen) {
            try {
                for (Map<String, Object> it : read(ctx, f)) {
                    Object itLang = it.get("language");
                    if (!lang.equals("any") && itLang != null && !lang.equals(itLang)) continue;
                    if (pats == null || matches(pats, it)) all.add(it);
                }
            } catch (ConnectorException e) {
                if (chosen.size() == 1 || e.kind() == ConnectorException.Kind.RATE_LIMITED) throw e;
                failed.add(f.id() + ": " + e.getMessage());
            }
        }
        all.sort(Comparator.comparing((Map<String, Object> m) -> String.valueOf(m.getOrDefault("published_at", ""))).reversed());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("sources", chosen.stream().map(Feed::id).toList());
        if (!failed.isEmpty()) out.put("unavailable", failed);
        out.put("matched", all.size());
        List<Map<String, Object>> items = all.size() > limit ? all.subList(0, limit) : all;
        out.put("count", items.size());
        out.put("items", new ArrayList<>(items));
        return out;
    }

    private static boolean matches(List<Pattern> pats, Map<String, Object> it) {
        String hay = it.get("title") + " " + it.getOrDefault("summary", "");
        for (Pattern p : pats) if (p.matcher(hay).find()) return true;
        return false;
    }

    private List<Feed> sources(JsonNode args) {
        Set<String> want = strSet(args, "sources", false);
        if (want.isEmpty()) return feeds;
        List<Feed> out = new ArrayList<>();
        for (String w : want) {
            Feed f = feeds.stream().filter(x -> x.id().equalsIgnoreCase(w)).findFirst()
                    .orElseThrow(() -> ConnectorException.badRequest("Unknown source \"" + w + "\"; use "
                            + feeds.stream().map(Feed::id).toList()));
            out.add(f);
        }
        return out;
    }

    // ── feed reading ─────────────────────────────────────────────────────────

    List<Map<String, Object>> read(ConnectorContext ctx, Feed f) {
        String ua = ctx.field("contact").map(c -> "Ananke/1 (" + c.replaceAll("[\\r\\n]", " ") + ")").orElse("Ananke/1");
        String xml = ctx.get(URI.create(f.url()), Map.of("User-Agent", ua), "application/rss+xml, application/atom+xml, application/xml;q=0.9, text/xml;q=0.8");
        return parse(f, xml);
    }

    /** RSS 2.0 {@code <item>} or Atom {@code <entry>} → compact items. Hardened against XXE. */
    static List<Map<String, Object>> parse(Feed f, String xml) {
        Document doc;
        try {
            String body = xml == null ? "" : xml.strip();
            if (body.startsWith("﻿")) body = body.substring(1);   // the Fed's feed starts with a BOM
            DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
            dbf.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            dbf.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            dbf.setFeature("http://xml.org/sax/features/external-general-entities", false);
            dbf.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            dbf.setXIncludeAware(false);
            dbf.setExpandEntityReferences(false);
            dbf.setNamespaceAware(true);
            javax.xml.parsers.DocumentBuilder db = dbf.newDocumentBuilder();
            db.setErrorHandler(null);   // no stderr noise; errors still throw
            doc = db.parse(new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw ConnectorException.upstream(f.name() + " feed could not be read as XML.");
        }
        List<Map<String, Object>> out = new ArrayList<>();
        NodeList items = doc.getElementsByTagNameNS("*", "item");
        boolean atom = items.getLength() == 0;
        if (atom) items = doc.getElementsByTagNameNS("*", "entry");
        for (int i = 0; i < items.getLength() && out.size() < 200; i++) {
            Element e = (Element) items.item(i);
            String title = text(e, "title");
            if (title == null) continue;
            String link = atom ? atomLink(e) : text(e, "link");
            String date = firstNonNull(text(e, "pubDate"), text(e, "published"), text(e, "updated"), text(e, "date"));
            String summary = firstNonNull(text(e, "description"), text(e, "summary"), text(e, "content"));
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("source", f.id());
            m.put("published_at", instant(date));
            m.put("title", clean(title, 250));
            String s = summary == null ? "" : clean(summary, 350);
            if (!s.isEmpty() && !s.equalsIgnoreCase(clean(title, 250))) m.put("summary", s);
            String cat = text(e, "category");
            if (cat != null) m.put("category", clean(cat, 80));
            String lang = language(link);
            if (lang != null) m.put("language", lang);
            m.put("url", link == null ? null : stripTracking(link.strip()));
            out.add(m);
        }
        return out;
    }

    private static String text(Element parent, String local) {
        for (Node n = parent.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n.getNodeType() == Node.ELEMENT_NODE && local.equals(n.getLocalName())) {
                String t = n.getTextContent();
                return t == null || t.isBlank() ? null : t.strip();
            }
        }
        return null;
    }

    private static String atomLink(Element entry) {
        String any = null;
        for (Node n = entry.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n.getNodeType() == Node.ELEMENT_NODE && "link".equals(n.getLocalName())) {
                Element l = (Element) n;
                String rel = l.getAttribute("rel");
                if (rel.isEmpty() || rel.equals("alternate")) return l.getAttribute("href");
                if (any == null) any = l.getAttribute("href");
            }
        }
        return any;
    }

    @SafeVarargs
    private static <T> T firstNonNull(T... xs) {
        for (T x : xs) if (x != null) return x;
        return null;
    }

    private static final Pattern BW_LANG = Pattern.compile("businesswire\\.com/news/home/\\d+/([a-z]{2})/");

    /** Business Wire mixes languages in one feed; its links carry the language code. */
    static String language(String link) {
        if (link == null) return null;
        Matcher m = BW_LANG.matcher(link);
        return m.find() ? m.group(1) : null;
    }

    private static String stripTracking(String link) {
        return link.replaceFirst("[?&]feedref=[^&]*$", "");
    }

    /** RFC 822 dates as feeds actually write them ("UT", "GMT", "+0000", "-0500") or ISO-8601 → ISO instant. */
    static String instant(String raw) {
        if (raw == null) return null;
        String s = raw.strip().replaceAll("\\s+", " ");
        try {
            return OffsetDateTime.parse(s).toInstant().toString();
        } catch (Exception ignored) { }
        String r = s.replaceFirst(" UTC?$", " GMT");
        try {
            return ZonedDateTime.parse(r, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toString();
        } catch (Exception ignored) { }
        try {
            return ZonedDateTime.parse(r, DateTimeFormatter.ofPattern("EEE, d MMM yyyy HH:mm[:ss] z", Locale.ENGLISH)).toInstant().toString();
        } catch (Exception ignored) { }
        try {
            return ZonedDateTime.parse(r, DateTimeFormatter.ofPattern("EEE, d MMM yyyy HH:mm[:ss] Z", Locale.ENGLISH)).toInstant().toString();
        } catch (Exception ignored) { }
        try {
            return Instant.parse(s).toString();
        } catch (Exception ignored) { }
        return s;
    }

    /** Strip HTML tags and entities the feed double-escaped, collapse whitespace, cap length. */
    static String clean(String s, int max) {
        String t = s.replaceAll("(?s)<[^>]*>", " ")
                .replace("&amp;quot;", "\"").replace("&quot;", "\"").replace("&#39;", "'").replace("&#x27;", "'")
                .replace("&nbsp;", " ").replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&")
                .replaceAll("\\s+", " ").strip();
        return t.length() > max ? t.substring(0, max) + "…" : t;
    }
}
