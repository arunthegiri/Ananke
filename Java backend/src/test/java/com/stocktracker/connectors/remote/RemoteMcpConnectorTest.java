package com.stocktracker.connectors.remote;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.stocktracker.connectors.ConnectorContext;
import com.stocktracker.connectors.ConnectorException;
import com.stocktracker.connectors.ConnectorException.Kind;
import com.stocktracker.connectors.ConnectorSpec;
import com.stocktracker.connectors.ConnectorTool;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static com.stocktracker.connectors.ToolArgs.number;
import static com.stocktracker.connectors.ToolArgs.object;
import static com.stocktracker.connectors.ToolArgs.props;
import static com.stocktracker.connectors.ToolArgs.str;
import static com.stocktracker.connectors.ToolArgs.strArray;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The remote-MCP base against a fake MCP server: allowlist, permits, auth, sessions, errors, rendering. */
class RemoteMcpConnectorTest {

    static class Demo extends RemoteMcpConnector {
        final URI uri;

        Demo(String url) {
            super(Clock.systemUTC());
            uri = URI.create(url);
        }

        @Override
        public ConnectorSpec spec() {
            return new ConnectorSpec("demo_mcp", "Demo MCP", ConnectorSpec.Category.ALT_DATA, ConnectorSpec.Licence.BYO_PERSONAL,
                    "demo", "Source: demo", "https://example.invalid", List.of(ConnectorSpec.Field.secret("api_key", "Key", "")), List.of());
        }

        @Override
        protected List<UpstreamTool> upstreamTools() {
            return List.of(
                    new UpstreamTool("get_quotes", "Quotes", object(props(
                            "symbols", strArray("symbols"), "limit", number("limit")), List.of()),
                            Duration.ofMinutes(5), Map.of("limit", 3), 5),
                    new UpstreamTool("get-Risk.Score", "Risk", object(props("country", str("country")), List.of("country")),
                            Duration.ofHours(1), Map.of(), 0),
                    new UpstreamTool("write_thing", "allowlisted by mistake", object(props(), List.of()), Duration.ZERO, Map.of(), 0),
                    new UpstreamTool("delete_all", "allowlisted by mistake", object(props(), List.of()), Duration.ZERO, Map.of(), 0));
        }

        @Override
        protected URI endpoint(ConnectorContext ctx) {
            return uri;
        }

        @Override
        protected Map<String, String> authHeaders(ConnectorContext ctx) {
            return Map.of("X-Key", ctx.require("api_key"));
        }

        @Override
        public String test(ConnectorContext ctx) {
            return "Connected — " + offeredCount(ctx) + " tools";
        }
    }

    final ObjectMapper om = new ObjectMapper();
    final AtomicInteger permits = new AtomicInteger();
    FakeMcpServer mcp;
    Demo demo;
    volatile String key = "k1";

    @BeforeEach
    void start() throws Exception {
        mcp = new FakeMcpServer("X-Key");
        mcp.tools = toolsList();
        demo = new Demo(mcp.url());
    }

    @AfterEach
    void stop() {
        mcp.close();
    }

    ArrayNode toolsList() {
        ArrayNode a = om.createArrayNode();
        a.add(tool("get_quotes", true, false, "symbols", "array", "limit", "number", "refresh", "boolean"));
        a.add(tool("get-Risk.Score", null, null, "country", "string"));
        a.add(tool("write_thing", false, false));
        a.add(tool("delete_all", true, true));
        a.add(tool("start_upgrade", false, false));
        return a;
    }

    ObjectNode tool(String name, Boolean readOnly, Boolean destructive, String... propTypes) {
        ObjectNode t = om.createObjectNode().put("name", name).put("description", name);
        ObjectNode schema = t.putObject("inputSchema").put("type", "object");
        ObjectNode p = schema.putObject("properties");
        for (int i = 0; i < propTypes.length; i += 2) p.putObject(propTypes[i]).put("type", propTypes[i + 1]);
        if (readOnly != null) {
            ObjectNode ann = t.putObject("annotations").put("readOnlyHint", readOnly);
            if (destructive != null) ann.put("destructiveHint", destructive);
        }
        return t;
    }

    ConnectorContext ctx() {
        return new ConnectorContext(demo.spec(), k -> k.equals("api_key") ? Optional.ofNullable(key) : Optional.empty(),
                HttpClient.newHttpClient(), om, permits::incrementAndGet);
    }

    ConnectorTool named(String name) {
        return demo.tools().stream().filter(t -> t.name().equals(name)).findFirst().orElseThrow();
    }

    @Test
    void toolNames_arePrefixedAndSanitised_andDeclaredStatically() {
        assertThat(demo.tools()).extracting(ConnectorTool::name)
                .containsExactly("demo_mcp_get_quotes", "demo_mcp_get_risk_score", "demo_mcp_write_thing", "demo_mcp_delete_all");
        assertThat(RemoteMcpConnector.toolName("x", "a".repeat(100))).hasSize(64);
        assertThat(mcp.seen).isEmpty();   // tools() never touches the network
    }

    @Test
    void permitPerHttpRequest_listCachedAndSessionReused() throws Exception {
        named("demo_mcp_get_quotes").handler().call(ctx(), om.readTree("{\"symbols\":[\"BTC\"]}"));
        assertThat(mcp.methods()).containsExactly("initialize", "notifications/initialized", "tools/list", "tools/call");
        assertThat(permits.get()).isEqualTo(4);
        assertThat(mcp.seen).allSatisfy(s -> assertThat(s.headers()).containsEntry("X-Key", "k1"));

        named("demo_mcp_get_risk_score").handler().call(ctx(), om.readTree("{\"country\":\"FR\"}"));
        assertThat(mcp.methods()).hasSize(5).endsWith("tools/call");
        assertThat(permits.get()).isEqualTo(5);
        assertThat(mcp.seen.get(4).session()).isEqualTo("s1");
    }

    @Test
    void onlyAllowlistedReadOnlyTools_canBeCalled() throws Exception {
        assertThat(demo.test(ctx())).isEqualTo("Connected — 2 tools");   // write_thing (readOnly=false) and delete_all (destructive) dropped

        for (String t : List.of("demo_mcp_write_thing", "demo_mcp_delete_all")) {
            assertThatThrownBy(() -> named(t).handler().call(ctx(), om.createObjectNode()))
                    .isInstanceOfSatisfying(ConnectorException.class, e -> assertThat(e.kind()).isEqualTo(Kind.BAD_REQUEST))
                    .hasMessageContaining("does not currently offer");
        }
        assertThatThrownBy(() -> demo.callUpstream(ctx(), "start_upgrade", om.createObjectNode()))
                .isInstanceOf(ConnectorException.class).hasMessageContaining("not allowed");
        assertThat(mcp.methods()).doesNotContain("tools/call");
    }

    @Test
    void onlyDeclaredArgumentsAreForwarded_limitClampedAndDefaulted_requiredChecked() throws Exception {
        named("demo_mcp_get_quotes").handler().call(ctx(), om.readTree("{\"symbols\":[\"BTC\"],\"limit\":500,\"refresh\":true,\"panel_request\":\"x\"}"));
        JsonNode sent = mcp.seen.get(3).arguments();
        assertThat(sent.has("refresh")).isFalse();
        assertThat(sent.has("panel_request")).isFalse();
        assertThat(sent.get("limit").asInt()).isEqualTo(5);

        named("demo_mcp_get_quotes").handler().call(ctx(), om.readTree("{\"limit\":0}"));
        assertThat(mcp.seen.get(4).arguments().get("limit").asInt()).isEqualTo(1);   // 0 = "no cap" upstream: never sent
        named("demo_mcp_get_quotes").handler().call(ctx(), om.createObjectNode());
        assertThat(mcp.seen.get(5).arguments().get("limit").asInt()).isEqualTo(3);

        int before = mcp.seen.size();
        assertThatThrownBy(() -> named("demo_mcp_get_risk_score").handler().call(ctx(), om.createObjectNode()))
                .isInstanceOfSatisfying(ConnectorException.class, e -> assertThat(e.kind()).isEqualTo(Kind.BAD_REQUEST));
        assertThat(mcp.seen).hasSize(before);
    }

    @Test
    void credentialChange_resetsTheSession() throws Exception {
        named("demo_mcp_get_quotes").handler().call(ctx(), om.createObjectNode());
        key = "k2";
        named("demo_mcp_get_quotes").handler().call(ctx(), om.createObjectNode());
        assertThat(mcp.methods()).containsExactly("initialize", "notifications/initialized", "tools/list", "tools/call",
                "initialize", "notifications/initialized", "tools/list", "tools/call");
        assertThat(mcp.seen.get(7).session()).isEqualTo("s2");
        assertThat(mcp.seen.get(7).headers()).containsEntry("X-Key", "k2");
    }

    @Test
    void expiredSession_isReinitialisedOnce_andCounted() throws Exception {
        named("demo_mcp_get_quotes").handler().call(ctx(), om.createObjectNode());
        mcp.expireNextCall.set(1);
        named("demo_mcp_get_quotes").handler().call(ctx(), om.createObjectNode());
        assertThat(mcp.methods()).endsWith("tools/call", "initialize", "notifications/initialized", "tools/call");
        assertThat(permits.get()).isEqualTo(mcp.seen.size());
    }

    @Test
    void errorsMapToKinds() throws Exception {
        mcp.authHeader = "X-Key";
        mcp.authValue = "right";
        assertThatThrownBy(() -> demo.test(ctx()))
                .isInstanceOfSatisfying(ConnectorException.class, e -> assertThat(e.kind()).isEqualTo(Kind.AUTH))
                .hasMessageContaining("HTTP 401").hasMessageNotContaining("k1");

        key = "right";
        mcp.onCall = (n, a) -> om.createObjectNode().put("__status", 429).set("body", om.createObjectNode().put("error", "slow down"));
        assertThatThrownBy(() -> named("demo_mcp_get_quotes").handler().call(ctx(), om.createObjectNode()))
                .isInstanceOfSatisfying(ConnectorException.class, e -> assertThat(e.kind()).isEqualTo(Kind.RATE_LIMITED));

        mcp.onCall = (n, a) -> om.createObjectNode().put("__rpcError", "Invalid params: country unknown");
        assertThatThrownBy(() -> named("demo_mcp_get_quotes").handler().call(ctx(), om.createObjectNode()))
                .isInstanceOfSatisfying(ConnectorException.class, e -> assertThat(e.kind()).isEqualTo(Kind.BAD_REQUEST));

        mcp.onCall = (n, a) -> mcp.textResult("Daily allowance exhausted").put("isError", true);
        assertThatThrownBy(() -> named("demo_mcp_get_quotes").handler().call(ctx(), om.createObjectNode()))
                .isInstanceOfSatisfying(ConnectorException.class, e -> assertThat(e.kind()).isEqualTo(Kind.RATE_LIMITED));

        mcp.onCall = (n, a) -> mcp.textResult("upstream feed broke").put("isError", true);
        assertThatThrownBy(() -> named("demo_mcp_get_quotes").handler().call(ctx(), om.createObjectNode()))
                .isInstanceOfSatisfying(ConnectorException.class, e -> assertThat(e.kind()).isEqualTo(Kind.UPSTREAM));
    }

    @Test
    @SuppressWarnings("unchecked")
    void render_liftsAsOfAndStale_parsesJsonText_truncatesHugePayloads() throws Exception {
        mcp.onCall = (n, a) -> mcp.structuredResult(om.createObjectNode().put("cached_at", "2026-11-14T18:00:00Z").put("stale", false)
                .set("data", om.createObjectNode().put("BTC", 91000.5)));
        Map<String, Object> out = (Map<String, Object>) named("demo_mcp_get_quotes").handler().call(ctx(), om.createObjectNode());
        assertThat(out).containsEntry("tool", "get_quotes").containsEntry("as_of", "2026-11-14T18:00:00Z")
                .containsEntry("stale", false).containsEntry("truncated", false);
        assertThat((Map<String, Object>) out.get("content")).containsKey("data");

        mcp.sse = false;
        mcp.onCall = (n, a) -> mcp.textResult("{\"results\":[1,2,3],\"provider\":\"fmp\"}");
        out = (Map<String, Object>) named("demo_mcp_get_quotes").handler().call(ctx(), om.createObjectNode());
        assertThat((Map<String, Object>) out.get("content")).containsEntry("provider", "fmp");

        mcp.onCall = (n, a) -> mcp.textResult("x".repeat(RemoteMcpConnector.MAX_CHARS + 10));
        out = (Map<String, Object>) named("demo_mcp_get_quotes").handler().call(ctx(), om.createObjectNode());
        assertThat(out).containsEntry("truncated", true);
        assertThat((String) out.get("content")).hasSize(RemoteMcpConnector.MAX_CHARS);
    }

    @Test
    void verifyAgainst_reportsDrift() {
        RemoteMcpConnector.UpstreamTool quotes = demo.declared("get_quotes");
        assertThat(RemoteMcpConnector.verifyAgainst(quotes, tool("get_quotes", true, false, "symbols", "array", "limit", "number"))).isEmpty();
        assertThat(RemoteMcpConnector.verifyAgainst(quotes, tool("get_quotes", false, false, "symbols", "string")))
                .contains("not read-only upstream", "unknown property limit", "symbols type array vs \"string\"");
        assertThat(Set.copyOf(RemoteMcpConnector.verifyAgainst(demo.declared("get-Risk.Score"),
                (JsonNode) om.valueToTree(Map.of("name", "x", "inputSchema", Map.of("properties", Map.of("country", Map.of("type", "string")),
                        "required", List.of("country", "extra"))))))).containsExactly("upstream requires extra");
    }
}
