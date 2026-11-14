import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { llmSettingsApi, type LlmSettings, type LlmSettingsUpdate } from "@/lib/api/llm-settings";

export const LLM_SETTINGS_KEY = ["settings", "llm"] as const;

/** Copilot provider settings. Client-only fetch — nothing here renders during SSR. */
export function useLlmSettings(enabled = true) {
  return useQuery({
    queryKey: LLM_SETTINGS_KEY,
    queryFn: llmSettingsApi.get,
    enabled,
    staleTime: 30_000,
    retry: false,
  });
}

export function useUpdateLlmSettings() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (body: LlmSettingsUpdate) => llmSettingsApi.update(body),
    onSuccess: (data: LlmSettings) => qc.setQueryData(LLM_SETTINGS_KEY, data),
  });
}

export function useUseEnvDefault() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: () => llmSettingsApi.useEnvDefault(),
    onSuccess: (data: LlmSettings) => qc.setQueryData(LLM_SETTINGS_KEY, data),
  });
}
