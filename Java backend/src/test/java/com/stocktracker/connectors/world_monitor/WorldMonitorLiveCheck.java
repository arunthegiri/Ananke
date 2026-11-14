package com.stocktracker.connectors.world_monitor;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stocktracker.connectors.ConnectorContext;
import com.stocktracker.connectors.RateLimiter;
import com.stocktracker.connectors.remote.RemoteMcpConnector;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Opt-in live check (CONNECTORS_LIVE=1): World Monitor's public, credential-free {@code tools/list}
 * still offers every allowlisted tool, read-only, with the declared parameters. With
 * WORLD_MONITOR_KEY set it also runs the connector's own test (initialize + list + allowance,
 * which spends no daily allowance). Not a *Test class: never in the normal suite.
 */
@EnabledIfEnvironmentVariable(named = "CONNECTORS_LIVE", matches = "1")
class WorldMonitorLiveCheck {

    final ObjectMapper om = new ObjectMapper();
    final WorldMonitorConnector wm = new WorldMonitorConnector();

    @Test
    void publicToolsList_stillMatchesTheAllowlist() throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(WorldMonitorConnector.MCP_URL))
                .timeout(Duration.ofSeconds(60))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream")
                .header("MCP-Protocol-Version", "2025-06-18")
                .header("User-Agent", "Ananke/1")
                .POST(HttpRequest.BodyPublishers.ofString("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\",\"params\":{}}"))
                .build();
        HttpResponse<String> r = HttpClient.newHttpClient().send(req, HttpResponse.BodyHandlers.ofString());
        assertThat(r.statusCode()).isEqualTo(200);
        String body = r.body();
        if (r.headers().firstValue("Content-Type").orElse("").startsWith("text/event-stream")) {
            body = body.lines().filter(l -> l.startsWith("data:")).map(l -> l.substring(5).strip()).findFirst().orElse("{}");
        }
        JsonNode tools = om.readTree(body).path("result").path("tools");
        Map<String, JsonNode> byName = new HashMap<>();
        tools.forEach(t -> byName.put(t.path("name").asText(), t));
        System.out.println("World Monitor tools/list: " + byName.size() + " tools");

        for (String upstream : new String[] {"get_market_data", "get_economic_data", "get_country_macro", "get_price_and_debt_indicators",
                "get_country_risk", "get_conflict_events", "get_news_intelligence", "get_world_brief", "get_mcp_allowance"}) {
            JsonNode live = byName.get(upstream);
            assertThat(live).as(upstream + " is offered").isNotNull();
            if (!upstream.equals(WorldMonitorConnector.ALLOWANCE_TOOL)) {
                var drift = RemoteMcpConnector.verifyAgainst(wm.declared(upstream), live);
                System.out.println("  " + upstream + " weight=" + live.path("_meta").path("worldmonitor/weight").asText()
                        + " access=" + live.path("_meta").path("worldmonitor/access").asText() + " drift=" + drift);
                assertThat(drift).as(upstream).isEmpty();
            } else {
                assertThat(RemoteMcpConnector.readOnly(live)).isTrue();
            }
        }

        String key = System.getenv("WORLD_MONITOR_KEY");
        if (key != null && !key.isBlank()) {
            RateLimiter limiter = RateLimiter.system();
            ConnectorContext ctx = new ConnectorContext(wm.spec(), k -> Optional.of(key), HttpClient.newHttpClient(), om,
                    () -> limiter.acquire(wm.spec()));
            System.out.println("test: " + wm.test(ctx));
        }
    }
}
