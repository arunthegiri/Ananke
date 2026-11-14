package com.stocktracker.connectors;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Duration;
import java.util.Map;
import java.util.function.Function;

/**
 * One read-only call a connector offers to panels ({@code POST /api/connectors/{id}/tools/{tool}})
 * and to agents (as an {@code AgentTool} of the same name).
 *
 * @param cacheTtl how long an identical call is answered from cache; {@link Duration#ZERO} = never
 */
public record ConnectorTool(
        String name,
        String description,
        Map<String, Object> parameters,
        Duration cacheTtl,
        Handler handler,
        Function<JsonNode, String> status) {

    public ConnectorTool {
        if (name == null || !name.matches("^[a-z0-9_]{1,64}$")) throw new IllegalArgumentException("Bad tool name: " + name);
        cacheTtl = cacheTtl == null ? Duration.ZERO : cacheTtl;
        status = status == null ? a -> "Fetching data…" : status;
    }

    @FunctionalInterface
    public interface Handler {
        Object call(ConnectorContext ctx, JsonNode args) throws Exception;
    }
}
