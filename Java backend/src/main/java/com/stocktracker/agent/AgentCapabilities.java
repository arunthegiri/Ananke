package com.stocktracker.agent;

import com.stocktracker.agent.actions.CloudToolGate;
import com.stocktracker.agent.skills.SkillToolset;

import java.util.List;

/**
 * The shared capabilities every Ananke agent composes (§9.2.4) instead of re-implementing:
 * the skill index for its system prompt, {@code load_skill}, the cloud action tools of
 * linked clouds, and the billable-action guard. An agent supplies its own tools and its
 * {@link AgentProfile}; the result is a fixed {@link AgentToolbox} for one turn.
 */
public class AgentCapabilities {

    private final SkillToolset skills;
    private final CloudToolGate cloudTools;
    private final ActionGuard guard;

    public AgentCapabilities(SkillToolset skills, CloudToolGate cloudTools, ActionGuard guard) {
        this.skills     = skills;
        this.cloudTools = cloudTools;
        this.guard      = guard;
    }

    /** No skills, no cloud tools, billable calls refused — for agents built without the shared kit. */
    public static AgentCapabilities none() {
        return new AgentCapabilities(null, new CloudToolGate(List.of()), ActionGuard.REFUSE_ALL);
    }

    /** {@code base} plus this agent's skill index (unchanged when it sees no skills). */
    public String systemPrompt(String base, AgentProfile agent) {
        if (skills == null) return base;
        String section = skills.systemPromptSection(agent);
        return section.isEmpty() ? base : base.stripTrailing() + "\n\n" + section;
    }

    public AgentToolbox toolbox(AgentProfile agent, Long packId, List<AgentTool> own) {
        AgentToolbox.Builder b = AgentToolbox.builder(agent.id()).guard(guard).packId(packId).addAll(own);
        if (skills != null) skills.loadSkillTool(agent).ifPresent(b::add);
        b.addAll(cloudTools.toolsFor(agent));
        return b.build();
    }

    public SkillToolset skills() {
        return skills;
    }

    public CloudToolGate cloudTools() {
        return cloudTools;
    }
}
