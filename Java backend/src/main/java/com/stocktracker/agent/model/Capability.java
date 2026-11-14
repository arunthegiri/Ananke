package com.stocktracker.agent.model;

/** What a model/deployment can do for a role (§5.1, §5.6). */
public enum Capability {
    TEXT, TOOL_CALLING, STRUCTURED_OUTPUT, VISION, STREAMING;

    /**
     * {@code VERIFIED} means a live conformance probe passed for the <em>current</em>
     * connection version and deployment revision — not that the model is any good at
     * research. Anything never probed, or probed against an older configuration, is
     * {@code UNVERIFIED}. {@code UNSUPPORTED} is only recorded when a source of truth says
     * so; a failed probe is {@code UNVERIFIED}, because one failure does not prove absence.
     */
    public enum Status { VERIFIED, UNVERIFIED, UNSUPPORTED }
}
