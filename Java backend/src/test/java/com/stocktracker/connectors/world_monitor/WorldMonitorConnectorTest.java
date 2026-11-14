package com.stocktracker.connectors.world_monitor;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stocktracker.connectors.ConnectorContext;
import com.stocktracker.connectors.ConnectorException;
import com.stocktracker.connectors.ConnectorTool;
import com.stocktracker.connectors.remote.FakeMcpServer;
import com.stocktracker.connectors.remote.RemoteMcpConnector;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.net.http.HttpClient;
import java.time.Clock;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** World Monitor against a fake MCP server serving the recorded real tools/list. */
class WorldMonitorConnectorTest {

    static final String KEY = "wm_" + "0".repeat(40);

    final ObjectMapper om = new ObjectMapper();
    final AtomicInteger permits = new AtomicInteger();
    FakeMcpServer mcp;
    WorldMonitorConnector wm;
    JsonNode recorded;

    static JsonNode recordedToolsList(ObjectMapper om) throws Exception {
        try (InputStream in = WorldMonitorConnectorTest.class.getResourceAsStream("/connectors/world_monitor_tools_list.json")) {
            return om.readTree(in).path("result").path("tools");
        }
    }

    @BeforeEach
    void start() throws Exception {
        recorded = recordedToolsList(om);
        mcp = new FakeMcpServer(WorldMonitorConnector.KEY_HEADER);
        mcp.tools = recorded;
        mcp.authHeader = WorldMonitorConnector.KEY_HEADER;
        mcp.authValue = KEY;
        wm = new WorldMonitorConnector(Clock.systemUTC(), mcp.url());
    }

    @AfterEach
    void stop() {
        mcp.close();
    }

    ConnectorContext ctx(String key) {
        return new ConnectorContext(wm.spec(), k -> k.equals("api_key") ? Optional.ofNullable(key) : Optional.empty(),
                HttpClient.newHttpClient(), om, permits::incrementAndGet);
    }

    ConnectorTool named(String name) {
        return wm.tools().stream().filter(t -> t.name().equals(name)).findFirst().orElseThrow();
    }

    @Test
    void declaredTools_matchTheRecordedRealToolsList() {
        Map<String, JsonNode> byName = new HashMap<>();
        recorded.forEach(t -> byName.put(t.path("name").asText(), t));
        for (String upstream : List.of("get_market_data", "get_economic_data", "get_country_macro", "get_price_and_debt_indicators",
                "get_country_risk", "get_conflict_events", "get_news_intelligence", "get_world_brief")) {
            JsonNode live = byName.get(upstream);
            assertThat(live).as(upstream).isNotNull();
            assertThat(RemoteMcpConnector.verifyAgainst(wm.declared(upstream), live)).as(upstream).isEmpty();
        }
        assertThat(RemoteMcpConnector.readOnly(byName.get(WorldMonitorConnector.ALLOWANCE_TOOL))).isTrue();
        // The real list does carry writing tools; none is allowlisted.
        for (String w : List.of("start_upgrade", "report_issue", "get_stock_research")) {
            assertThat(RemoteMcpConnector.readOnly(byName.get(w))).as(w).isFalse();
        }
        assertThat(wm.tools()).extracting(ConnectorTool::name).allMatch(n -> n.startsWith("world_monitor_get_"))
                .contains("world_monitor_get_market_data", "world_monitor_get_country_risk", "world_monitor_get_world_brief")
                .hasSize(8);
    }

    @Test
    void test_listsThenReadsTheAllowance_onePermitPerRequest() {
        mcp.onCall = (n, a) -> mcp.structuredResult(om.valueToTree(Map.of("access", "subscription", "used", 12, "limit", 1000,
                "remaining", 988, "resetsAt", "2026-11-15T00:00:00.000Z", "sharedWithRestApi", false)));
        String msg = wm.test(ctx(KEY));
        assertThat(msg).contains("8 of 8 allowlisted tools").contains("12 used of 1000 (subscription)");
        assertThat(mcp.methods()).containsExactly("initialize", "notifications/initialized", "tools/list", "tools/call");
        assertThat(mcp.seen.get(3).tool()).isEqualTo("get_mcp_allowance");
        assertThat(mcp.seen).allSatisfy(s -> assertThat(s.headers()).containsEntry("X-WorldMonitor-Key", KEY));
        assertThat(permits.get()).isEqualTo(4);
    }

    @Test
    void badKey_isAuth_withoutEchoingTheKey() {
        assertThatThrownBy(() -> wm.test(ctx("wm_wrong")))
                .isInstanceOfSatisfying(ConnectorException.class, e -> assertThat(e.kind()).isEqualTo(ConnectorException.Kind.AUTH))
                .hasMessageContaining("Invalid API key").hasMessageNotContaining("wm_wrong");
        assertThat(mcp.methods()).containsExactly("initialize");
        assertThatThrownBy(() -> wm.test(ctx(null)))
                .isInstanceOfSatisfying(ConnectorException.class, e -> assertThat(e.kind()).isEqualTo(ConnectorException.Kind.NOT_LINKED));
    }

    @Test
    @SuppressWarnings("unchecked")
    void marketData_forwardsOnlyDeclaredArgs_andCarriesCachedAt() throws Exception {
        mcp.onCall = (n, a) -> mcp.structuredResult(om.valueToTree(Map.of(
                "cached_at", "2026-11-14T18:30:12.000Z", "stale", false,
                "data", Map.of("commodities", List.of(Map.of("symbol", "GC=F", "price", 2712.4, "change", 0.42))))));
        Map<String, Object> out = (Map<String, Object>) named("world_monitor_get_market_data").handler().call(ctx(KEY),
                om.readTree("{\"symbols\":[\"GC=F\"],\"asset_class\":[\"commodity\"],\"refresh\":true,\"request_id\":\"x\",\"jmespath\":\"data\",\"limit\":0}"));
        JsonNode sent = mcp.seen.get(3).arguments();
        assertThat(sent.fieldNames()).toIterable().containsExactlyInAnyOrder("symbols", "asset_class", "limit");
        assertThat(sent.get("limit").asInt()).isEqualTo(1);
        assertThat(out).containsEntry("as_of", "2026-11-14T18:30:12.000Z").containsEntry("stale", false);
    }

    @Test
    void paidOnlyTool_onAFreeAccount_isABadRequestNotAnOutage() {
        mcp.onCall = (n, a) -> mcp.textResult("upgrade-required: get_country_risk requires a paid plan.").put("isError", true);
        assertThatThrownBy(() -> named("world_monitor_get_country_risk").handler().call(ctx(KEY), om.createObjectNode().put("country_code", "IQ")))
                .isInstanceOfSatisfying(ConnectorException.class, e -> assertThat(e.kind()).isEqualTo(ConnectorException.Kind.BAD_REQUEST));
    }
}
