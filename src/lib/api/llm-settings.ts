import { apiFetch } from "./client";

/**
 * Copilot provider / model / API-key settings (backend: LlmSettingsController).
 * API keys are write-only — the server reports `keySet` + a last-four `keyHint`
 * and never returns the key itself.
 */

export type ProviderKind = "anthropic" | "openai_compatible";

export interface LlmProvider {
  id: string;
  label: string;
  kind: ProviderKind;
  keyRequired: boolean;
  defaultBaseUrl: string;
  baseUrl: string;
  model: string;
  suggestedModels: string[];
  keySet: boolean;
  keyHint: string | null;
}

export interface LlmSettings {
  /** Provider chosen in Settings, or null when the .env provider is in effect. */
  activeProvider: string | null;
  effectiveSource: "settings" | "env";
  configured: boolean;
  effectiveModel: string | null;
  envModel: string | null;
  envConfigured: boolean;
  providers: LlmProvider[];
}

export interface LlmModelList {
  models: string[];
  /** True when the list came from the provider's API, not just presets. */
  live: boolean;
  error: string | null;
}

export interface LlmSettingsUpdate {
  provider: string;
  /** Omit to keep the saved key; "" clears it. */
  apiKey?: string;
  baseUrl?: string;
  model?: string;
  activate?: boolean;
}

export interface LlmProbe {
  provider: string;
  apiKey?: string;
  baseUrl?: string;
  model?: string;
}

/** A provider the copilot can use right now: it has a key, or doesn't need one. */
export function isUsable(p: LlmProvider): boolean {
  return (p.keySet || !p.keyRequired) && Boolean(p.baseUrl) && Boolean(p.model);
}

export const llmSettingsApi = {
  get: () => apiFetch<LlmSettings>("/api/settings/llm"),
  update: (body: LlmSettingsUpdate) =>
    apiFetch<LlmSettings>("/api/settings/llm", { method: "PUT", body }),
  useEnvDefault: () => apiFetch<LlmSettings>("/api/settings/llm/active", { method: "DELETE" }),
  models: (body: LlmProbe) =>
    apiFetch<LlmModelList>("/api/settings/llm/models", { method: "POST", body }),
  test: (body: LlmProbe) =>
    apiFetch<{ ok: boolean; reply?: string; error?: string }>("/api/settings/llm/test", {
      method: "POST",
      body,
    }),
};
