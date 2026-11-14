package com.stocktracker.agent.actions;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stocktracker.agent.AgentCapabilities;
import com.stocktracker.agent.AgentProfile;
import com.stocktracker.agent.AgentTool;
import com.stocktracker.agent.AgentToolbox;
import com.stocktracker.agent.actions.AgentAuditEntry.Event;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * §9.3 / §9.4: cloud action tools appear only for linked clouds, and billable ones never run
 * on the model's say-so — they wait for a person, and every step is audit-logged on the Pack.
 * The cloud sources here are test doubles; production registers none until §7 exists.
 */
class CloudActionGatingTest {

    final ObjectMapper mapper = new ObjectMapper();
    final List<AgentAuditEntry> audit = new ArrayList<>();
    final AtomicInteger launches = new AtomicInteger();
    MutableClock clock;

    static final class MutableClock extends Clock {
        Instant now = Instant.parse("2026-11-14T12:00:00Z");
        @Override public ZoneOffset getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }

    static final class FakeSource implements CloudActionToolSource {
        final String cloud;
        boolean linked;
        final List<AgentTool> tools;

        FakeSource(String cloud, boolean linked, List<AgentTool> tools) {
            this.cloud = cloud;
            this.linked = linked;
            this.tools = tools;
        }

        @Override public String cloud() { return cloud; }
        @Override public boolean isLinked() { return linked; }
        @Override public List<AgentTool> tools() { return tools; }
    }

    AgentTool tool(String name, String cloud, boolean billable, String estimate) {
        return new AgentTool() {
            @Override public String name() { return name; }
            @Override public String description() { return name; }
            @Override public Map<String, Object> parameters() { return Map.of("type", "object", "properties", Map.of()); }
            @Override public Object call(JsonNode args) { launches.incrementAndGet(); return Map.of("instanceId", "i-123"); }
            @Override public String cloud() { return cloud; }
            @Override public boolean billable() { return billable; }
            @Override public String costEstimate(JsonNode args) { return estimate; }
        };
    }

    FakeSource aws;
    FakeSource gcp;
    CloudToolGate gate;
    ActionConfirmationService actions;
    AgentCapabilities kit;

    @BeforeEach
    void setUp() {
        clock = new MutableClock();
        AgentAuditRepository repo = mock(AgentAuditRepository.class);
        when(repo.save(any())).thenAnswer(inv -> {
            audit.add(inv.getArgument(0));
            return inv.getArgument(0);
        });
        aws = new FakeSource("aws", false, List.of(
                tool("aws_describe_instances", "aws", false, null),
                tool("aws_launch_gpu_instance", "aws", true, "g6.xlarge on-demand ≈ $0.80/hour until stopped"),
                tool("gcp_smuggled", "gcp", false, null)));            // wrong cloud: must be dropped
        gcp = new FakeSource("gcp", false, List.of(tool("gcp_list_vms", "gcp", false, null)));
        gate = new CloudToolGate(List.of(aws, gcp));
        actions = new ActionConfirmationService(repo, gate, mapper, clock);
        kit = new AgentCapabilities(null, gate, actions);
    }

    List<String> toolNames(AgentProfile agent) {
        return kit.toolbox(agent, 7L, List.of()).names();
    }

    @Test
    void noCloudLinked_noCloudToolOffered() {
        assertThat(toolNames(AgentProfile.COPILOT)).isEmpty();
        assertThat(toolNames(AgentProfile.PACK_OPS)).isEmpty();
        assertThat(new CloudToolGate(List.of()).toolsFor(AgentProfile.PACK_OPS)).isEmpty();
    }

    @Test
    void oneCloudLinked_onlyThatCloudsTools() {
        aws.linked = true;
        assertThat(toolNames(AgentProfile.PACK_OPS)).containsExactly("aws_describe_instances", "aws_launch_gpu_instance");
        assertThat(gate.linkedClouds()).containsExactly("aws");

        gcp.linked = true;
        aws.linked = false;
        assertThat(toolNames(AgentProfile.PACK_OPS)).containsExactly("gcp_list_vms");
    }

    @Test
    void agentsWithoutCloudActions_getNone() {
        aws.linked = true;
        AgentProfile readOnly = new AgentProfile("research", "Research", null, false);
        assertThat(toolNames(readOnly)).isEmpty();
    }

    @Test
    void billableCall_waitsForAPerson_thenRunsOnConfirm_andIsAudited() throws Exception {
        aws.linked = true;
        AgentToolbox box = kit.toolbox(AgentProfile.PACK_OPS, 7L, List.of());

        @SuppressWarnings("unchecked")
        Map<String, Object> out = (Map<String, Object>) box.dispatch("aws_launch_gpu_instance", mapper.readTree("{}"));
        assertThat(out).containsEntry("status", "confirmation_required")
                .containsEntry("costEstimate", "g6.xlarge on-demand ≈ $0.80/hour until stopped");
        assertThat(launches).as("nothing created on the model's say-so").hasValue(0);
        assertThat(actions.pending()).singleElement().satisfies(p -> {
            assertThat(p.packId()).isEqualTo(7L);
            assertThat(p.agent()).isEqualTo("pack-ops");
        });

        actions.confirm((String) out.get("actionId"));

        assertThat(launches).hasValue(1);
        assertThat(actions.pending()).isEmpty();
        assertThat(audit).extracting(AgentAuditEntry::getEvent).containsExactly(Event.REQUESTED, Event.CONFIRMED, Event.EXECUTED);
        assertThat(audit).allSatisfy(e -> {
            assertThat(e.getPackId()).isEqualTo(7L);
            assertThat(e.getCloud()).isEqualTo("aws");
            assertThat(e.getActionId()).isEqualTo(out.get("actionId"));
        });
        assertThatThrownBy(() -> actions.confirm((String) out.get("actionId"))).isInstanceOf(NoSuchElementException.class);
        assertThat(launches).as("a confirmation runs once").hasValue(1);
    }

    @Test
    void rejected_unlinkedOrExpired_neverRun() throws Exception {
        aws.linked = true;
        AgentToolbox box = kit.toolbox(AgentProfile.PACK_OPS, 7L, List.of());
        JsonNode none = mapper.readTree("{}");

        String rejected = (String) ((Map<?, ?>) box.dispatch("aws_launch_gpu_instance", none)).get("actionId");
        actions.reject(rejected);

        String unlinked = (String) ((Map<?, ?>) box.dispatch("aws_launch_gpu_instance", none)).get("actionId");
        aws.linked = false;
        assertThatThrownBy(() -> actions.confirm(unlinked)).hasMessageContaining("no longer linked");
        aws.linked = true;

        String expired = (String) ((Map<?, ?>) box.dispatch("aws_launch_gpu_instance", none)).get("actionId");
        clock.now = clock.now.plus(ActionConfirmationService.TTL).plus(Duration.ofSeconds(1));
        assertThatThrownBy(() -> actions.confirm(expired)).isInstanceOf(NoSuchElementException.class);

        assertThat(launches).hasValue(0);
        assertThat(audit).extracting(AgentAuditEntry::getEvent).containsExactly(
                Event.REQUESTED, Event.REJECTED, Event.REQUESTED, Event.FAILED, Event.REQUESTED, Event.EXPIRED);
    }

    @Test
    void billableWithoutCostEstimate_isRefused_andAudited() throws Exception {
        AgentToolbox box = AgentToolbox.builder("pack-ops").guard(actions).packId(3L)
                .add(tool("aws_mystery_spend", "aws", true, null)).build();
        assertThat(box.dispatch("aws_mystery_spend", mapper.readTree("{}"))).asInstanceOf(
                org.assertj.core.api.InstanceOfAssertFactories.MAP).containsEntry("status", "refused");
        assertThat(actions.pending()).isEmpty();
        assertThat(launches).hasValue(0);
        assertThat(audit).singleElement().satisfies(e -> assertThat(e.getEvent()).isEqualTo(Event.REFUSED));
    }

    @Test
    void agentWithoutAGuard_refusesBillableCalls() throws Exception {
        AgentToolbox box = AgentToolbox.builder("x").add(tool("aws_launch", "aws", true, "$1")).build();
        assertThat(box.dispatch("aws_launch", mapper.readTree("{}"))).asInstanceOf(
                org.assertj.core.api.InstanceOfAssertFactories.MAP).containsEntry("status", "refused");
        assertThat(launches).hasValue(0);
    }
}
