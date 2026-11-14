import { useState } from "react";
import { useQuery } from "@tanstack/react-query";
import { Check, ChevronDown, Cpu, Loader2, Settings2 } from "lucide-react";

import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuLabel,
  DropdownMenuSeparator,
  DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu";
import { openAiSettings } from "@/lib/ai-settings-dialog";
import { isUsable, llmSettingsApi } from "@/lib/api/llm-settings";
import { useLlmSettings, useUpdateLlmSettings } from "@/hooks/useLlmSettings";
import { cn } from "@/lib/utils";

const MAX_MODELS_PER_PROVIDER = 12;

/**
 * Copilot header control: shows which provider · model answers, and switches
 * between the providers that have keys. Choosing a model makes it the active
 * Copilot model for the next message (server-side setting, applies to every
 * window). Full key/URL management lives in the AI provider dialog.
 */
export function ModelPicker() {
  const [open, setOpen] = useState(false);
  const settings = useLlmSettings();
  const update = useUpdateLlmSettings();
  const s = settings.data;

  const active = s?.providers.find(
    (p) => p.id === s.activeProvider && s.effectiveSource === "settings",
  );
  const usable = s?.providers.filter(isUsable) ?? [];

  // Live model list for the active provider, fetched only while the menu is open.
  const liveModels = useQuery({
    queryKey: ["settings", "llm", "models", active?.id],
    queryFn: () => llmSettingsApi.models({ provider: active!.id }),
    enabled: open && Boolean(active),
    staleTime: 5 * 60_000,
    retry: false,
  });

  const label = !s
    ? "AI model"
    : active
      ? `${active.label} · ${s.effectiveModel}`
      : s.envConfigured
        ? `.env · ${s.envModel}`
        : "Set up AI";

  const choose = (provider: string, model: string) =>
    update.mutate({ provider, model, activate: true });

  return (
    <DropdownMenu open={open} onOpenChange={setOpen}>
      <DropdownMenuTrigger asChild>
        <button
          type="button"
          data-testid="copilot-model-picker"
          aria-label="Choose Copilot model"
          className={cn(
            "flex h-7 max-w-[260px] items-center gap-1.5 rounded-md border border-border-subtle px-2",
            "text-[11px] text-text-secondary transition-colors hover:bg-surface-2 hover:text-text-primary",
            s && !s.configured && "text-warning",
          )}
        >
          {update.isPending ? (
            <Loader2 className="h-3 w-3 shrink-0 animate-spin" />
          ) : (
            <Cpu className="h-3 w-3 shrink-0" />
          )}
          <span className="truncate font-mono">{label}</span>
          <ChevronDown className="h-3 w-3 shrink-0 opacity-60" />
        </button>
      </DropdownMenuTrigger>
      <DropdownMenuContent align="end" className="max-h-[70vh] w-72 overflow-y-auto">
        {usable.length === 0 && (
          <div className="px-2 py-3 text-xs text-text-muted">
            No provider has a key yet. Add one to choose a model.
          </div>
        )}
        {usable.map((p) => {
          const isActive = active?.id === p.id;
          const fromApi = isActive ? (liveModels.data?.models ?? []) : [];
          const models = dedupe([p.model, ...p.suggestedModels, ...fromApi]).slice(
            0,
            MAX_MODELS_PER_PROVIDER,
          );
          return (
            <div key={p.id}>
              <DropdownMenuLabel className="flex items-center gap-2 text-[10px] uppercase tracking-wider text-text-muted">
                {p.label}
                {isActive && liveModels.isFetching && <Loader2 className="h-3 w-3 animate-spin" />}
              </DropdownMenuLabel>
              {models.map((m) => {
                const selected = isActive && s?.effectiveModel === m;
                return (
                  <DropdownMenuItem
                    key={m}
                    onSelect={() => choose(p.id, m)}
                    className="gap-2 font-mono text-xs"
                  >
                    <Check className={cn("h-3 w-3", selected ? "opacity-100" : "opacity-0")} />
                    <span className="truncate">{m}</span>
                  </DropdownMenuItem>
                );
              })}
              <DropdownMenuItem
                onSelect={() => openAiSettings(p.id)}
                className="pl-7 text-xs text-text-muted"
              >
                Other {p.label} model…
              </DropdownMenuItem>
              <DropdownMenuSeparator />
            </div>
          );
        })}
        <DropdownMenuItem onSelect={() => openAiSettings()} className="gap-2 text-xs">
          <Settings2 className="h-3.5 w-3.5" />
          Providers & API keys…
        </DropdownMenuItem>
      </DropdownMenuContent>
    </DropdownMenu>
  );
}

function dedupe(xs: string[]): string[] {
  return [...new Set(xs.filter(Boolean))];
}
