package com.stocktracker.agent.model;

import java.util.List;

/**
 * What happens when a role's model cannot be called (§5.4 steps 9–10). Default is
 * {@link Mode#DISABLED}: the call fails with an actionable reason. There is deliberately no
 * "any configured provider" mode — the process-global fallback in {@code ResolvingLLMClient}
 * is exactly what this path must not copy, because a stopped private Pack must never push
 * confidential research to a hosted API (§5.4).
 */
public record FallbackPolicy(Mode mode, List<Target> targets) {

    public enum Mode { DISABLED, EXPLICIT }

    public record Target(String connectionId, String modelId) { }

    public static final FallbackPolicy DISABLED = new FallbackPolicy(Mode.DISABLED, List.of());

    public FallbackPolicy {
        mode = mode == null ? Mode.DISABLED : mode;
        targets = targets == null ? List.of() : List.copyOf(targets);
    }

    public static FallbackPolicy explicit(Target... targets) {
        return new FallbackPolicy(Mode.EXPLICIT, List.of(targets));
    }

    public boolean enabled() {
        return mode == Mode.EXPLICIT && !targets.isEmpty();
    }
}
