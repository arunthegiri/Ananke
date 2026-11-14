package com.stocktracker.agent;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * The fixed tool list one agent turn runs with (§9.2.4). Built in code from the agent's
 * own tools plus the shared capabilities ({@code load_skill}, linked-cloud action tools);
 * immutable once built, so nothing the model reads can add a tool mid-conversation.
 */
public final class AgentToolbox {

    private static final Pattern TOOL_NAME = Pattern.compile("^[A-Za-z0-9_-]{1,64}$");

    private final Map<String, AgentTool> tools;
    private final ActionGuard guard;
    private final String agentId;
    private final Long packId;

    private AgentToolbox(Map<String, AgentTool> tools, ActionGuard guard, String agentId, Long packId) {
        this.tools   = Collections.unmodifiableMap(tools);
        this.guard   = guard;
        this.agentId = agentId;
        this.packId  = packId;
    }

    public static Builder builder(String agentId) {
        return new Builder(agentId);
    }

    public List<Map<String, Object>> definitions() {
        List<Map<String, Object>> out = new ArrayList<>(tools.size());
        for (AgentTool t : tools.values()) out.add(t.definition());
        return out;
    }

    public List<String> names() {
        return List.copyOf(tools.keySet());
    }

    public boolean has(String name) {
        return tools.containsKey(name);
    }

    public String statusText(String name, JsonNode args) {
        AgentTool t = tools.get(name);
        return t == null ? "Working…" : t.statusText(args);
    }

    /**
     * Runs one call. Unknown names (including tools a skill told the model about) are an
     * error result, never a lookup elsewhere; billable tools go to the guard instead of running.
     */
    public Object dispatch(String name, JsonNode args) throws Exception {
        AgentTool t = tools.get(name);
        if (t == null) return Map.of("error", "Unknown tool: " + name);
        if (t.billable()) return guard.requestConfirmation(agentId, packId, t, args);
        return t.call(args);
    }

    public static final class Builder {
        private final Map<String, AgentTool> tools = new LinkedHashMap<>();
        private final String agentId;
        private ActionGuard guard = ActionGuard.REFUSE_ALL;
        private Long packId;

        private Builder(String agentId) {
            this.agentId = agentId;
        }

        public Builder add(AgentTool tool) {
            if (!TOOL_NAME.matcher(tool.name()).matches()) {
                throw new IllegalArgumentException("Invalid tool name: " + tool.name());
            }
            if (tools.putIfAbsent(tool.name(), tool) != null) {
                throw new IllegalStateException("Duplicate tool name: " + tool.name());
            }
            return this;
        }

        public Builder addAll(List<AgentTool> list) {
            list.forEach(this::add);
            return this;
        }

        public Builder guard(ActionGuard guard) {
            this.guard = guard;
            return this;
        }

        public Builder packId(Long packId) {
            this.packId = packId;
            return this;
        }

        public AgentToolbox build() {
            return new AgentToolbox(new LinkedHashMap<>(tools), guard, agentId, packId);
        }
    }
}
