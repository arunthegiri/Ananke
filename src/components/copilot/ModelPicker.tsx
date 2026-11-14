import { useState } from "react";
import { useQueries } from "@tanstack/react-query";
import { Check, ChevronDown, Cpu, KeyRound, Loader2, Settings2 } from "lucide-react";

import { Popover, PopoverContent, PopoverTrigger } from "@/components/ui/popover";
import {
  Command,
  CommandEmpty,
  CommandGroup,
  CommandInput,
  CommandItem,
  CommandList,
  CommandSeparator,
} from "@/components/ui/command";
import { openAiSettings } from "@/lib/ai-settings-dialog";
import { isUsable, llmSettingsApi } from "@/lib/api/llm-settings";
import { useLlmSettings, useUpdateLlmSettings } from "@/hooks/useLlmSettings";
import { cn } from "@/lib/utils";

/**
 * Model dropdown in the Copilot chat box. Lists every model of every provider
 * that has a key (fetched live from each provider's API, so a new Anthropic
 * or OpenAI model appears without an Ananke update), with type-to-filter.
 * Picking one makes it the Copilot's model from the next message on.
 */
export function ModelPicker({ className }: { className?: string }) {
  const [open, setOpen] = useState(false);
  const settings = useLlmSettings();
  const update = useUpdateLlmSettings();
  const s = settings.data;

  const usable = s?.providers.filter(isUsable) ?? [];
  const activeId = s?.effectiveSource === "settings" ? s.activeProvider : null;
  const active = s?.providers.find((p) => p.id === activeId);

  // One live model list per connected provider. The key hint is in the query
  // key, so replacing a key refetches; lists are cached for 10 minutes.
  const lists = useQueries({
    queries: usable.map((p) => ({
      queryKey: ["settings", "llm", "models", p.id, p.keyHint, p.baseUrl],
      queryFn: () => llmSettingsApi.models({ provider: p.id }),
      enabled: open,
      staleTime: 10 * 60_000,
      retry: false,
    })),
  });

  const label = !s
    ? "Model"
    : active
      ? (s.effectiveModel ?? "Model")
      : s.envConfigured
        ? (s.envModel ?? "Model")
        : "Add an API key";

  const choose = (provider: string, model: string) => {
    setOpen(false);
    update.mutate({ provider, model, activate: true });
  };

  return (
    <Popover open={open} onOpenChange={setOpen}>
      <PopoverTrigger asChild>
        <button
          type="button"
          data-testid="copilot-model-picker"
          aria-label="Choose Copilot model"
          title={active ? `${active.label} · ${s?.effectiveModel}` : undefined}
          className={cn(
            "flex h-7 max-w-[280px] items-center gap-1.5 rounded-md px-2",
            "text-[11px] text-text-muted transition-colors hover:bg-surface-2 hover:text-text-primary",
            s && !s.configured && "text-warning",
            className,
          )}
        >
          {update.isPending ? (
            <Loader2 className="h-3 w-3 shrink-0 animate-spin" />
          ) : s && !s.configured ? (
            <KeyRound className="h-3 w-3 shrink-0" />
          ) : (
            <Cpu className="h-3 w-3 shrink-0" />
          )}
          {active && <span className="shrink-0 text-text-muted">{active.label}</span>}
          <span className="truncate font-mono text-text-secondary">{label}</span>
          <ChevronDown className="h-3 w-3 shrink-0 opacity-60" />
        </button>
      </PopoverTrigger>
      <PopoverContent align="start" side="top" className="w-80 p-0">
        <Command>
          <CommandInput placeholder="Search models…" className="h-9 text-xs" />
          <CommandList className="max-h-[50vh]">
            <CommandEmpty className="py-4 text-center text-xs text-text-muted">
              {usable.length === 0 ? "Add an API key to see models." : "No matching model."}
            </CommandEmpty>
            {usable.map((p, i) => {
              const q = lists[i];
              const models = q?.data?.models?.length ? q.data.models : [p.model].filter(Boolean);
              return (
                <CommandGroup
                  key={p.id}
                  heading={
                    <span className="flex items-center gap-2">
                      {p.label}
                      {q?.isFetching && <Loader2 className="h-3 w-3 animate-spin" />}
                      {q?.data?.error && (
                        <span className="truncate font-normal normal-case text-warning">
                          {q.data.error}
                        </span>
                      )}
                    </span>
                  }
                >
                  {models.map((m) => {
                    const selected = activeId === p.id && s?.effectiveModel === m;
                    return (
                      <CommandItem
                        key={`${p.id}:${m}`}
                        value={`${p.label} ${m}`}
                        onSelect={() => choose(p.id, m)}
                        className="gap-2 font-mono text-xs"
                      >
                        <Check
                          className={cn("h-3 w-3 shrink-0", selected ? "opacity-100" : "opacity-0")}
                        />
                        <span className="truncate">{m}</span>
                      </CommandItem>
                    );
                  })}
                </CommandGroup>
              );
            })}
            <CommandSeparator />
            <CommandGroup>
              <CommandItem
                value="__manage providers api keys"
                onSelect={() => {
                  setOpen(false);
                  openAiSettings();
                }}
                className="gap-2 text-xs"
              >
                <Settings2 className="h-3.5 w-3.5" />
                Add or manage API keys…
              </CommandItem>
            </CommandGroup>
          </CommandList>
        </Command>
      </PopoverContent>
    </Popover>
  );
}
