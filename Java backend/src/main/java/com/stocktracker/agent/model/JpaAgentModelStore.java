package com.stocktracker.agent.model;

import java.time.Instant;
import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;

/** {@link AgentModelStore} over the V10 tables. */
public class JpaAgentModelStore implements AgentModelStore, ModelCallRecorder {

    private final ModelConnectionRepository connections;
    private final AgentModelBindingRepository bindings;
    private final ModelCapabilityProbeRepository probes;
    private final ModelCallRecordRepository calls;

    public JpaAgentModelStore(ModelConnectionRepository connections, AgentModelBindingRepository bindings,
                              ModelCapabilityProbeRepository probes, ModelCallRecordRepository calls) {
        this.connections = connections;
        this.bindings = bindings;
        this.probes = probes;
        this.calls = calls;
    }

    @Override
    public Optional<ModelConnection> connection(String workspaceId, String connectionId) {
        return connections.findByIdAndWorkspaceId(connectionId, workspaceId);
    }

    @Override
    public Optional<AgentModelBinding> binding(String workspaceId, AgentModelBinding.Scope scope, String scopeId,
                                               String roleId) {
        return bindings.findByWorkspaceIdAndScopeTypeAndScopeIdAndRoleId(workspaceId, scope,
                scopeId == null ? "" : scopeId, roleId);
    }

    @Override
    public Map<Capability, Capability.Status> capabilities(String workspaceId, String connectionId,
                                                           long connectionVersion, String modelId,
                                                           String deploymentRevision, Instant notBefore) {
        Map<Capability, Capability.Status> out = new EnumMap<>(Capability.class);
        // Newest first: the first row seen per capability is the current answer.
        for (ModelCapabilityProbe p : probes
                .findByWorkspaceIdAndConnectionIdAndConnectionVersionAndModelIdAndDeploymentRevisionAndProbedAtAfterOrderByProbedAtDesc(
                        workspaceId, connectionId, connectionVersion, modelId,
                        deploymentRevision == null ? "" : deploymentRevision, notBefore)) {
            out.putIfAbsent(p.getCapability(), p.getStatus());
        }
        return out;
    }

    @Override
    public void saveProbe(ModelCapabilityProbe probe) {
        probes.save(probe);
    }

    @Override
    public void record(ModelCallRecord call) {
        calls.save(call);
    }
}
