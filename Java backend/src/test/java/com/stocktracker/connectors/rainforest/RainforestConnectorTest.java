package com.stocktracker.connectors.rainforest;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stocktracker.connectors.ConnectorContext;
import com.stocktracker.connectors.ConnectorException;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Rainforest connector against a fake API shaped like the documented results. */
class RainforestConnectorTest {

    final ObjectMapper om = new ObjectMapper();
    final List<String> paths = new CopyOnWriteArrayList<>();
    HttpServer server;
    RainforestConnector rf;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.start();
        rf = new RainforestConnector("http://127.0.0.1:" + server.getAddress().getPort());
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    ConnectorContext ctx(String key) {
        return new ConnectorContext(rf.spec(), k -> k.equals("api_key") ? Optional.ofNullable(key) : Optional.empty(),
                HttpClient.newHttpClient(), om, () -> { });
    }

    void handle(HttpExchange ex) throws IOException {
        String p = ex.getRequestURI().getPath();
        String q = ex.getRequestURI().getQuery();
        paths.add(p + (q == null ? "" : "?" + q));
        if (q == null || !q.contains("api_key=good") && !q.contains("api_key=broke") && !q.contains("api_key=soft")) {
            send(ex, 401, "{\"request_info\":{\"success\":false,\"message\":\"Supplied api_key is not valid\"}}");
            return;
        }
        if (q.contains("api_key=broke")) {
            send(ex, 402, "{\"request_info\":{\"success\":false,\"message\":\"Out of credits\"}}");
            return;
        }
        if (q.contains("api_key=soft")) {
            send(ex, 200, "{\"request_info\":{\"success\":false,\"message\":\"Supplied api_key is not valid\"}}");
            return;
        }
        if (p.equals("/account")) {
            send(ex, 200, """
                    {"request_info":{"success":true},"account_info":{"api_key":"good","name":"John Smith","email":"john.smith@example.com",
                      "plan":"Starter","credits_used":115,"credits_limit":15000,"credits_remaining":14885}}""");
            return;
        }
        String type = q.replaceAll(".*type=([a-z]+).*", "$1");
        switch (type) {
            case "product" -> send(ex, 200, """
                    {"request_info":{"success":true,"credits_used":116,"credits_remaining":14884},
                     "request_metadata":{"created_at":"2026-11-14T10:00:00.000Z","processed_at":"2026-11-14T10:00:03.000Z","amazon_url":"https://www.amazon.com/dp/B073JYC4XM"},
                     "product":{"title":"Memory Card","asin":"B073JYC4XM","brand":"SanDisk","link":"https://www.amazon.com/dp/B073JYC4XM",
                       "rating":4.8,"ratings_total":231000,
                       "bestsellers_rank":[{"rank":1,"category":"Computer Memory Cards"},{"rank":12,"category":"Electronics"}],
                       "categories":[{"name":"Electronics"},{"name":"Memory Cards"}],
                       "feature_bullets":["%s","b","c","d","e","f","g"],
                       "buybox_winner":{"new_offers_count":24,"new_offers_from":{"raw":"$9.99"},"is_prime":true,
                         "availability":{"type":"in_stock","raw":"In Stock"},
                         "fulfillment":{"is_sold_by_amazon":true,"is_fulfilled_by_amazon":true,"is_sold_by_third_party":false},
                         "price":{"symbol":"$","value":12.49,"currency":"USD","raw":"$12.49"}}}}""".formatted("x".repeat(500)));
            case "offers" -> send(ex, 200, "{\"request_info\":{\"success\":true},\"request_metadata\":{\"processed_at\":\"2026-11-14T10:01:00.000Z\"},"
                    + "\"product\":{\"title\":\"Memory Card\",\"asin\":\"B073JYC4XM\"},\"pagination\":{\"current_page\":1,\"total_pages\":3},\"offers\":["
                    + IntStream.range(0, 30).mapToObj(i -> "{\"position\":" + (i + 1) + ",\"price\":{\"value\":" + (12 + i)
                    + ",\"currency\":\"USD\"},\"condition\":{\"is_new\":true,\"title\":\"New\"},\"seller\":{\"name\":\"Seller " + i
                    + "\",\"id\":\"A" + i + "\",\"rating\":4.5,\"ratings_total\":100},\"is_prime\":" + (i == 0)
                    + ",\"delivery\":{\"fulfilled_by_amazon\":" + (i == 0) + ",\"comments\":\"FREE delivery\"},\"buybox_winner\":" + (i == 0) + "}")
                    .collect(Collectors.joining(",")) + "]}");
            case "bestsellers" -> send(ex, 200, """
                    {"request_info":{"success":true},"request_metadata":{"processed_at":"2026-11-14T10:02:00.000Z"},
                     "bestsellers":[{"rank":1,"position":1,"title":"Action Camera","asin":"B07TXR9Y5G","rating":4.4,"ratings_total":12000,
                       "price":{"symbol":"$","value":"89.99","currency":"USD","raw":"$89.99"}}],
                     "bestsellers_info":{"current_category":{"name":"Camera & Photo","id":"502394"},"parent_category":{"name":"Electronics"},
                       "child_categories":[{"name":"Action Cameras"}]},"pagination":{"current_page":1,"total_pages":2}}""");
            default -> send(ex, 400, "{\"request_info\":{\"success\":false,\"message\":\"Unsupported type\"}}");
        }
    }

    void send(HttpExchange ex, int status, String body) throws IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(status, b.length);
        ex.getResponseBody().write(b);
        ex.close();
    }

    static ConnectorException.Kind kind(Throwable e) {
        return ((ConnectorException) e).kind();
    }

    @Test
    void test_usesTheFreeAccountEndpoint_andNeverEchoesNameOrEmail() throws Exception {
        String line = rf.test(ctx("good"));
        assertThat(line).contains("plan Starter").contains("14885 credits left").doesNotContain("John").doesNotContain("@");
        assertThat(paths.get(0)).startsWith("/account?api_key=good");
    }

    @Test
    void badKeys_includingA200WithSuccessFalse_areAuth_andNoCreditsIsRateLimited() {
        assertThatThrownBy(() -> rf.test(ctx("bad"))).satisfies(e -> assertThat(kind(e)).isEqualTo(ConnectorException.Kind.AUTH));
        assertThatThrownBy(() -> rf.test(ctx("soft"))).satisfies(e -> assertThat(kind(e)).isEqualTo(ConnectorException.Kind.AUTH));
        assertThatThrownBy(() -> rf.test(ctx("broke")))
                .hasMessageContaining("out of credits").satisfies(e -> assertThat(kind(e)).isEqualTo(ConnectorException.Kind.RATE_LIMITED));
    }

    @Test
    @SuppressWarnings("unchecked")
    void product_isCompact_withObservedTime() throws Exception {
        Map<String, Object> out = (Map<String, Object>) rf.product(ctx("good"), om.readTree("{\"asin\":\"b073jyc4xm\",\"amazon_domain\":\"www.Amazon.com\"}"));
        assertThat(paths.get(0)).contains("type=product").contains("amazon_domain=amazon.com").contains("asin=B073JYC4XM");
        assertThat(out).containsEntry("observed_at", "2026-11-14T10:00:03.000Z").containsEntry("rating", 4.8).containsEntry("credits_remaining", 14884L);
        assertThat((Map<String, Object>) out.get("buybox")).containsEntry("price", 12.49).containsEntry("sold_by_amazon", true)
                .containsEntry("availability", "In Stock").containsEntry("new_offers_count", 24.0);
        assertThat((List<String>) out.get("feature_bullets")).hasSize(5).first().asString().hasSize(201);
    }

    @Test
    @SuppressWarnings("unchecked")
    void offers_areCappedAndFlagTheBuyBox() throws Exception {
        Map<String, Object> out = (Map<String, Object>) rf.offers(ctx("good"), om.readTree("{\"asin\":\"B073JYC4XM\",\"prime_only\":true,\"page\":99}"));
        List<Map<String, Object>> offers = (List<Map<String, Object>>) out.get("offers");
        assertThat(offers).hasSize(20);
        assertThat(offers.get(0)).containsEntry("buybox_winner", true).containsEntry("price", 12.0).containsEntry("seller", "Seller 0");
        assertThat(out).containsEntry("total_pages", 3).containsEntry("page", 10);
        assertThat(paths.get(0)).contains("offers_prime=true").contains("page=10").doesNotContain("offers_condition_new");
    }

    @Test
    @SuppressWarnings("unchecked")
    void bestsellers_parseStringPrices_andValidateArgs() throws Exception {
        Map<String, Object> out = (Map<String, Object>) rf.bestsellers(ctx("good"), om.readTree("{\"category_id\":\"bestsellers_camera\"}"));
        assertThat(((List<Map<String, Object>>) out.get("bestsellers")).get(0)).containsEntry("price", 89.99).containsEntry("asin", "B07TXR9Y5G");
        assertThat(out).containsEntry("category", "Camera & Photo");
        assertThatThrownBy(() -> rf.bestsellers(ctx("good"), om.readTree("{\"category_id\":\"bad id; drop\"}")))
                .satisfies(e -> assertThat(kind(e)).isEqualTo(ConnectorException.Kind.BAD_REQUEST));
        assertThatThrownBy(() -> rf.product(ctx("good"), om.readTree("{\"asin\":\"B073JYC4XM\",\"amazon_domain\":\"evil.example.com\"}")))
                .satisfies(e -> assertThat(kind(e)).isEqualTo(ConnectorException.Kind.BAD_REQUEST));
        assertThatThrownBy(() -> rf.product(ctx("good"), om.readTree("{}")))
                .satisfies(e -> assertThat(kind(e)).isEqualTo(ConnectorException.Kind.BAD_REQUEST));
    }
}
