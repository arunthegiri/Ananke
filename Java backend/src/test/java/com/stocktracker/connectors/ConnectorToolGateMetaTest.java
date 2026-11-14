package com.stocktracker.connectors;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stocktracker.agent.AgentProfile;
import com.stocktracker.agent.AgentTool;
import org.junit.jupiter.api.Test;

import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Past DIRECT_LIMIT connector tools, agents get data_sources + data_source_call instead (§0.2). */
class ConnectorToolGateMetaTest {

    final ObjectMapper om = new ObjectMapper();

    static Connector keyless(String id, int tools) {
        ConnectorSpec spec = new ConnectorSpec(id, "Source " + id, ConnectorSpec.Category.MACRO, ConnectorSpec.Licence.FREE_PUBLIC,
                "Data from " + id + ".", null, null, List.of(), List.of());
        List<ConnectorTool> list = new ArrayList<>();
        for (int i = 0; i < tools; i++) {
            String name = id + "_t" + i;
            list.add(new ConnectorTool(name, "Tool " + i, ToolArgs.object(ToolArgs.props("x", ToolArgs.str("x")), List.of()),
                    Duration.ZERO, (ctx, a) -> Map.of("tool", name, "x", a.path("x").asText("")), null));
        }
        return new Connector() {
            @Override public ConnectorSpec spec() { return spec; }
            @Override public List<ConnectorTool> tools() { return list; }
            @Override public String test(ConnectorContext ctx) { return "ok"; }
        };
    }

    ConnectorService service(List<Connector> cs) {
        return new ConnectorService(cs, new ConnectorServiceTest.MemoryStore(), RateLimiter.system(),
                new ResponseCache(Clock.systemUTC(), 10), HttpClient.newHttpClient(), om, Clock.systemUTC());
    }

    @Test
    void fewTools_areOfferedDirectly_keylessOnlyOnceSwitchedOn() {
        ConnectorService s = service(List.of(keyless("aa", 3)));
        ConnectorToolGate gate = new ConnectorToolGate(s, om);
        assertThat(gate.toolsFor(AgentProfile.COPILOT)).as("keyless connectors start off").isEmpty();
        s.setEnabled("aa", true);
        assertThat(gate.toolsFor(AgentProfile.COPILOT)).extracting(AgentTool::name).containsExactly("aa_t0", "aa_t1", "aa_t2");
    }

    @Test
    @SuppressWarnings("unchecked")
    void manyTools_collapseToTwoMetaTools_thatOnlyReachUsableConnectors() throws Exception {
        List<Connector> cs = new ArrayList<>();
        for (String id : List.of("aa", "bb", "cc", "dd", "ee", "ff")) cs.add(keyless(id, 5));
        ConnectorService s = service(cs);
        for (String id : List.of("aa", "bb", "cc", "dd", "ee")) s.setEnabled(id, true);   // 25 tools > 24; ff stays off
        ConnectorToolGate gate = new ConnectorToolGate(s, om);

        List<AgentTool> tools = gate.toolsFor(AgentProfile.COPILOT);
        assertThat(tools).extracting(AgentTool::name).containsExactly("data_sources", "data_source_call");
        assertThat(gate.promptSection(AgentProfile.COPILOT)).contains("data_source_call").contains("(aa)");

        Map<String, Object> list = (Map<String, Object>) tools.get(0).call(om.createObjectNode());
        assertThat((List<?>) list.get("sources")).hasSize(5);
        Map<String, Object> one = (Map<String, Object>) tools.get(0).call(om.readTree("{\"source\":\"bb\"}"));
        assertThat(one.toString()).contains("parameters").contains("bb_t4");

        Map<String, Object> out = (Map<String, Object>) tools.get(1).call(om.readTree("{\"tool\":\"cc_t2\",\"arguments\":{\"x\":\"hi\"}}"));
        assertThat(out).containsKeys("source", "observed_at", "notice");
        assertThat(out.get("data").toString()).contains("cc_t2").contains("hi");

        // A switched-off source's tool is unreachable even by name.
        Map<String, Object> off = (Map<String, Object>) tools.get(1).call(om.readTree("{\"tool\":\"ff_t0\"}"));
        assertThat(off).containsEntry("kind", "BAD_REQUEST");
    }
}
