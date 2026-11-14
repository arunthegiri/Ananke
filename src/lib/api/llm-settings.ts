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

/**
 * Which provider a pasted key belongs to, from its prefix. Order matters:
 * `sk-ant-` and `sk-or-` must be checked before the generic OpenAI `sk-`.
 * Returns null for keys with no recognisable prefix (Together, DeepSeek and
 * Moonshot all issue look-alike keys) — the user picks those by hand.
 */
export function detectProviders(key: string): string[] {
  const k = key.trim();
  if (k.startsWith("sk-ant-")) return ["anthropic"];
  if (k.startsWith("sk-or-")) return ["openrouter"];
  if (k.startsWith("gsk_")) return ["groq"];
  if (k.startsWith("sk-proj-") || k.startsWith("sk-svcacct-") || k.startsWith("sk-admin-"))
    return ["openai"];
  // A bare sk-… key could be OpenAI, DeepSeek or Moonshot — try them in that order.
  if (/^sk-[A-Za-z0-9_-]{16,}$/.test(k)) return ["openai", "deepseek", "moonshot"];
  // Together and others have no fixed prefix.
  return [];
}

/**
 * Resolves a pasted key to the first candidate provider that accepts it, by
 * asking each one for its model list. Returns the provider and its models.
 */
export async function resolveKey(
  key: string,
): Promise<{ provider: string; models: string[] } | { error: string }> {
  const candidates = detectProviders(key);
  if (candidates.length === 0) {
    return { error: "Couldn't tell which provider this key is for — pick it from the list below." };
  }
  let lastError = "";
  for (const provider of candidates) {
    try {
      const res = await llmSettingsApi.models({ provider, apiKey: key.trim() });
      if (res.live && res.models.length > 0) return { provider, models: res.models };
      lastError = res.error ?? "No models returned.";
    } catch (e) {
      lastError = e instanceof Error ? e.message : String(e);
    }
  }
  return {
    error:
      candidates.length === 1
        ? `The key was rejected: ${lastError}`
        : `No provider accepted this key (tried ${candidates.join(", ")}): ${lastError}`,
  };
}

/** Picks a sensible default model from a live list: a current general-purpose one, not a mini/nano. */
export function pickDefaultModel(provider: string, models: string[]): string {
  const prefer: Record<string, RegExp[]> = {
    anthropic: [/sonnet/, /opus/],
    openai: [/^gpt-\d(\.\d)?$/, /^gpt-\d/, /^o\d/],
  };
  for (const re of prefer[provider] ?? []) {
    const hit = models.find((m) => re.test(m) && !/mini|nano|preview/.test(m));
    if (hit) return hit;
  }
  return models[0] ?? "";
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
