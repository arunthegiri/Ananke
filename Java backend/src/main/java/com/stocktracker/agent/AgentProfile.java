package com.stocktracker.agent;

import java.util.Set;

/**
 * Who an agent is, for the purposes of the shared capabilities (§9.2.2): which skill
 * topics are relevant to it, and whether it may be offered cloud action tools at all.
 *
 * @param topics skill topics this agent sees; {@code null} = every enabled skill.
 */
public record AgentProfile(String id, String label, Set<String> topics, boolean cloudActions) {

    /** Topics a skill may declare (scripts/skills/sources.json). */
    public static final Set<String> KNOWN_TOPICS =
            Set.of("compute", "gpu", "cost", "identity", "deployment", "cli", "serverless");

    /** The copilot's chat mode: every enabled skill. Research mode composes no skills. */
    public static final AgentProfile COPILOT =
            new AgentProfile("copilot", "Copilot (chat)", null, true);

    /** The Pack operations agent: compute, GPU capacity, cost, identity and CLI guidance. */
    public static final AgentProfile PACK_OPS =
            new AgentProfile("pack-ops", "Pack agent", Set.of("compute", "gpu", "cost", "identity", "cli"), true);

    public static final java.util.List<AgentProfile> ALL = java.util.List.of(COPILOT, PACK_OPS);

    public boolean wantsTopics(java.util.Collection<String> skillTopics) {
        return topics == null || skillTopics.stream().anyMatch(topics::contains);
    }

    public static AgentProfile byId(String id) {
        return ALL.stream().filter(p -> p.id().equals(id)).findFirst()
                .orElseThrow(() -> new java.util.NoSuchElementException("Unknown agent: " + id));
    }
}
