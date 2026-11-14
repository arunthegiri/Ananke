package com.stocktracker.connectors.keepa;

import com.fasterxml.jackson.databind.JsonNode;
import com.stocktracker.connectors.Connector;
import com.stocktracker.connectors.ConnectorContext;
import com.stocktracker.connectors.ConnectorException;
import com.stocktracker.connectors.ConnectorSpec;
import com.stocktracker.connectors.ConnectorSpec.Field;
import com.stocktracker.connectors.ConnectorSpec.RateLimit;
import com.stocktracker.connectors.ConnectorTool;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.GZIPInputStream;

import static com.stocktracker.connectors.ToolArgs.*;

/**
 * Keepa (Connectors roadmap, alt data): Amazon product price, sales-rank and offer-count history —
 * the "Amazon inventory monitoring" use case — with the user's own token-metered Keepa API key.
 *
 * <p>Keepa specifics handled here:
 * <ul>
 *   <li>Every response is gzip-encoded regardless of {@code Accept-Encoding}, which the JDK client
 *       does not undo, so this connector sends its own requests (still one rate-limit permit each).</li>
 *   <li>Times are "Keepa minutes": minutes since 2011-01-01T00:00Z. Converted to ISO-8601 UTC.</li>
 *   <li>History arrays are flat {@code [time, value, time, value…]} ({@code [time, price, shipping…]}
 *       for buy-box), values -1/-2 mean "no offer / out of stock". Downsampled before returning.</li>
 *   <li>Prices are integers in the smallest currency unit (cents), except Japan (yen).</li>
 * </ul>
 * Request parameters and field semantics follow Keepa's official Java client (keepacom/api_backend).
 */
@Component
public class KeepaConnector implements Connector {

    static final String ID = "keepa";
    static final String BASE_URL = "https://api.keepa.com";

    /** Keepa minute 0 = 2011-01-01T00:00:00Z (21,564,000 minutes after the Unix epoch). */
    static final long KEEPA_EPOCH_MINUTE = 21_564_000L;

    /** Amazon locale → Keepa domain id and price currency. */
    static final Map<String, Integer> DOMAINS = new LinkedHashMap<>();
    static final Map<Integer, String> CURRENCY = new LinkedHashMap<>();

    static {
        String[][] d = {{"us", "1", "USD"}, {"gb", "2", "GBP"}, {"de", "3", "EUR"}, {"fr", "4", "EUR"}, {"jp", "5", "JPY"},
                {"ca", "6", "CAD"}, {"it", "8", "EUR"}, {"es", "9", "EUR"}, {"in", "10", "INR"}, {"mx", "11", "MXN"}, {"br", "12", "BRL"}};
        for (String[] r : d) {
            DOMAINS.put(r[0], Integer.parseInt(r[1]));
            CURRENCY.put(Integer.parseInt(r[1]), r[2]);
        }
    }

    /** csv indices (Product.CsvType) returned without the offers parameter. */
    record Series(String name, int index, boolean price, boolean withShipping) { }

    static final List<Series> SERIES = List.of(
            new Series("amazon_price", 0, true, false),
            new Series("new_price", 1, true, false),
            new Series("used_price", 2, true, false),
            new Series("sales_rank", 3, false, false),
            new Series("list_price", 4, true, false),
            new Series("new_offer_count", 11, false, false),
            new Series("used_offer_count", 12, false, false),
            new Series("buy_box_price", 18, true, true));   // only present with offers/buybox

    private static final ConnectorSpec SPEC = new ConnectorSpec(
            ID,
            "Keepa",
            ConnectorSpec.Category.ALT_DATA,
            ConnectorSpec.Licence.BYO_PERSONAL,
            "Amazon product tracking by ASIN: current and historical Amazon/new/used prices, sales rank, "
                    + "new/used offer counts, Amazon in-stock status and out-of-stock rates, product search and "
                    + "category best-seller lists. Keepa API plans are metered in tokens per minute.",
            "Data: Keepa.com",
            "https://keepa.com/#!discuss/t/how-our-api-plans-work/410",
            List.of(Field.secret("api_key", "API access key",
                    "Your private Keepa API key (64 characters) from keepa.com → API access. Each request spends "
                            + "tokens: about 1 per product, 10 per search page, 50 per best-seller list.")),
            // The smallest plan refills 20 tokens/minute; one request costs at least one token.
            List.of(RateLimit.perMinute(20)));

    private final Clock clock;
    private final String base;

    public KeepaConnector() {
        this(Clock.systemUTC(), BASE_URL);
    }

    /** Test seam: point at a fake Keepa. */
    KeepaConnector(Clock clock, String base) {
        this.clock = clock;
        this.base = base;
    }

    @Override
    public ConnectorSpec spec() {
        return SPEC;
    }

    @Override
    public String test(ConnectorContext ctx) {
        JsonNode r;
        try {
            r = call(ctx, "/token");
        } catch (ConnectorException e) {
            // /token only takes the key, so "invalid parameter" there means a malformed key.
            if (e.kind() == ConnectorException.Kind.BAD_REQUEST) {
                throw new ConnectorException(ConnectorException.Kind.AUTH, "Keepa rejected the key: " + e.getMessage());
            }
            throw e;
        }
        return "Connected — Keepa answered (" + r.path("tokensLeft").asInt() + " tokens left, refill "
                + r.path("refillRate").asInt() + "/min).";
    }

    @Override
    public List<ConnectorTool> tools() {
        return List.of(
                new ConnectorTool(ID + "_product",
                        "An Amazon product by ASIN: title, brand, category, current Amazon/new/used price, sales rank, "
                                + "new/used offer counts, Amazon availability, averages and min/max over the window, "
                                + "out-of-stock percentages, monthly units sold estimate, and downsampled history "
                                + "series (ISO timestamps). include_offers adds live stock counts and buy-box data "
                                + "(costs more Keepa tokens).",
                        object(props(
                                "asin", str("10-character ASIN, e.g. B0088PUEPK"),
                                "domain", enumStr("Amazon locale (default us)", List.copyOf(DOMAINS.keySet())),
                                "days", integer("History/statistics window in days (1-365, default 90)"),
                                "points", integer("Max points per history series (5-200, default 60)"),
                                "include_offers", bool("Also fetch up to 20 live offers for stock levels and buy box (extra tokens)")),
                                List.of("asin")),
                        Duration.ofHours(2), this::product,
                        a -> "Reading Keepa history for " + a.path("asin").asText("product") + "…"),
                new ConnectorTool(ID + "_search",
                        "Search Amazon products on Keepa by keywords (10 results per page): ASIN, title, brand, "
                                + "current prices, sales rank and monthly sold estimate. Costs about 10 tokens per page.",
                        object(props(
                                "term", str("Search keywords"),
                                "domain", enumStr("Amazon locale (default us)", List.copyOf(DOMAINS.keySet())),
                                "page", integer("Result page 0-9 (default 0)")), List.of("term")),
                        Duration.ofHours(6), this::search,
                        a -> "Searching Keepa for " + a.path("term").asText("") + "…"),
                new ConnectorTool(ID + "_bestsellers",
                        "The best-seller ASIN list of an Amazon category (by category node id), best-selling first, "
                                + "with when Keepa last updated it. Costs about 50 tokens.",
                        object(props(
                                "category", str("Amazon category node id, e.g. 172282 (Electronics, US)"),
                                "domain", enumStr("Amazon locale (default us)", List.copyOf(DOMAINS.keySet())),
                                "limit", integer("Max ASINs (1-500, default 50)")), List.of("category")),
                        Duration.ofHours(12), this::bestsellers,
                        a -> "Reading Keepa best sellers…"),
                new ConnectorTool(ID + "_tokens",
                        "Keepa token balance: tokens left, refill rate per minute and time to next refill. Free.",
                        object(props(), List.of()),
                        Duration.ZERO, (ctx, a) -> tokens(ctx),
                        a -> "Checking Keepa tokens…"));
    }

    // ── tools ────────────────────────────────────────────────────────────────

    Object product(ConnectorContext ctx, JsonNode args) {
        String asin = requireStr(args, "asin", 10).toUpperCase(Locale.ROOT);
        if (!asin.matches("^[A-Z0-9]{10}$")) throw ConnectorException.badRequest("asin must be 10 letters/digits, e.g. B0088PUEPK");
        int domain = domain(args);
        int days = intArg(args, "days", 90, 1, 365);
        int points = intArg(args, "points", 60, 5, 200);
        boolean offers = args.path("include_offers").asBoolean(false);
        JsonNode r = call(ctx, "/product", "domain", domain, "asin", asin, "stats", days, "history", 1, "days", days,
                "offers", offers ? 20 : null, "stock", offers ? 1 : null, "only-live-offers", offers ? 1 : null, "buybox", offers ? 1 : null);
        JsonNode products = r.path("products");
        if (!products.isArray() || products.isEmpty() || products.get(0).path("title").isNull() && products.get(0).path("csv").isNull()) {
            throw ConnectorException.badRequest("Keepa has no product " + asin + " on amazon " + key(domain) + ".");
        }
        JsonNode p = products.get(0);
        long cutoff = clock.instant().minus(Duration.ofDays(days)).getEpochSecond() / 60 - KEEPA_EPOCH_MINUTE;

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("asin", p.path("asin").asText(asin));
        out.put("domain", key(domain));
        out.put("currency", CURRENCY.get(domain));
        out.put("title", trim(p.path("title").asText(null), 200));
        out.put("brand", p.path("brand").asText(null));
        out.put("manufacturer", p.path("manufacturer").asText(null));
        out.put("root_category", p.path("rootCategory").isNumber() ? p.path("rootCategory").asLong() : null);
        out.put("monthly_sold", p.path("monthlySold").asInt(0) > 0 ? p.path("monthlySold").asInt() : null);
        out.put("amazon_availability", availability(p.path("availabilityAmazon").asInt(-1)));
        out.put("last_update", keepaTime(p.path("lastUpdate")));
        out.put("last_price_change", keepaTime(p.path("lastPriceChange")));
        out.put("tracking_since", keepaTime(p.path("trackingSince")));
        out.put("listed_since", keepaTime(p.path("listedSince")));

        JsonNode stats = p.path("stats");
        if (stats.isObject()) {
            Map<String, Object> s = new LinkedHashMap<>();
            s.put("window_days", days);
            s.put("current", statRow(stats.path("current"), domain));
            s.put("average", statRow(stats.path("avg"), domain));
            s.put("average_30d", statRow(stats.path("avg30"), domain));
            s.put("average_90d", statRow(stats.path("avg90"), domain));
            s.put("min", statPoints(stats.path("minInInterval").isArray() ? stats.path("minInInterval") : stats.path("min"), domain));
            s.put("max", statPoints(stats.path("maxInInterval").isArray() ? stats.path("maxInInterval") : stats.path("max"), domain));
            s.put("out_of_stock_pct_30d", oos(stats.path("outOfStockPercentage30")));
            s.put("out_of_stock_pct_90d", oos(stats.path("outOfStockPercentage90")));
            if (offers) {
                s.put("stock_amazon", stockValue(stats.path("stockAmazon")));
                s.put("stock_buy_box", stockValue(stats.path("stockBuyBox")));
                s.put("total_offer_count", stats.path("totalOfferCount").asInt(-2) >= 0 ? stats.path("totalOfferCount").asInt() : null);
                s.put("buy_box_price", money(stats.path("buyBoxPrice").asInt(-1), domain));
                s.put("buy_box_seller_id", stats.path("buyBoxSellerId").asText(null));
                s.put("buy_box_is_amazon", stats.path("buyBoxIsAmazon").isBoolean() ? stats.path("buyBoxIsAmazon").asBoolean() : null);
                s.put("buy_box_is_fba", stats.path("buyBoxIsFBA").isBoolean() ? stats.path("buyBoxIsFBA").asBoolean() : null);
            }
            out.put("stats", s);
        }

        Map<String, Object> history = new LinkedHashMap<>();
        JsonNode csv = p.path("csv");
        for (Series se : SERIES) {
            JsonNode arr = csv.path(se.index());
            if (!arr.isArray() || arr.isEmpty()) continue;
            List<List<Object>> pts = series(arr, se, domain, cutoff);
            if (!pts.isEmpty()) history.put(se.name(), downsample(pts, points));
        }
        out.put("history", history);
        out.put("note", "Prices in " + CURRENCY.get(domain) + "; null = no offer / out of stock. sales_rank: lower is better. "
                + "History points are [ISO time, value], downsampled to at most " + points + " per series.");
        out.put("tokens_left", r.path("tokensLeft").isNumber() ? r.path("tokensLeft").asInt() : null);
        return out;
    }

    Object search(ConnectorContext ctx, JsonNode args) {
        String term = requireStr(args, "term", 200);
        int domain = domain(args);
        int page = intArg(args, "page", 0, 0, 9);
        JsonNode r = call(ctx, "/search", "domain", domain, "type", "product", "term", term, "page", page, "stats", 30, "history", 0);
        List<Map<String, Object>> rows = new ArrayList<>();
        for (JsonNode p : r.path("products")) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("asin", p.path("asin").asText(null));
            m.put("title", trim(p.path("title").asText(null), 150));
            m.put("brand", p.path("brand").asText(null));
            JsonNode cur = p.path("stats").path("current");
            m.put("amazon_price", money(cur.path(0).asInt(-1), domain));
            m.put("new_price", money(cur.path(1).asInt(-1), domain));
            m.put("sales_rank", cur.path(3).asInt(-1) >= 0 ? cur.path(3).asInt() : null);
            m.put("new_offer_count", cur.path(11).asInt(-1) >= 0 ? cur.path(11).asInt() : null);
            m.put("monthly_sold", p.path("monthlySold").asInt(0) > 0 ? p.path("monthlySold").asInt() : null);
            rows.add(m);
            if (rows.size() >= 10) break;
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("term", term);
        out.put("domain", key(domain));
        out.put("currency", CURRENCY.get(domain));
        out.put("page", page);
        out.put("count", rows.size());
        out.put("products", rows);
        out.put("tokens_left", r.path("tokensLeft").isNumber() ? r.path("tokensLeft").asInt() : null);
        return out;
    }

    Object bestsellers(ConnectorContext ctx, JsonNode args) {
        String category = requireStr(args, "category", 20);
        if (!category.matches("^\\d{1,19}$")) throw ConnectorException.badRequest("category must be a numeric Amazon category node id");
        int domain = domain(args);
        int limit = intArg(args, "limit", 50, 1, 500);
        JsonNode r = call(ctx, "/bestsellers", "domain", domain, "category", category);
        JsonNode list = r.path("bestSellersList");
        if (!list.isObject()) throw ConnectorException.badRequest("Keepa has no best-seller list for category " + category + ".");
        List<String> asins = new ArrayList<>();
        for (JsonNode a : list.path("asinList")) {
            asins.add(a.asText());
            if (asins.size() >= limit) break;
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("category", category);
        out.put("domain", key(domain));
        out.put("updated_at", keepaTime(list.path("lastUpdate")));
        out.put("total", list.path("asinList").size());
        out.put("asins", asins);
        out.put("tokens_left", r.path("tokensLeft").isNumber() ? r.path("tokensLeft").asInt() : null);
        return out;
    }

    Object tokens(ConnectorContext ctx) {
        JsonNode r = call(ctx, "/token");
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("tokens_left", r.path("tokensLeft").asInt());
        out.put("refill_rate_per_min", r.path("refillRate").asInt());
        out.put("refill_in_ms", r.path("refillIn").asInt());
        out.put("token_flow_reduction", r.path("tokenFlowReduction").asDouble());
        return out;
    }

    // ── transport ────────────────────────────────────────────────────────────

    /** One Keepa request: own transport because bodies are always gzip-encoded. */
    JsonNode call(ConnectorContext ctx, String path, Object... query) {
        Object[] kv = new Object[query.length + 2];
        kv[0] = "key";
        kv[1] = ctx.require("api_key");
        System.arraycopy(query, 0, kv, 2, query.length);
        HttpRequest req = HttpRequest.newBuilder(URI.create(url(base + path, kv)))
                .timeout(Duration.ofSeconds(60)).GET()
                .header("Accept", "application/json").header("Accept-Encoding", "gzip").header("User-Agent", "Ananke/1")
                .build();
        ctx.acquirePermit();
        HttpResponse<byte[]> res;
        try {
            res = ctx.http().send(req, HttpResponse.BodyHandlers.ofByteArray());
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            throw ConnectorException.upstream("Keepa is unreachable: " + e.getClass().getSimpleName());
        }
        JsonNode body;
        try {
            body = ctx.mapper().readTree(decode(res.body()));
        } catch (Exception e) {
            throw ConnectorException.upstream("Keepa answered something that is not JSON (HTTP " + res.statusCode() + ").");
        }
        check(res.statusCode(), body);
        return body;
    }

    /** Maps Keepa's status codes and {@code error} objects (which can also arrive with HTTP 200). */
    static void check(int status, JsonNode body) {
        JsonNode err = body.path("error");
        String msg = err.isObject() ? err.path("message").asText("") : "";
        String type = err.isObject() ? err.path("type").asText("") : "";
        if (status == 429 || "tokenLimit".equalsIgnoreCase(type) || body.path("tokensLeft").asInt(0) < 0 && status != 200) {
            throw new ConnectorException(ConnectorException.Kind.RATE_LIMITED,
                    "Keepa: out of tokens (refill in " + Math.max(0, body.path("refillIn").asInt()) / 1000 + "s). Try again shortly.");
        }
        if (status == 401 || status == 402 || status == 403 || "unauthorized".equalsIgnoreCase(type) || "paymentRequired".equalsIgnoreCase(type)) {
            throw new ConnectorException(ConnectorException.Kind.AUTH,
                    "Keepa rejected the key (HTTP " + status + ")" + (msg.isEmpty() ? "." : ": " + msg) + " Check it in Settings → Connectors.");
        }
        if (status == 400 || "invalidParameter".equalsIgnoreCase(type)) {
            throw ConnectorException.badRequest("Keepa: invalid request" + (msg.isEmpty() ? "." : ": " + msg));
        }
        if (status == 404) throw ConnectorException.badRequest("Keepa: not found (HTTP 404).");
        if (status / 100 != 2 || err.isObject()) {
            throw ConnectorException.upstream("Keepa failed: HTTP " + status + (msg.isEmpty() ? "" : " " + msg));
        }
    }

    static String decode(byte[] b) throws Exception {
        if (b.length > 2 && (b[0] & 0xff) == 0x1f && (b[1] & 0xff) == 0x8b) {
            try (GZIPInputStream in = new GZIPInputStream(new ByteArrayInputStream(b))) {
                byte[] out = in.readNBytes(20_000_001);
                if (out.length > 20_000_000) throw ConnectorException.upstream("Keepa answered with too much data.");
                return new String(out, StandardCharsets.UTF_8);
            }
        }
        return new String(b, StandardCharsets.UTF_8);
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    static int domain(JsonNode args) {
        String d = oneOf(args, "domain", "us", List.copyOf(DOMAINS.keySet()));
        return DOMAINS.get(d);
    }

    static String key(int domain) {
        return DOMAINS.entrySet().stream().filter(e -> e.getValue() == domain).map(Map.Entry::getKey).findFirst().orElse("?");
    }

    /** Keepa minutes → ISO-8601 UTC; null for 0/negative/missing. */
    static String keepaTime(long minutes) {
        if (minutes <= 0) return null;
        return Instant.ofEpochSecond((minutes + KEEPA_EPOCH_MINUTE) * 60).toString();
    }

    static String keepaTime(JsonNode n) {
        return n == null || !n.isNumber() ? null : keepaTime(n.asLong());
    }

    /** Integer price in the smallest unit → decimal (JPY has no minor unit); null for -1/-2. */
    static Double money(int v, int domain) {
        if (v < 0) return null;
        return domain == 5 ? (double) v : v / 100.0;
    }

    static Object value(int v, Series s, int domain) {
        if (v < 0) return null;
        if (s.price()) return money(v, domain);
        return Integer.valueOf(v);   // not a ternary: Double:Integer would promote ranks and counts to double
    }

    static List<List<Object>> series(JsonNode arr, Series s, int domain, long cutoffMinute) {
        int step = s.withShipping() ? 3 : 2;
        List<List<Object>> pts = new ArrayList<>();
        Object lastBefore = null;
        boolean hadBefore = false;
        for (int i = 0; i + step - 1 < arr.size(); i += step) {
            long t = arr.get(i).asLong();
            int v = arr.get(i + 1).asInt();
            if (s.withShipping() && v >= 0) v += Math.max(0, arr.get(i + 2).asInt());
            Object val = value(v, s, domain);
            if (t < cutoffMinute) {
                lastBefore = val;
                hadBefore = true;
                continue;
            }
            List<Object> p = new ArrayList<>(2);
            p.add(keepaTime(t));
            p.add(val);
            pts.add(p);
        }
        // Keep the value in force at the start of the window, stamped at the window start.
        if (hadBefore) {
            List<Object> p = new ArrayList<>(2);
            p.add(keepaTime(cutoffMinute));
            p.add(lastBefore);
            pts.add(0, p);
        }
        return pts;
    }

    static Map<String, Object> statRow(JsonNode arr, int domain) {
        if (!arr.isArray()) return null;
        Map<String, Object> m = new LinkedHashMap<>();
        for (Series s : SERIES) {
            if (s.index() >= arr.size()) continue;
            Object v = value(arr.get(s.index()).asInt(-1), s, domain);
            if (v != null) m.put(s.name(), v);
        }
        return m;
    }

    /** min/max arrays: per index {@code [keepaTime, value]} or null. */
    static Map<String, Object> statPoints(JsonNode arr, int domain) {
        if (!arr.isArray()) return null;
        Map<String, Object> m = new LinkedHashMap<>();
        for (Series s : SERIES) {
            JsonNode p = arr.path(s.index());
            if (!p.isArray() || p.size() < 2) continue;
            Object v = value(p.get(1).asInt(-1), s, domain);
            if (v == null) continue;
            Map<String, Object> e = new LinkedHashMap<>();
            e.put("value", v);
            e.put("at", keepaTime(p.get(0).asLong()));
            m.put(s.name(), e);
        }
        return m;
    }

    static Map<String, Object> oos(JsonNode arr) {
        if (!arr.isArray()) return null;
        Map<String, Object> m = new LinkedHashMap<>();
        for (String[] f : new String[][]{{"amazon", "0"}, {"new", "1"}}) {
            int v = arr.path(Integer.parseInt(f[1])).asInt(-1);
            if (v >= 0) m.put(f[0], v);
        }
        return m;
    }

    static Integer stockValue(JsonNode n) {
        return n.isNumber() && n.asInt() >= 0 ? n.asInt() : null;
    }

    static String availability(int code) {
        return switch (code) {
            case -1 -> "no_amazon_offer";
            case 0 -> "in_stock";
            case 1 -> "preorder";
            case 3 -> "backorder";
            case 4 -> "delayed";
            default -> "unknown";
        };
    }

    static String trim(String s, int max) {
        if (s == null) return null;
        String t = s.replaceAll("\\s+", " ").strip();
        return t.length() > max ? t.substring(0, max) + "…" : t;
    }

    /** Evenly spaced picks that always keep the first and last element. */
    static <T> List<T> downsample(List<T> in, int max) {
        if (in.size() <= max) return in;
        List<T> out = new ArrayList<>(max);
        for (int i = 0; i < max; i++) out.add(in.get((int) Math.round(i * (in.size() - 1) / (double) (max - 1))));
        return out;
    }
}
