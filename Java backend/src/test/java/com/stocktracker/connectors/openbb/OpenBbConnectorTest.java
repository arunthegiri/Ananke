package com.stocktracker.connectors.openbb;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.stocktracker.connectors.ConnectorContext;
import com.stocktracker.connectors.ConnectorException;
import com.stocktracker.connectors.ConnectorTool;
import com.stocktracker.connectors.remote.FakeMcpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.http.HttpClient;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** OpenBB sidecar against a fake openbb-mcp-server (tool names follow its route_naming rule). */
class OpenBbConnectorTest {

    final ObjectMapper om = new ObjectMapper();
    final AtomicInteger permits = new AtomicInteger();
    final Map<String, String> fields = new HashMap<>();
    FakeMcpServer mcp;
    OpenBbConnector obb;

    @BeforeEach
    void start() throws Exception {
        mcp = new FakeMcpServer("Authorization");
        ArrayNode tools = om.createArrayNode();
        tools.add(tool("equity_price_quote", "symbol", "provider"));
        tools.add(tool("equity_price_historical", "symbol", "start_date", "end_date", "provider", "interval"));
        tools.add(tool("news_company", "symbol", "start_date", "end_date", "limit", "provider"));
        tools.add(tool("install_skill", "name", "files"));
        tools.add(tool("run_pipeline", "steps"));
        mcp.tools = tools;
        obb = new OpenBbConnector();
        fields.put("server_url", mcp.url().replace("/mcp", ""));   // base URL only: /mcp is appended
    }

    @AfterEach
    void stop() {
        mcp.close();
    }

    ObjectNode tool(String name, String... params) {
        ObjectNode t = om.createObjectNode().put("name", name).put("description", name);
        ObjectNode p = t.putObject("inputSchema").put("type", "object").putObject("properties");
        for (String s : params) p.putObject(s).put("type", s.equals("limit") ? "integer" : "string");
        return t;
    }

    ConnectorContext ctx() {
        return new ConnectorContext(obb.spec(), k -> Optional.ofNullable(fields.get(k)), HttpClient.newHttpClient(), om, permits::incrementAndGet);
    }

    ConnectorTool named(String name) {
        return obb.tools().stream().filter(t -> t.name().equals(name)).findFirst().orElseThrow();
    }

    @Test
    void toolNamesFollowOpenBbsRouteNaming_andNeverIncludeItsWritingTools() {
        assertThat(obb.tools()).extracting(ConnectorTool::name).contains("openbb_equity_price_quote", "openbb_equity_price_historical",
                "openbb_equity_fundamental_income", "openbb_news_company", "openbb_economy_calendar", "openbb_economy_cpi")
                .noneMatch(n -> n.contains("install_skill") || n.contains("run_pipeline") || n.contains("call_tool"));
    }

    @Test
    void test_countsServedTools_andSendsBasicStyleBearer() {
        fields.put("token", "alice:s3cret");
        assertThat(obb.test(ctx())).contains("offers 3 of 10");
        String expected = "Bearer " + Base64.getEncoder().encodeToString("alice:s3cret".getBytes());
        assertThat(mcp.seen).allSatisfy(s -> assertThat(s.headers()).containsEntry("Authorization", expected));
        assertThat(permits.get()).isEqualTo(3);   // initialize, initialized, tools/list
        assertThat(OpenBbConnector.bearer("YWxpY2U6czNjcmV0")).isEqualTo("YWxpY2U6czNjcmV0");
    }

    @Test
    void noToken_sendsNoAuthorization() {
        obb.test(ctx());
        assertThat(mcp.seen).allSatisfy(s -> assertThat(s.headers()).doesNotContainKey("Authorization"));
    }

    @Test
    void wrongToken_isAuth() {
        mcp.authHeader = "Authorization";
        mcp.authValue = "Bearer right";
        fields.put("token", "wrong");
        assertThatThrownBy(() -> obb.test(ctx()))
                .isInstanceOfSatisfying(ConnectorException.class, e -> assertThat(e.kind()).isEqualTo(ConnectorException.Kind.AUTH));
    }

    @Test
    void serverUrl_mustBeHttp() {
        fields.put("server_url", "file:///etc/passwd");
        assertThatThrownBy(() -> obb.test(ctx()))
                .isInstanceOfSatisfying(ConnectorException.class, e -> assertThat(e.kind()).isEqualTo(ConnectorException.Kind.BAD_REQUEST));
        fields.put("server_url", "http://user:pw@127.0.0.1:8001/mcp");
        assertThatThrownBy(() -> obb.test(ctx())).isInstanceOf(ConnectorException.class);
        assertThat(mcp.seen).isEmpty();
    }

    @Test
    void notInstalledTool_isRefusedNotCalled() {
        assertThatThrownBy(() -> named("openbb_equity_fundamental_income").handler().call(ctx(), om.createObjectNode().put("symbol", "AAPL")))
                .isInstanceOfSatisfying(ConnectorException.class, e -> assertThat(e.kind()).isEqualTo(ConnectorException.Kind.BAD_REQUEST))
                .hasMessageContaining("does not currently offer");
        assertThat(mcp.methods()).doesNotContain("tools/call");
    }

    @Test
    @SuppressWarnings("unchecked")
    void history_keepsTheNewestRows_dropsCharts_liftsTimestamp() throws Exception {
        mcp.sse = false;
        mcp.onCall = (n, a) -> {
            ObjectNode obj = om.createObjectNode();
            ArrayNode rows = obj.putArray("results");
            for (int i = 1; i <= 120; i++) rows.addObject().put("date", String.format("2026-%02d-%02d", 1 + (i - 1) / 28, 1 + (i - 1) % 28)).put("close", i);
            obj.put("provider", "yfinance");
            obj.putObject("chart").put("content", "x".repeat(1000));
            obj.putObject("extra").putObject("metadata").put("timestamp", "2026-11-14T18:00:00.000000");
            return mcp.textResult(obj.toString());
        };
        Map<String, Object> out = (Map<String, Object>) named("openbb_equity_price_historical").handler().call(ctx(),
                om.readTree("{\"symbol\":\"AAPL\",\"start_date\":\"2026-01-01\",\"max_rows\":10,\"provider\":\"yfinance\"}"));
        JsonNode sent = mcp.seen.get(mcp.seen.size() - 1).arguments();
        assertThat(sent.has("max_rows")).isFalse();
        assertThat(sent.get("provider").asText()).isEqualTo("yfinance");

        Map<String, Object> content = (Map<String, Object>) out.get("content");
        List<Map<String, Object>> rows = (List<Map<String, Object>>) content.get("results");
        assertThat(rows).hasSize(10);
        assertThat(rows.get(9).get("close")).isEqualTo(120);
        assertThat(content).doesNotContainKey("chart").containsEntry("results_total", 120);
        assertThat(out).containsEntry("as_of", "2026-11-14T18:00:00.000000");
    }
}
