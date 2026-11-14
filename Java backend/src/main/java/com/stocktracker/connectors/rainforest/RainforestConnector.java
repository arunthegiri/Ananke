package com.stocktracker.connectors.rainforest;

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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static com.stocktracker.connectors.ToolArgs.*;

/**
 * Rainforest API by Traject Data (Connectors roadmap, alt data): live Amazon product pages, offer
 * listings and category best-seller lists, scraped on request with the user's own key. Each call
 * costs one Rainforest credit; the account endpoint used by {@link #test} is free.
 *
 * <p>Point-in-time: Rainforest fetches Amazon live, so every result carries
 * {@code request_metadata.processed_at} as {@code observed_at}.
 */
@Component
public class RainforestConnector implements Connector {

    static final String ID = "rainforest";
    static final String BASE_URL = "https://api.rainforestapi.com";

    private static final ConnectorSpec SPEC = new ConnectorSpec(
            ID,
            "Rainforest API (Amazon)",
            ConnectorSpec.Category.ALT_DATA,
            ConnectorSpec.Licence.BYO_PERSONAL,
            "Live Amazon data by ASIN: product page (price, availability, buy-box seller, rating, best-seller "
                    + "ranks), the full offer listing (sellers, conditions, Prime/FBA, prices) and category "
                    + "best-seller lists. Each request costs one Rainforest credit.",
            "Data: Rainforest API by Traject Data",
            "https://docs.trajectdata.com/rainforestapi/product-data-api/overview",
            List.of(Field.secret("api_key", "API key", "Your Rainforest API key from app.rainforestapi.com.")),
            // No published per-minute cap (credits are the limit); requests take 1-6 s each. Keep it modest.
            List.of(RateLimit.perMinute(20)));

    private final String base;

    public RainforestConnector() {
        this(BASE_URL);
    }

    /** Test seam: point at a fake Rainforest. */
    RainforestConnector(String base) {
        this.base = base;
    }

    @Override
    public ConnectorSpec spec() {
        return SPEC;
    }

    @Override
    public String test(ConnectorContext ctx) {
        JsonNode a = call(ctx, "/account").path("account_info");
        // Only plan and credits are echoed — the account endpoint also returns name and email, which stay out.
        List<String> parts = new ArrayList<>();
        if (!a.path("plan").asText("").isBlank()) parts.add("plan " + a.path("plan").asText());
        if (a.path("credits_remaining").isNumber()) parts.add(a.path("credits_remaining").asLong() + " credits left");
        return "Connected — Rainforest answered" + (parts.isEmpty() ? "" : " (" + String.join(", ", parts) + ")") + ".";
    }

    @Override
    public List<ConnectorTool> tools() {
        return List.of(
                new ConnectorTool(ID + "_product",
                        "A live Amazon product page by ASIN: title, brand, rating and review count, best-seller ranks "
                                + "per category, buy-box price and availability, who sells/fulfils it, Prime, number of "
                                + "new offers, categories and the first feature bullets.",
                        object(props(
                                "asin", str("10-character ASIN"),
                                "amazon_domain", str("Amazon site, e.g. amazon.com (default), amazon.co.uk, amazon.de")),
                                List.of("asin")),
                        Duration.ofHours(6), this::product,
                        a -> "Reading Amazon product " + a.path("asin").asText("") + " via Rainforest…"),
                new ConnectorTool(ID + "_offers",
                        "The offer listing for an ASIN (10 offers per page): price, condition, seller (name, rating, "
                                + "rating count), Prime, fulfilled by Amazon, delivery text, and which offer holds the buy box.",
                        object(props(
                                "asin", str("10-character ASIN"),
                                "amazon_domain", str("Amazon site (default amazon.com)"),
                                "page", integer("Offer page (1-10, default 1)"),
                                "prime_only", bool("Only Prime-eligible offers"),
                                "new_only", bool("Only new-condition offers")), List.of("asin")),
                        Duration.ofHours(1), this::offers,
                        a -> "Reading Amazon offers for " + a.path("asin").asText("") + " via Rainforest…"),
                new ConnectorTool(ID + "_bestsellers",
                        "An Amazon best-seller list (by Rainforest bestsellers category id, e.g. bestsellers_appliances): "
                                + "rank, ASIN, title, price, rating; plus the category and its children.",
                        object(props(
                                "category_id", str("Rainforest bestsellers category id, e.g. bestsellers_electronics"),
                                "amazon_domain", str("Amazon site (default amazon.com)"),
                                "page", integer("Page (1-5, default 1)"),
                                "limit", integer("Max items (1-50, default 30)")), List.of("category_id")),
                        Duration.ofHours(6), this::bestsellers,
                        a -> "Reading Amazon best sellers via Rainforest…"));
    }

    // ── tools ────────────────────────────────────────────────────────────────

    Object product(ConnectorContext ctx, JsonNode args) {
        String asin = asin(args);
        String domain = domain(args);
        JsonNode r = call(ctx, "/request", "type", "product", "amazon_domain", domain, "asin", asin);
        JsonNode p = r.path("product");
        if (!p.isObject()) throw ConnectorException.badRequest("Rainforest found no product " + asin + " on " + domain + ".");
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("asin", p.path("asin").asText(asin));
        out.put("amazon_domain", domain);
        out.put("observed_at", r.path("request_metadata").path("processed_at").asText(null));
        out.put("title", trim(p.path("title").asText(null), 200));
        out.put("brand", p.path("brand").asText(null));
        out.put("link", p.path("link").asText(null));
        out.put("rating", num(p.path("rating")));
        out.put("ratings_total", num(p.path("ratings_total")));
        List<Map<String, Object>> ranks = new ArrayList<>();
        for (JsonNode b : p.path("bestsellers_rank")) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("rank", num(b.path("rank")));
            m.put("category", b.path("category").asText(null));
            ranks.add(m);
            if (ranks.size() >= 5) break;
        }
        out.put("bestsellers_rank", ranks);
        JsonNode bb = p.path("buybox_winner");
        if (bb.isObject()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("price", num(bb.path("price").path("value")));
            m.put("currency", bb.path("price").path("currency").asText(null));
            m.put("availability", bb.path("availability").path("raw").asText(null));
            m.put("availability_type", bb.path("availability").path("type").asText(null));
            m.put("is_prime", bb.path("is_prime").isBoolean() ? bb.path("is_prime").asBoolean() : null);
            JsonNode f = bb.path("fulfillment");
            m.put("sold_by_amazon", f.path("is_sold_by_amazon").isBoolean() ? f.path("is_sold_by_amazon").asBoolean() : null);
            m.put("fulfilled_by_amazon", f.path("is_fulfilled_by_amazon").isBoolean() ? f.path("is_fulfilled_by_amazon").asBoolean() : null);
            m.put("third_party_seller", f.path("third_party_seller").path("name").asText(null));
            m.put("new_offers_count", num(bb.path("new_offers_count")));
            m.put("new_offers_from", bb.path("new_offers_from").path("raw").asText(null));
            out.put("buybox", m);
        }
        List<String> cats = new ArrayList<>();
        for (JsonNode c : p.path("categories")) if (c.hasNonNull("name")) cats.add(c.path("name").asText());
        out.put("categories", cats);
        List<String> bullets = new ArrayList<>();
        for (JsonNode b : p.path("feature_bullets")) {
            bullets.add(trim(b.asText(), 200));
            if (bullets.size() >= 5) break;
        }
        out.put("feature_bullets", bullets);
        out.put("credits_remaining", r.path("request_info").path("credits_remaining").isNumber()
                ? r.path("request_info").path("credits_remaining").asLong() : null);
        return out;
    }

    Object offers(ConnectorContext ctx, JsonNode args) {
        String asin = asin(args);
        String domain = domain(args);
        int page = intArg(args, "page", 1, 1, 10);
        JsonNode r = call(ctx, "/request", "type", "offers", "amazon_domain", domain, "asin", asin, "page", page,
                "offers_prime", args.path("prime_only").asBoolean(false) ? "true" : null,
                "offers_condition_new", args.path("new_only").asBoolean(false) ? "true" : null);
        List<Map<String, Object>> rows = new ArrayList<>();
        for (JsonNode o : r.path("offers")) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("position", num(o.path("position")));
            m.put("price", num(o.path("price").path("value")));
            m.put("currency", o.path("price").path("currency").asText(null));
            m.put("condition", o.path("condition").path("title").asText(null));
            m.put("is_new", o.path("condition").path("is_new").isBoolean() ? o.path("condition").path("is_new").asBoolean() : null);
            m.put("seller", o.path("seller").path("name").asText(null));
            m.put("seller_id", o.path("seller").path("id").asText(null));
            m.put("seller_rating", num(o.path("seller").path("rating")));
            m.put("seller_ratings_total", num(o.path("seller").path("ratings_total")));
            m.put("is_prime", o.path("is_prime").isBoolean() ? o.path("is_prime").asBoolean() : null);
            m.put("fulfilled_by_amazon", o.path("delivery").path("fulfilled_by_amazon").isBoolean()
                    ? o.path("delivery").path("fulfilled_by_amazon").asBoolean() : null);
            m.put("delivery", trim(o.path("delivery").path("comments").asText(o.path("delivery").path("raw").asText(null)), 120));
            m.put("buybox_winner", o.path("buybox_winner").asBoolean(false));
            rows.add(m);
            if (rows.size() >= 20) break;
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("asin", asin);
        out.put("amazon_domain", domain);
        out.put("observed_at", r.path("request_metadata").path("processed_at").asText(null));
        out.put("title", trim(r.path("product").path("title").asText(null), 200));
        out.put("page", page);
        out.put("total_pages", r.path("pagination").path("total_pages").isNumber() ? r.path("pagination").path("total_pages").asInt() : null);
        out.put("count", rows.size());
        out.put("offers", rows);
        return out;
    }

    Object bestsellers(ConnectorContext ctx, JsonNode args) {
        String category = requireStr(args, "category_id", 120);
        if (!category.matches("^[A-Za-z0-9_\\-]+$")) throw ConnectorException.badRequest("category_id must look like bestsellers_appliances");
        String domain = domain(args);
        int page = intArg(args, "page", 1, 1, 5);
        int limit = intArg(args, "limit", 30, 1, 50);
        JsonNode r = call(ctx, "/request", "type", "bestsellers", "amazon_domain", domain, "category_id", category, "page", page);
        List<Map<String, Object>> rows = new ArrayList<>();
        for (JsonNode b : r.path("bestsellers")) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("rank", num(b.path("rank")));
            m.put("asin", b.path("asin").asText(null));
            m.put("title", trim(b.path("title").asText(null), 150));
            m.put("price", num(b.path("price").path("value")));
            m.put("currency", b.path("price").path("currency").asText(null));
            m.put("rating", num(b.path("rating")));
            m.put("ratings_total", num(b.path("ratings_total")));
            rows.add(m);
            if (rows.size() >= limit) break;
        }
        JsonNode info = r.path("bestsellers_info");
        List<String> children = new ArrayList<>();
        for (JsonNode c : info.path("child_categories")) {
            if (c.hasNonNull("name")) children.add(c.path("name").asText());
            if (children.size() >= 20) break;
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("category", info.path("current_category").path("name").asText(category));
        out.put("parent_category", info.path("parent_category").path("name").asText(null));
        out.put("child_categories", children);
        out.put("amazon_domain", domain);
        out.put("observed_at", r.path("request_metadata").path("processed_at").asText(null));
        out.put("page", page);
        out.put("count", rows.size());
        out.put("bestsellers", rows);
        return out;
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    /** One Rainforest call; maps the documented 402 (out of credits) and a {@code success:false} body. */
    JsonNode call(ConnectorContext ctx, String path, Object... query) {
        Object[] kv = new Object[query.length + 2];
        kv[0] = "api_key";
        kv[1] = ctx.require("api_key");
        System.arraycopy(query, 0, kv, 2, query.length);
        JsonNode r;
        try {
            r = ctx.getJson(URI.create(url(base + path, kv)), Map.of());
        } catch (ConnectorException e) {
            if (e.kind() == ConnectorException.Kind.UPSTREAM && e.getMessage().contains("HTTP 402")) {
                throw new ConnectorException(ConnectorException.Kind.RATE_LIMITED,
                        "Rainforest: the account is out of credits or has a payment problem (HTTP 402).");
            }
            if (e.kind() == ConnectorException.Kind.UPSTREAM && e.getMessage().contains("HTTP 400")) {
                throw ConnectorException.badRequest(e.getMessage());
            }
            throw e;
        }
        JsonNode info = r.path("request_info");
        if (info.path("success").isBoolean() && !info.path("success").asBoolean()) {
            String msg = info.path("message").asText("request failed");
            boolean auth = msg.toLowerCase(Locale.ROOT).contains("api_key");
            throw new ConnectorException(auth ? ConnectorException.Kind.AUTH : ConnectorException.Kind.UPSTREAM, "Rainforest: " + msg);
        }
        return r;
    }

    static String asin(JsonNode args) {
        String asin = requireStr(args, "asin", 10).toUpperCase(Locale.ROOT);
        if (!asin.matches("^[A-Z0-9]{10}$")) throw ConnectorException.badRequest("asin must be 10 letters/digits");
        return asin;
    }

    static String domain(JsonNode args) {
        String d = optStr(args, "amazon_domain");
        if (d == null) return "amazon.com";
        d = d.toLowerCase(Locale.ROOT).replaceFirst("^https?://", "").replaceFirst("^www\\.", "");
        if (!d.matches("^amazon\\.[a-z]{2,3}(\\.[a-z]{2})?$")) throw ConnectorException.badRequest("amazon_domain must look like amazon.com or amazon.co.uk");
        return d;
    }

    static Double num(JsonNode n) {
        if (n == null || n.isMissingNode() || n.isNull()) return null;
        if (n.isNumber()) return n.asDouble();
        try {
            return Double.parseDouble(n.asText().replace(",", "").strip());
        } catch (Exception e) {
            return null;
        }
    }

    static String trim(String s, int max) {
        if (s == null) return null;
        String t = s.replaceAll("\\s+", " ").strip();
        return t.length() > max ? t.substring(0, max) + "…" : t;
    }
}
