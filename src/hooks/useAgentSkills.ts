import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { agentSkillsApi, type SkillPack } from "@/lib/api/agent-skills";

export const SKILL_PACKS_KEY = ["skills", "packs"] as const;
export const SKILL_AGENTS_KEY = ["skills", "agents"] as const;

/** Skill packs + their toggles. Client-only fetch — nothing here renders during SSR. */
export function useSkillPacks() {
  return useQuery({
    queryKey: SKILL_PACKS_KEY,
    queryFn: agentSkillsApi.packs,
    staleTime: 60_000,
    retry: false,
  });
}

export function useSkillAgents() {
  return useQuery({
    queryKey: SKILL_AGENTS_KEY,
    queryFn: agentSkillsApi.agents,
    staleTime: 60_000,
    retry: false,
  });
}

export function useSetSkillPackEnabled() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: ({ id, enabled }: { id: string; enabled: boolean }) =>
      agentSkillsApi.setEnabled(id, enabled),
    onSuccess: (pack: SkillPack) => {
      qc.setQueryData<SkillPack[]>(SKILL_PACKS_KEY, (old) =>
        old?.map((p) => (p.id === pack.id ? pack : p)),
      );
      void qc.invalidateQueries({ queryKey: SKILL_AGENTS_KEY });
    },
  });
}
