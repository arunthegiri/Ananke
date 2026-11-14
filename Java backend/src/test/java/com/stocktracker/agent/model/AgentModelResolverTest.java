package com.stocktracker.agent.model;

import com.stocktracker.agent.model.AgentModelBinding.Scope;
import com.stocktracker.agent.model.ModelResolutionException.Code;
import com.stocktracker.agent.model.ResolvedModelBinding.Source;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static com.stocktracker.agent.model.TestModels.WS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Precedence, inheritance and gating rules of spec §5.2 / §5.4 / §5.6 — pure, no network. */
class AgentModelResolverTest {

    private TestModels.Store store;
    private TestModels.Deployments pack;
    private AgentModelResolver resolver;

    @BeforeEach
    void setUp() {
        store = new TestModels.Store();
        store.connection("conn-hosted", Adapter.OPENAI_COMPATIBLE, "https://api.example.com/v1", true);
        store.connection("conn-other", Adapter.ANTHROPIC, "https://api.anthropic.example", true);
        store.connection("conn-pack", Adapter.PACK, "pack:7", false);
        pack = new TestModels.Deployments("pack:7", "qwen-served", null);
        resolver = new AgentModelResolver(store, pack, TestModels.CLOCK);
    }

    private ResolvedModelBinding resolve(AgentRole role, String project, BindingSpec override) {
        return resolver.resolve(WS, project, role, override, TrustZone.ANY);
    }

    private static Code codeOf(Runnable r) {
        try {
            r.run();
        } catch (ModelResolutionException e) {
            return e.code();
        }
        throw new AssertionError("expected a ModelResolutionException");
    }

    @Test
    void unboundRoleIsBindingRequiredNeverASilentHostedPick() {
        assertThat(codeOf(() -> resolve(AgentRole.CRITIC, null, null))).isEqualTo(Code.MODEL_BINDING_REQUIRED);
    }

    @Test
    void precedenceIsOverrideThenProjectThenWorkspaceRoleThenDefault() {
        store.bind(Scope.WORKSPACE, "", AgentRole.DEFAULT_KEY, "conn-hosted", "default-model");
        assertThat(resolve(AgentRole.CRITIC, "p1", null).source()).isEqualTo(Source.WORKSPACE_DEFAULT);

        store.bind(Scope.WORKSPACE, "", "critic", "conn-hosted", "ws-critic");
        assertThat(resolve(AgentRole.CRITIC, "p1", null).modelId()).isEqualTo("ws-critic");

        store.bind(Scope.PROJECT, "p1", "critic", "conn-other", "project-critic");
        ResolvedModelBinding project = resolve(AgentRole.CRITIC, "p1", null);
        assertThat(project.source()).isEqualTo(Source.PROJECT);
        assertThat(project.modelId()).isEqualTo("project-critic");
        // Another project does not see p1's binding.
        assertThat(resolve(AgentRole.CRITIC, "p2", null).modelId()).isEqualTo("ws-critic");

        ResolvedModelBinding run = resolve(AgentRole.CRITIC, "p1", BindingSpec.of("conn-hosted", "run-critic"));
        assertThat(run.source()).isEqualTo(Source.RUN_OVERRIDE);
        assertThat(run.modelId()).isEqualTo("run-critic");
    }

    @Test
    void toolRolesDoNotImplicitlyInheritTheWorkspaceDefault() {
        store.bind(Scope.WORKSPACE, "", AgentRole.DEFAULT_KEY, "conn-hosted", "cheap-model");
        assertThat(codeOf(() -> resolve(AgentRole.BUILDER, null, null))).isEqualTo(Code.MODEL_BINDING_REQUIRED);
    }

    @Test
    void explicitInheritDefersToDefaultButStillPassesTheCapabilityGate() {
        store.bind(Scope.WORKSPACE, "", AgentRole.DEFAULT_KEY, "conn-hosted", "cheap-model");
        store.bind(Scope.WORKSPACE, "", "builder", null, null); // inherit
        assertThat(codeOf(() -> resolve(AgentRole.BUILDER, null, null))).isEqualTo(Code.CAPABILITY_NOT_VERIFIED);

        store.verify("conn-hosted", "cheap-model", null, Capability.TEXT, Capability.TOOL_CALLING);
        assertThat(resolve(AgentRole.BUILDER, null, null).source()).isEqualTo(Source.WORKSPACE_DEFAULT);
    }

    @Test
    void textOnlyRoleNeedsNoProbeButToolRoleNeedsAVerifiedOne() {
        store.bind(Scope.WORKSPACE, "", "reporter", "conn-hosted", "m");
        store.bind(Scope.WORKSPACE, "", "builder", "conn-hosted", "m");
        assertThat(resolve(AgentRole.REPORTER, null, null).modelId()).isEqualTo("m");
        assertThat(codeOf(() -> resolve(AgentRole.BUILDER, null, null))).isEqualTo(Code.CAPABILITY_NOT_VERIFIED);
    }

    @Test
    void probesAreInvalidatedByConnectionEditsAndExpiry() {
        store.bind(Scope.WORKSPACE, "", "builder", "conn-hosted", "m");
        store.verify("conn-hosted", "m", null, Capability.TEXT, Capability.TOOL_CALLING);
        assertThat(resolve(AgentRole.BUILDER, null, null).capabilities())
                .containsEntry(Capability.TOOL_CALLING, Capability.Status.VERIFIED);

        store.connections.get("conn-hosted").setVersion(2L);
        assertThat(codeOf(() -> resolve(AgentRole.BUILDER, null, null))).isEqualTo(Code.CAPABILITY_NOT_VERIFIED);

        store.verify("conn-hosted", "m", null, Capability.TOOL_CALLING);
        store.probes.forEach(p -> p.setProbedAt(Instant.parse("2026-09-01T00:00:00Z"))); // > 30 days old
        assertThat(codeOf(() -> resolve(AgentRole.BUILDER, null, null))).isEqualTo(Code.CAPABILITY_NOT_VERIFIED);
    }

    @Test
    void disabledOrMissingConnectionsBlock() {
        store.bind(Scope.WORKSPACE, "", "critic", "conn-hosted", "m");
        store.connections.get("conn-hosted").setEnabled(false);
        assertThat(codeOf(() -> resolve(AgentRole.CRITIC, null, null))).isEqualTo(Code.CONNECTION_DISABLED);
        assertThat(codeOf(() -> resolve(AgentRole.CRITIC, null, BindingSpec.of("conn-gone", "m"))))
                .isEqualTo(Code.CONNECTION_NOT_FOUND);
    }

    @Test
    void dataPolicyRestrictsTrustZones() {
        store.bind(Scope.WORKSPACE, "", "critic", "conn-hosted", "m");
        assertThat(codeOf(() -> resolver.resolve(WS, null, AgentRole.CRITIC, null, TrustZone.PRIVATE_ONLY)))
                .isEqualTo(Code.TRUST_ZONE_NOT_PERMITTED);
    }

    @Test
    void packBindingsPinTheDeploymentRevisionAndFailWhenStopped() {
        store.bind(Scope.WORKSPACE, "", "critic", "conn-pack", null);
        ResolvedModelBinding b = resolve(AgentRole.CRITIC, null, null);
        assertThat(b.trustZone()).isEqualTo(TrustZone.PRIVATE_COMPUTE);
        assertThat(b.modelId()).isEqualTo("qwen-served");
        assertThat(b.deploymentRevision()).isEqualTo("rev-a/000000000000");

        pack.running = false;
        assertThat(codeOf(() -> resolve(AgentRole.CRITIC, null, null))).isEqualTo(Code.DEPLOYMENT_UNAVAILABLE);
    }

    @Test
    void packProbesDoNotSurviveARedeploy() {
        store.bind(Scope.WORKSPACE, "", "builder", "conn-pack", null);
        store.verify("conn-pack", "qwen-served", pack.revision, Capability.TEXT, Capability.TOOL_CALLING);
        assertThat(resolve(AgentRole.BUILDER, null, null).deploymentId()).isEqualTo("pack:7");

        pack.revision = "rev-a/ffffffffffff"; // new tool parser / launch config
        assertThat(codeOf(() -> resolve(AgentRole.BUILDER, null, null))).isEqualTo(Code.CAPABILITY_NOT_VERIFIED);
    }

    @Test
    void fallbacksAreValidatedUpFrontAgainstTheSameDataPolicy() {
        BindingSpec spec = BindingSpec.of("conn-pack", null)
                .withFallback(FallbackPolicy.explicit(new FallbackPolicy.Target("conn-hosted", "hosted-m")));
        // Private-only research: a hosted fallback is rejected before the run starts.
        assertThatThrownBy(() -> resolver.resolve(WS, null, AgentRole.CRITIC, spec, TrustZone.PRIVATE_ONLY))
                .isInstanceOf(ModelResolutionException.class)
                .extracting(e -> ((ModelResolutionException) e).code())
                .isEqualTo(Code.TRUST_ZONE_NOT_PERMITTED);

        ResolvedModelBinding ok = resolver.resolve(WS, null, AgentRole.CRITIC, spec, TrustZone.ANY);
        assertThat(ok.fallbacks()).singleElement().satisfies(f -> {
            assertThat(f.source()).isEqualTo(Source.FALLBACK);
            assertThat(f.modelId()).isEqualTo("hosted-m");
            assertThat(f.fallbacks()).isEmpty();
        });
    }

    @Test
    void fallbackToItselfIsInvalid() {
        BindingSpec spec = BindingSpec.of("conn-hosted", "m")
                .withFallback(FallbackPolicy.explicit(new FallbackPolicy.Target("conn-hosted", "m")));
        assertThat(codeOf(() -> resolve(AgentRole.CRITIC, null, spec))).isEqualTo(Code.INVALID_BINDING);
    }

    @Test
    void resolveAllReportsEveryBlockedRoleAtOnce() {
        store.bind(Scope.WORKSPACE, "", AgentRole.DEFAULT_KEY, "conn-hosted", "m");
        AgentModelResolver.Resolution r = resolver.resolveAll(WS, null,
                List.of(AgentRole.PLANNER, AgentRole.BUILDER, AgentRole.REPORTER), Map.of(), TrustZone.ANY);
        assertThat(r.bindings()).containsOnlyKeys("reporter");
        assertThat(r.errors()).containsOnlyKeys("planner", "builder");
        assertThat(r.errors().get("builder").code()).isEqualTo(Code.MODEL_BINDING_REQUIRED);
    }

    @Test
    void workspacesDoNotSeeEachOthersBindings() {
        store.bind(Scope.WORKSPACE, "", "critic", "conn-hosted", "m");
        assertThat(codeOf(() -> resolver.resolve("other-ws", null, AgentRole.CRITIC, null, TrustZone.ANY)))
                .isEqualTo(Code.MODEL_BINDING_REQUIRED);
        assertThat(codeOf(() -> resolver.resolve("other-ws", null, AgentRole.CRITIC,
                BindingSpec.of("conn-hosted", "m"), TrustZone.ANY))).isEqualTo(Code.CONNECTION_NOT_FOUND);
    }
}
