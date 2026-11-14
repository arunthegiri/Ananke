package com.stocktracker.connectors.keepa;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stocktracker.connectors.ConnectorContext;
import com.stocktracker.connectors.ConnectorException;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.zip.GZIPOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Keepa connector against a fake Keepa that gzips every body, like the real one. */
class KeepaConnectorTest {

    static final String GOOD = "k".repeat(64);
    /** 2026-11-14T00:00Z in Keepa minutes. */
    static final long NOW_KM = Instant.parse("2026-11-14T00:00:00Z").getEpochSecond() / 60 - KeepaConnector.KEEPA_EPOCH_MINUTE;

    final ObjectMapper om = new ObjectMapper();
    final List<String> paths = new CopyOnWriteArrayList<>();
    final AtomicInteger permits = new AtomicInteger();
    HttpServer server;
    KeepaConnector keepa;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.start();
        keepa = new KeepaConnector(Clock.fixed(Instant.parse("2026-11-14T00:00:00Z"), ZoneOffset.UTC),
                "http://127.0.0.1:" + server.getAddress().getPort());
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    ConnectorContext ctx(String key) {
        return new ConnectorContext(keepa.spec(), k -> k.equals("api_key") ? Optional.ofNullable(key) : Optional.empty(),
                HttpClient.newHttpClient(), om, permits::incrementAndGet);
    }

    void handle(HttpExchange ex) throws IOException {
        String p = ex.getRequestURI().getPath();
        String q = ex.getRequestURI().getRawQuery();
        paths.add(p + (q == null ? "" : "?" + q));
        String key = q == null ? "" : q.replaceAll(".*key=([^&]*).*", "$1");
        if (key.length() != 64) {
            send(ex, 400, "{\"error\":{\"details\":\"\",\"message\":\"You used an invalid parameter for this API call.\",\"type\":\"invalidParameter\"},\"tokensLeft\":0}");
            return;
        }
        if (!key.equals(GOOD)) {
            send(ex, 402, "{\"error\":{\"message\":\"Operation unauthorized. No active API plan found.\",\"type\":\"unauthorized\"},\"tokensLeft\":0}");
            return;
        }
        switch (p) {
            case "/token" -> send(ex, 200, "{\"refillIn\":4000,\"refillRate\":20,\"tokenFlowReduction\":0.0,\"tokensLeft\":1180}");
            case "/product" -> {
                if (q.contains("asin=B000000000")) {
                    send(ex, 200, "{\"products\":[],\"tokensLeft\":1179}");
                    return;
                }
                if (q.contains("asin=B0EMPTYTOK")) {
                    send(ex, 429, "{\"refillIn\":30000,\"tokensLeft\":-12}");
                    return;
                }
                send(ex, 200, product());
            }
            case "/search" -> send(ex, 200, """
                    {"products":[{"asin":"B0088PUEPK","title":"Western Digital 1TB","brand":"WD","monthlySold":2000,
                      "stats":{"current":[4999,4599,-1,321,-1,-1,-1,-1,-1,-1,-1,14,-1]}}],"tokensLeft":1100}""");
            case "/bestsellers" -> send(ex, 200, "{\"bestSellersList\":{\"domainId\":1,\"lastUpdate\":" + NOW_KM
                    + ",\"categoryId\":172282,\"asinList\":[" + IntStream.range(0, 300).mapToObj(i -> "\"B00000" + String.format("%04d", i) + "\"")
                    .collect(Collectors.joining(",")) + "]},\"tokensLeft\":1000}");
            default -> send(ex, 404, "{}");
        }
    }

    /** ~400 days of daily Amazon prices (cents) and a sales-rank series; -1 = out of stock. */
    static String product() {
        String amazon = IntStream.range(0, 400).mapToObj(i -> (NOW_KM - (399 - i) * 1440L) + "," + (i == 390 ? -1 : 2000 + i))
                .collect(Collectors.joining(","));
        return """
                {"products":[{"asin":"B0088PUEPK","title":"Western Digital 1TB External Hard Drive","brand":"WD",
                  "manufacturer":"Western Digital","rootCategory":172282,"monthlySold":2000,"availabilityAmazon":0,
                  "lastUpdate":%d,"lastPriceChange":%d,"trackingSince":1000,"listedSince":0,
                  "csv":[[%s],[%d,4599],null,[%d,500,%d,321],null,null,null,null,null,null,null,[%d,14]],
                  "stats":{"current":[2399,4599,-1,321,-1,-1,-1,-1,-1,-1,-1,14,-1],"avg":[2300,4600,-1,400],"avg30":[2390,4600,-1,350],
                    "avg90":[2350,4600,-1,380],"min":[[%d,2000],null,null,[%d,300]],"max":[[%d,2399],null,null,[%d,900]],
                    "outOfStockPercentage30":[3,0],"outOfStockPercentage90":[1,0]}}],"tokensLeft":1179}
                """.formatted(NOW_KM - 60, NOW_KM - 1440, amazon, NOW_KM - 10, NOW_KM - 5000, NOW_KM - 100, NOW_KM - 20,
                NOW_KM - 9000, NOW_KM - 200, NOW_KM - 100, NOW_KM - 8000);
    }

    void send(HttpExchange ex, int status, String body) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (GZIPOutputStream gz = new GZIPOutputStream(bos)) {
            gz.write(body.getBytes(StandardCharsets.UTF_8));
        }
        byte[] b = bos.toByteArray();
        ex.getResponseHeaders().add("Content-Type", "application/json;charset=UTF-8");
        ex.getResponseHeaders().add("Content-Encoding", "gzip");
        ex.sendResponseHeaders(status, b.length);
        ex.getResponseBody().write(b);
        ex.close();
    }

    static ConnectorException.Kind kind(Throwable e) {
        return ((ConnectorException) e).kind();
    }

    @Test
    void keepaMinutes_convertToIso() {
        assertThat(KeepaConnector.keepaTime(0)).isNull();
        // From Keepa's docs: 411180 → 1318510800000 (2011-10-13T13:00:00Z).
        assertThat(KeepaConnector.keepaTime(411180)).isEqualTo(Instant.ofEpochMilli(1318510800000L).toString())
                .isEqualTo("2011-10-13T13:00:00Z");
    }

    @Test
    void test_gunzipsTheBody_andMapsBadKeysToAuth() throws Exception {
        assertThat(keepa.test(ctx(GOOD))).contains("1180 tokens left").contains("20/min");
        assertThat(permits).hasValue(1);
        assertThatThrownBy(() -> keepa.test(ctx("short"))).satisfies(e -> assertThat(kind(e)).isEqualTo(ConnectorException.Kind.AUTH));
        assertThatThrownBy(() -> keepa.test(ctx("x".repeat(64))))
                .hasMessageContaining("No active API plan").satisfies(e -> assertThat(kind(e)).isEqualTo(ConnectorException.Kind.AUTH));
    }

    @Test
    void errorBodyWithHttp200_isNotTreatedAsData() {
        assertThatThrownBy(() -> KeepaConnector.check(200, om.readTree("{\"error\":{\"type\":\"unauthorized\",\"message\":\"nope\"}}")))
                .satisfies(e -> assertThat(kind(e)).isEqualTo(ConnectorException.Kind.AUTH));
    }

    @Test
    @SuppressWarnings("unchecked")
    void product_convertsTimesAndPrices_windowsAndDownsamplesHistory() throws Exception {
        Map<String, Object> out = (Map<String, Object>) keepa.product(ctx(GOOD),
                om.readTree("{\"asin\":\"b0088puepk\",\"days\":90,\"points\":30}"));
        assertThat(paths.get(0)).contains("domain=1").contains("asin=B0088PUEPK").contains("stats=90").contains("days=90").doesNotContain("offers=");
        assertThat(out).containsEntry("currency", "USD").containsEntry("amazon_availability", "in_stock")
                .containsEntry("last_update", "2026-11-13T23:00:00Z").containsEntry("listed_since", null)
                .containsEntry("monthly_sold", 2000);

        Map<String, Object> stats = (Map<String, Object>) out.get("stats");
        assertThat((Map<String, Object>) stats.get("current")).containsEntry("amazon_price", 23.99).containsEntry("new_price", 45.99)
                .containsEntry("sales_rank", 321).containsEntry("new_offer_count", 14).doesNotContainKey("used_price");
        assertThat((Map<String, Object>) ((Map<String, Object>) stats.get("min")).get("amazon_price")).containsEntry("value", 20.0);
        assertThat((Map<String, Object>) stats.get("out_of_stock_pct_30d")).containsEntry("amazon", 3);

        Map<String, Object> history = (Map<String, Object>) out.get("history");
        List<List<Object>> amazon = (List<List<Object>>) history.get("amazon_price");
        assertThat(amazon).hasSize(30);
        assertThat(amazon.get(0).get(0)).isEqualTo("2026-08-16T00:00:00Z");                    // window start (90 days)
        assertThat(amazon.get(amazon.size() - 1)).containsExactly("2026-11-14T00:00:00Z", 23.99);  // latest kept
        List<List<Object>> rank = (List<List<Object>>) history.get("sales_rank");
        assertThat(rank).extracting(p -> p.get(1)).containsExactly(500, 321);
        // The out-of-stock day (value -1) is represented as null somewhere in the full series.
        Map<String, Object> full = (Map<String, Object>) keepa.product(ctx(GOOD), om.readTree("{\"asin\":\"B0088PUEPK\",\"days\":30,\"points\":200}"));
        assertThat((List<List<Object>>) ((Map<String, Object>) full.get("history")).get("amazon_price")).anyMatch(p -> p.get(1) == null);
    }

    @Test
    void product_argValidation_unknownAsin_andTokenExhaustion() throws Exception {
        assertThatThrownBy(() -> keepa.product(ctx(GOOD), om.readTree("{\"asin\":\"short\"}")))
                .satisfies(e -> assertThat(kind(e)).isEqualTo(ConnectorException.Kind.BAD_REQUEST));
        assertThatThrownBy(() -> keepa.product(ctx(GOOD), om.readTree("{\"asin\":\"B0088PUEPK\",\"domain\":\"mars\"}")))
                .satisfies(e -> assertThat(kind(e)).isEqualTo(ConnectorException.Kind.BAD_REQUEST));
        assertThat(paths).isEmpty();
        assertThatThrownBy(() -> keepa.product(ctx(GOOD), om.readTree("{\"asin\":\"B000000000\"}")))
                .satisfies(e -> assertThat(kind(e)).isEqualTo(ConnectorException.Kind.BAD_REQUEST));
        assertThatThrownBy(() -> keepa.product(ctx(GOOD), om.readTree("{\"asin\":\"B0EMPTYTOK\"}")))
                .hasMessageContaining("refill in 30s").satisfies(e -> assertThat(kind(e)).isEqualTo(ConnectorException.Kind.RATE_LIMITED));
    }

    @Test
    @SuppressWarnings("unchecked")
    void product_withOffers_requestsStockAndBuyBox() throws Exception {
        keepa.product(ctx(GOOD), om.readTree("{\"asin\":\"B0088PUEPK\",\"include_offers\":true,\"domain\":\"de\"}"));
        assertThat(paths.get(0)).contains("domain=3").contains("offers=20").contains("stock=1").contains("buybox=1");
    }

    @Test
    @SuppressWarnings("unchecked")
    void search_andBestsellers_areBounded() throws Exception {
        Map<String, Object> s = (Map<String, Object>) keepa.search(ctx(GOOD), om.readTree("{\"term\":\"hard drive\",\"page\":42}"));
        assertThat(paths.get(0)).contains("type=product").contains("term=hard+drive").contains("page=9").contains("history=0");
        assertThat(((List<Map<String, Object>>) s.get("products")).get(0)).containsEntry("amazon_price", 49.99)
                .containsEntry("sales_rank", 321).containsEntry("new_offer_count", 14);

        Map<String, Object> b = (Map<String, Object>) keepa.bestsellers(ctx(GOOD), om.readTree("{\"category\":\"172282\",\"limit\":10}"));
        assertThat((List<String>) b.get("asins")).hasSize(10).first().isEqualTo("B000000000");
        assertThat(b).containsEntry("total", 300).containsEntry("updated_at", "2026-11-14T00:00:00Z");
        assertThatThrownBy(() -> keepa.bestsellers(ctx(GOOD), om.readTree("{\"category\":\"Electronics\"}")))
                .satisfies(e -> assertThat(kind(e)).isEqualTo(ConnectorException.Kind.BAD_REQUEST));
    }
}
