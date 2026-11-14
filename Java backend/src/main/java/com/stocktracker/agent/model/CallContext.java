package com.stocktracker.agent.model;

/**
 * Who is calling: server-established, never taken from model output (§7). {@code runId} and
 * {@code taskId} are null until the durable runtime (M2) exists.
 */
public record CallContext(String workspaceId, String runId, String taskId, String roleId) {

    public static CallContext adHoc(String workspaceId, String roleId) {
        return new CallContext(workspaceId, null, null, roleId);
    }
}
