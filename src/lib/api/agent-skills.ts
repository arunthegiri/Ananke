import { apiFetch } from "./client";

/**
 * Agent skill packs (Packs build doc §9; backend: SkillController). Vetted cloud
 * `SKILL.md` bundles, each pinned to an upstream commit and checksummed at backend
 * startup. Turning a pack off removes its skills from every agent's index and from
 * `load_skill` immediately.
 */

export type SkillCloud = "aws" | "gcp" | "azure";

export interface AgentSkill {
  name: string;
  /** One-line description — the same text agents see in their system prompt. */
  description: string;
  topics: string[];
  files: number;
}

export interface SkillPack {
  id: string;
  cloud: SkillCloud;
  label: string;
  /** Upstream GitHub repo, `owner/name`. */
  repo: string;
  /** Pinned 40-char commit SHA. */
  commit: string;
  sourceUrl: string;
  license: string;
  distribution: "vendored" | "fetch";
  enabled: boolean;
  skills: AgentSkill[];
}

export interface AgentSkillView {
  id: string;
  label: string;
  skills: string[];
  /** Clouds whose action tools this agent is offered — empty until a cloud account is linked. */
  linkedClouds: string[];
}

export const CLOUD_LABEL: Record<SkillCloud, string> = {
  aws: "AWS",
  gcp: "Google Cloud",
  azure: "Azure",
};

export const agentSkillsApi = {
  packs: () => apiFetch<SkillPack[]>("/api/skills/packs"),
  setEnabled: (id: string, enabled: boolean) =>
    apiFetch<SkillPack>(`/api/skills/packs/${encodeURIComponent(id)}`, {
      method: "PUT",
      body: { enabled },
    }),
  agents: () => apiFetch<AgentSkillView[]>("/api/skills/agents"),
};
