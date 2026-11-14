package com.stocktracker.agent.actions;

import com.stocktracker.agent.AgentTool;

import java.util.List;

/**
 * A source of cloud action tools (an AWS/Azure MCP server, or Ananke's own provisioning
 * tools) for one cloud (§9.3). Implementations arrive with account linking (§7.1): they must
 * use that account's scoped credentials from {@code SecretVault}, never the backend's
 * ambient credentials. None is registered today — no cloud can be linked yet, so no
 * cloud action tool is offered anywhere, and none is pretended.
 */
public interface CloudActionToolSource {

    /** {@code aws}, {@code gcp} or {@code azure}. */
    String cloud();

    /** True only once the user has linked this cloud account. */
    boolean isLinked();

    /** The tools; each must report {@link AgentTool#cloud()} equal to {@link #cloud()}. */
    List<AgentTool> tools();
}
