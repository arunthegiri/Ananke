package com.stocktracker.agent.model;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;

/** Read side the resolver and client factory need. Every lookup is workspace-scoped. */
public interface AgentModelStore {

    Optional<ModelConnection> connection(String workspaceId, String connectionId);

    Optional<AgentModelBinding> binding(String workspaceId, AgentModelBinding.Scope scope, String scopeId,
                                        String roleId);

    /**
     * Latest probe status per capability for exactly this configuration, ignoring probes
     * older than {@code notBefore}. Capabilities with no qualifying probe are absent.
     */
    Map<Capability, Capability.Status> capabilities(String workspaceId, String connectionId, long connectionVersion,
                                                    String modelId, String deploymentRevision, Instant notBefore);

    void saveProbe(ModelCapabilityProbe probe);
}
