package com.stocktracker.agent;

import com.stocktracker.agent.actions.CloudToolGate;
import com.stocktracker.agent.skills.SkillToolset;
import com.stocktracker.connectors.ConnectorToolGate;

import java.util.List;

/**
 * The shared capabilities every Ananke agent composes (§9.2.4) instead of re-implementing:
 * the skill index for its system prompt, {@code load_skill}, the cloud action tools of
 * linked clouds, the tools of linked data connectors, and the billable-action guard. An agent supplies its own tools and its
 * {@link AgentProfile}; the result is a fixed {@link AgentToolbox} for one turn.
 */
public class AgentCapabilities {

    private final SkillToolset skills;
    private final CloudToolGate cloudTools;
    private final ActionGuard guard;
    private final ConnectorToolGate connectors;

    public AgentCapabilities(SkillToolset skills, CloudToolGate cloudTools, ActionGuard guard) {
        this(skills, cloudTools, guard, null);
    }

    public AgentCapabilities(SkillToolset skills, CloudToolGate cloudTools, ActionGuard guard,
                             ConnectorToolGate connectors) {
        this.skills     = skills;
        this.cloudTools = cloudTools;
        this.guard      = guard;
        this.connectors = connectors;
    }

    /** No skills, no cloud tools, billable calls refused — for agents built without the shared kit. */
    public static AgentCapabilities none() {
        return new AgentCapabilities(null, new CloudToolGate(List.of()), ActionGuard.REFUSE_ALL);
    }

    /** {@code base} plus this agent's skill index and connected data sources (unchanged when it has neither). */
    public String systemPrompt(String base, AgentProfile agent) {
        String out = base;
        if (skills != null) {
            String section = skills.systemPromptSection(agent);
            if (!section.isEmpty()) out = out.stripTrailing() + "\n\n" + section;
        }
        if (connectors != null) {
            String section = connectors.promptSection(agent);
            if (!section.isEmpty()) out = out.stripTrailing() + "\n\n" + section;
        }
        return out;
    }

    public AgentToolbox toolbox(AgentProfile agent, Long packId, List<AgentTool> own) {
        AgentToolbox.Builder b = AgentToolbox.builder(agent.id()).guard(guard).packId(packId).addAll(own);
        if (skills != null) skills.loadSkillTool(agent).ifPresent(b::add);
        b.addAll(cloudTools.toolsFor(agent));
        if (connectors != null) b.addAll(connectors.toolsFor(agent));
        return b.build();
    }

    public SkillToolset skills() {
        return skills;
    }

    public CloudToolGate cloudTools() {
        return cloudTools;
    }
}
