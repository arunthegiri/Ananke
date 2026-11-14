package com.stocktracker.agent.model;

/**
 * A requested binding before resolution: from a saved {@link AgentModelBinding} or a run's
 * explicit per-role override (§5.2 step 1). Callers name a connection id, never a credential.
 *
 * @param bindingVersion version of the saved row it came from; null for a run override.
 */
public record BindingSpec(boolean inheritDefault, String connectionId, String modelId,
                          Integer maxOutputTokens, FallbackPolicy fallback, Long bindingVersion) {

    public static BindingSpec of(String connectionId, String modelId) {
        return new BindingSpec(false, connectionId, modelId, null, FallbackPolicy.DISABLED, null);
    }

    public BindingSpec withFallback(FallbackPolicy f) {
        return new BindingSpec(inheritDefault, connectionId, modelId, maxOutputTokens, f, bindingVersion);
    }

    public BindingSpec withMaxOutputTokens(Integer max) {
        return new BindingSpec(inheritDefault, connectionId, modelId, max, fallback, bindingVersion);
    }
}
