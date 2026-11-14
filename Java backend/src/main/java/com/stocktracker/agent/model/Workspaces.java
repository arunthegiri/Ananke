package com.stocktracker.agent.model;

/**
 * Ownership scope (§8.6). Ananke is a single local installation today, so every request runs
 * in the {@link #LOCAL} workspace, chosen by the server. The column exists so hosted
 * multi-user work has somewhere to land — a {@code workspace_id} column is not an
 * authorization system, and nothing here pretends to be one.
 */
public final class Workspaces {
    public static final String LOCAL = "local";

    private Workspaces() { }
}
