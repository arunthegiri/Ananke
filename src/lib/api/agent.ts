import { apiFetch } from "./client";

/**
 * Agent actions that wait for a person (Packs build doc §9.3) and the Pack agent.
 * A billable tool call from any agent becomes a PendingAction; only `confirm` runs it,
 * and every step is written to the audit log on the Pack.
 */

export interface PendingAction {
  id: string;
  agent: string;
  packId: number | null;
  cloud: string | null;
  tool: string;
  /** What the person is agreeing to pay for. Always present: billable tools without one are refused. */
  costEstimate: string;
  /** The exact arguments the agent asked for (JSON). */
  arguments: string;
  createdAt: string;
  expiresAt: string;
}

export interface AuditEntry {
  id: number;
  actionId: string;
  packId: number | null;
  agent: string;
  cloud: string | null;
  tool: string;
  event: "REQUESTED" | "CONFIRMED" | "REJECTED" | "EXECUTED" | "FAILED" | "EXPIRED" | "REFUSED";
  costEstimate: string | null;
  arguments: string | null;
  detail: string | null;
  createdAt: string;
}

export interface AgentTurn {
  role: "user" | "assistant";
  content: string;
}

export interface PackAgentReply {
  answer: string;
  toolCalls: { tool: string; status: string }[];
  tools: string[];
}

export const PENDING_ACTIONS_KEY = ["agent", "actions"] as const;

export const agentApi = {
  pending: () => apiFetch<PendingAction[]>("/api/agent/actions"),
  confirm: (id: string) =>
    apiFetch<{ actionId: string; result: unknown }>(
      `/api/agent/actions/${encodeURIComponent(id)}/confirm`,
      {
        method: "POST",
      },
    ),
  reject: (id: string) =>
    apiFetch<void>(`/api/agent/actions/${encodeURIComponent(id)}/reject`, { method: "POST" }),
  audit: (packId: number) => apiFetch<AuditEntry[]>(`/api/packs/${packId}/audit`),
  packAgentChat: (messages: AgentTurn[], packId?: number | null) =>
    apiFetch<PackAgentReply>("/api/packs/agent/chat", {
      method: "POST",
      body: { messages, packId: packId ?? null },
    }),
};

/** Human-readable tool name: aws_provision_gpu_vm → "provision gpu vm (AWS)". */
export function toolLabel(tool: string): string {
  const m = /^(aws|gcp|azure)_(.+)$/.exec(tool);
  return m ? `${m[2].replaceAll("_", " ")} (${m[1].toUpperCase()})` : tool.replaceAll("_", " ");
}
