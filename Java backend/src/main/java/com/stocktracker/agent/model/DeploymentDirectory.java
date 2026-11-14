package com.stocktracker.agent.model;

import com.stocktracker.service.LLMClient;

import java.util.List;
import java.util.Optional;

/**
 * The narrow read-only seam onto model deployments (§5.5): today, Ananke Packs. The runtime
 * sees a descriptor — state, revision, served model name — and a client for a running
 * deployment, never Pack internals. The Packs workstream owns the implementation.
 */
public interface DeploymentDirectory {

    /**
     * @param state     the deployment's lifecycle state as the owner reports it.
     * @param available true only when it is running and can take calls right now.
     * @param revision  everything that invalidates a capability probe (§5.6): model revision
     *                  plus a hash of the launch config (runtime, tool parser, context).
     */
    record Deployment(String deploymentId, String label, String state, boolean available,
                      String revision, String servedModel) { }

    Optional<Deployment> describe(String deploymentId);

    /** A client for a running deployment; empty when it is not running. */
    Optional<LLMClient> clientFor(String deploymentId);

    List<Deployment> list();

    DeploymentDirectory NONE = new DeploymentDirectory() {
        @Override public Optional<Deployment> describe(String id) { return Optional.empty(); }
        @Override public Optional<LLMClient> clientFor(String id) { return Optional.empty(); }
        @Override public List<Deployment> list() { return List.of(); }
    };
}
