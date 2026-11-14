package com.stocktracker.agent.model;

/**
 * Rollout flags from spec §22, all off by default. Flags gate <em>entry points</em> — new
 * submissions and settings screens — never authorization, and turning one off must leave
 * existing work inspectable. Only {@code perAgentModels} has an implementation so far; the
 * others are declared so the rollout plan has one place to live.
 */
public record AgentRuntimeFlags(boolean agentRuntimeV2, boolean perAgentModels, boolean isolatedExecution,
                                boolean researchMonitoring, boolean controlLab, boolean paperDeploymentV1) {

    public static final AgentRuntimeFlags OFF = new AgentRuntimeFlags(false, false, false, false, false, false);
}
