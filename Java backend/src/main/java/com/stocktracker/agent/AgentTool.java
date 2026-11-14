package com.stocktracker.agent;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One tool an Ananke agent can offer its model (Packs build doc §9.2.4).
 *
 * <p>Tools are defined in code, never derived from model output or skill text,
 * so nothing a model reads can widen an agent's tool list (§9.3). Definitions
 * are emitted in the OpenAI function shape every {@code LLMClient} speaks.
 */
public interface AgentTool {

    String name();

    String description();

    /** JSON-schema object for the arguments. */
    Map<String, Object> parameters();

    /** Runs the tool. Returned objects are serialised to JSON for the model. */
    Object call(JsonNode args) throws Exception;

    /** Human-readable progress line shown while the call runs. */
    default String statusText(JsonNode args) {
        return "Working…";
    }

    /** Non-null for cloud action tools ({@code aws}, {@code gcp}, {@code azure}); gated on account linking. */
    default String cloud() {
        return null;
    }

    /**
     * True when the call can create billable resources. Billable tools never run inside
     * the tool loop: the model gets {@code confirmation_required} and a person confirms
     * in the UI (§9.3).
     */
    default boolean billable() {
        return false;
    }

    /** Required for billable tools: what the user is agreeing to pay for, shown before confirming. */
    default String costEstimate(JsonNode args) {
        return null;
    }

    default Map<String, Object> definition() {
        Map<String, Object> fn = new LinkedHashMap<>();
        fn.put("name", name());
        fn.put("description", description());
        fn.put("parameters", parameters());
        Map<String, Object> wrapper = new LinkedHashMap<>();
        wrapper.put("type", "function");
        wrapper.put("function", fn);
        return wrapper;
    }

    /** Builds a simple read-only tool from lambdas. */
    static AgentTool of(String name, String description, Map<String, Object> parameters,
                        ToolFn fn, java.util.function.Function<JsonNode, String> status) {
        return new AgentTool() {
            @Override public String name() { return name; }
            @Override public String description() { return description; }
            @Override public Map<String, Object> parameters() { return parameters; }
            @Override public Object call(JsonNode args) throws Exception { return fn.apply(args); }
            @Override public String statusText(JsonNode args) { return status == null ? "Working…" : status.apply(args); }
        };
    }

    @FunctionalInterface
    interface ToolFn {
        Object apply(JsonNode args) throws Exception;
    }
}
