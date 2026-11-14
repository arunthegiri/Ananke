package com.stocktracker.agent.model;

/** Wire adapter behind a model connection (§5.1). */
public enum Adapter {
    ANTHROPIC,
    OPENAI_COMPATIBLE,
    /** A deployed Ananke Pack (vLLM over an SSH tunnel). Owned by the Packs workstream. */
    PACK
}
