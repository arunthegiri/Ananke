package com.stocktracker.agent;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Intercepts billable tool calls (§9.3). Implementations park the call for a person to
 * confirm and return what the model should see instead of a result.
 */
@FunctionalInterface
public interface ActionGuard {

    Object requestConfirmation(String agentId, Long packId, AgentTool tool, JsonNode args);

    /** Used when an agent has no confirmation channel: billable calls are simply refused. */
    ActionGuard REFUSE_ALL = (agent, pack, tool, args) -> java.util.Map.of(
            "status", "refused",
            "error", "This action creates billable resources and needs a person to confirm it in the "
                   + "Ananke UI. This agent cannot request that confirmation.");
}
