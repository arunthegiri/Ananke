package com.stocktracker.agent.model;

import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.Set;

/**
 * A bounded agent responsibility (Agent Integration spec §6.1). Roles are logical: no role
 * is a long-running process, and two roles bound to the same model are not independent
 * evidence (§6.1, last paragraph).
 *
 * @param requires        capabilities a bound model must have <em>verified</em> before a run
 *                        that uses this role may start (§5.6). TEXT alone means a
 *                        report-only role.
 * @param inheritsDefault whether an unbound role may use the workspace default binding
 *                        (§5.2 step 4). Tool-using roles do not inherit, so a cheap default
 *                        never silently ends up writing strategy code.
 */
public record AgentRole(String id, String label, String purpose, Set<Capability> requires,
                        boolean inheritsDefault) {

    public static final AgentRole PLANNER = new AgentRole("planner", "Planner",
            "Turns a request into a bounded research plan, hypotheses and stop conditions.",
            Set.of(Capability.TEXT, Capability.TOOL_CALLING), false);
    public static final AgentRole RESEARCHER = new AgentRole("researcher", "Researcher",
            "Gathers sources and competing explanations from approved connectors.",
            Set.of(Capability.TEXT, Capability.TOOL_CALLING), false);
    public static final AgentRole VERIFIER = new AgentRole("verifier", "Verifier",
            "Checks whether each claim is supported, contradicted or lacks evidence.",
            Set.of(Capability.TEXT), true);
    public static final AgentRole QUANT_DESIGNER = new AgentRole("quant-designer", "Quant designer",
            "Specifies features, target, splits, baselines and costs; freezes the protocol.",
            Set.of(Capability.TEXT, Capability.TOOL_CALLING), false);
    public static final AgentRole BUILDER = new AgentRole("builder", "Builder",
            "Implements the strategy with the Ananke SDK and runs it in isolated jobs.",
            Set.of(Capability.TEXT, Capability.TOOL_CALLING), false);
    public static final AgentRole CRITIC = new AgentRole("critic", "Critic",
            "Finds methodology and implementation flaws, with severity and evidence.",
            Set.of(Capability.TEXT), true);
    public static final AgentRole REPORTER = new AgentRole("reporter", "Reporter",
            "Writes the cited report from verified evidence, results and limitations.",
            Set.of(Capability.TEXT), true);
    public static final AgentRole MONITOR_ANALYST = new AgentRole("monitor-analyst", "Monitor analyst",
            "Decides whether a detected event matters to a monitor's thesis.",
            Set.of(Capability.TEXT), true);
    public static final AgentRole TRAINING_SPECIALIST = new AgentRole("training-specialist", "Training specialist",
            "Plans bounded supervised training jobs within an approved envelope.",
            Set.of(Capability.TEXT, Capability.TOOL_CALLING), false);

    public static final List<AgentRole> ALL = List.of(PLANNER, RESEARCHER, VERIFIER, QUANT_DESIGNER,
            BUILDER, CRITIC, REPORTER, MONITOR_ANALYST, TRAINING_SPECIALIST);

    /** Binding key for the workspace default (§5.2 step 4). Not a role. */
    public static final String DEFAULT_KEY = "*";

    public static Optional<AgentRole> find(String id) {
        return ALL.stream().filter(r -> r.id().equals(id)).findFirst();
    }

    public static AgentRole byId(String id) {
        return find(id).orElseThrow(() -> new NoSuchElementException("Unknown agent role: " + id));
    }
}
