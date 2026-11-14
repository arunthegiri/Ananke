package com.stocktracker.agent.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stocktracker.service.LLMClient;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** In-memory doubles for the model-routing seams; no database, no network beyond FakeLlmServer. */
final class TestModels {

    static final String WS = Workspaces.LOCAL;
    static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-14T12:00:00Z"), ZoneOffset.UTC);
    static final ObjectMapper MAPPER = new ObjectMapper();

    private TestModels() { }

    static final class Store implements AgentModelStore, ModelCallRecorder {
        final Map<String, ModelConnection> connections = new ConcurrentHashMap<>();
        final Map<String, AgentModelBinding> bindings = new ConcurrentHashMap<>();
        final List<ModelCapabilityProbe> probes = new ArrayList<>();
        final List<ModelCallRecord> calls = new ArrayList<>();

        ModelConnection connection(String id, Adapter adapter, String baseUrl, boolean keyRequired) {
            ModelConnection c = new ModelConnection();
            c.setId(id);
            c.setWorkspaceId(WS);
            c.setLabel(id);
            c.setAdapter(adapter);
            c.setBaseUrl(adapter == Adapter.PACK ? null : baseUrl);
            c.setDeploymentId(adapter == Adapter.PACK ? baseUrl : null);
            c.setTrustZone(TrustZone.derive(adapter, adapter == Adapter.PACK ? null : baseUrl));
            c.setKeyRequired(keyRequired);
            c.setVersion(1L);
            connections.put(id, c);
            return c;
        }

        AgentModelBinding bind(AgentModelBinding.Scope scope, String scopeId, String roleId, String conn, String model) {
            AgentModelBinding b = new AgentModelBinding();
            b.setWorkspaceId(WS);
            b.setScopeType(scope);
            b.setScopeId(scopeId == null ? "" : scopeId);
            b.setRoleId(roleId);
            b.setConnectionId(conn);
            b.setModelId(model);
            b.setInheritDefault(conn == null);
            b.setVersion(1);
            bindings.put(key(scope, b.getScopeId(), roleId), b);
            return b;
        }

        void verify(String conn, String model, String revision, Capability... caps) {
            for (Capability c : caps) {
                ModelCapabilityProbe p = new ModelCapabilityProbe();
                p.setWorkspaceId(WS);
                p.setConnectionId(conn);
                p.setConnectionVersion(connections.get(conn).getVersion());
                p.setModelId(model);
                p.setDeploymentRevision(revision == null ? "" : revision);
                p.setCapability(c);
                p.setStatus(Capability.Status.VERIFIED);
                p.setProbeVersion("test");
                p.setProbedAt(CLOCK.instant());
                saveProbe(p);
            }
        }

        private static String key(AgentModelBinding.Scope s, String scopeId, String role) {
            return s + "|" + scopeId + "|" + role;
        }

        @Override
        public Optional<ModelConnection> connection(String workspaceId, String connectionId) {
            return Optional.ofNullable(connections.get(connectionId)).filter(c -> c.getWorkspaceId().equals(workspaceId));
        }

        @Override
        public Optional<AgentModelBinding> binding(String workspaceId, AgentModelBinding.Scope scope, String scopeId,
                                                   String roleId) {
            return Optional.ofNullable(bindings.get(key(scope, scopeId == null ? "" : scopeId, roleId)))
                    .filter(b -> b.getWorkspaceId().equals(workspaceId));
        }

        @Override
        public synchronized Map<Capability, Capability.Status> capabilities(String ws, String conn, long version,
                                                                            String model, String rev, Instant notBefore) {
            Map<Capability, Capability.Status> out = new EnumMap<>(Capability.class);
            for (int i = probes.size() - 1; i >= 0; i--) {
                ModelCapabilityProbe p = probes.get(i);
                if (p.getWorkspaceId().equals(ws) && p.getConnectionId().equals(conn)
                        && p.getConnectionVersion() == version && p.getModelId().equals(model)
                        && p.getDeploymentRevision().equals(rev == null ? "" : rev)
                        && p.getProbedAt().isAfter(notBefore)) {
                    out.putIfAbsent(p.getCapability(), p.getStatus());
                }
            }
            return out;
        }

        @Override
        public synchronized void saveProbe(ModelCapabilityProbe probe) {
            probes.add(probe);
        }

        @Override
        public synchronized void record(ModelCallRecord call) {
            calls.add(call);
        }

        synchronized List<ModelCallRecord> calls() {
            return List.copyOf(calls);
        }
    }

    static final class Credentials implements CredentialStore {
        final Map<String, String> secrets = new ConcurrentHashMap<>();

        @Override public Optional<String> get(String scope, String kind) { return Optional.ofNullable(secrets.get(scope + "|" + kind)); }
        @Override public void put(String scope, String kind, String v) { secrets.put(scope + "|" + kind, v); }
        @Override public void delete(String scope, String kind) { secrets.remove(scope + "|" + kind); }
        @Override public Optional<String> hint(String scope, String kind) { return get(scope, kind).map(s -> "…" + s.substring(s.length() - 4)); }
    }

    /** A Pack directory whose single deployment can be started, stopped or redeployed by the test. */
    static final class Deployments implements DeploymentDirectory {
        volatile boolean running = true;
        volatile String revision = "rev-a/000000000000";
        final String id;
        final String served;
        final LLMClient client;

        Deployments(String id, String served, LLMClient client) {
            this.id = id;
            this.served = served;
            this.client = client;
        }

        @Override
        public Optional<Deployment> describe(String deploymentId) {
            if (!id.equals(deploymentId)) return Optional.empty();
            return Optional.of(new Deployment(id, "Pack: test", running ? "RUNNING" : "STOPPED", running, revision, served));
        }

        @Override
        public Optional<LLMClient> clientFor(String deploymentId) {
            return running && id.equals(deploymentId) ? Optional.of(client) : Optional.empty();
        }

        @Override
        public List<Deployment> list() {
            return describe(id).stream().toList();
        }
    }

    static String okReply(String content) {
        return "{\"id\":\"req-1\",\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"" + content
                + "\"},\"finish_reason\":\"stop\"}],\"usage\":{\"prompt_tokens\":11,\"completion_tokens\":3}}";
    }
}
