import { useEffect, useId, useMemo, useState, useSyncExternalStore } from "react";
import { Check, CircleDot, KeyRound, Loader2, RefreshCw, Sparkles } from "lucide-react";

import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Button } from "@/components/ui/button";
import { PanelState } from "@/components/terminal/PanelState";
import {
  closeAiSettings,
  getAiSettings,
  getAiSettingsServer,
  subscribeAiSettings,
} from "@/lib/ai-settings-dialog";
import {
  isUsable,
  llmSettingsApi,
  pickDefaultModel,
  resolveKey,
  type LlmModelList,
  type LlmProvider,
} from "@/lib/api/llm-settings";
import { useLlmSettings, useUpdateLlmSettings, useUseEnvDefault } from "@/hooks/useLlmSettings";
import { cn } from "@/lib/utils";

/**
 * Settings → AI provider (PRD MC 01, bring your own AI) as a modal. The
 * canonical home is the /settings page; this dialog is the in-context
 * shortcut (Copilot's model picker / "not configured" state). Mounted once
 * (TopBar); opened via openAiSettings().
 */
export function AiProviderSettings() {
  const { open, provider: requested } = useSyncExternalStore(
    subscribeAiSettings,
    getAiSettings,
    getAiSettingsServer,
  );

  return (
    <Dialog open={open} onOpenChange={(o) => !o && closeAiSettings()}>
      <DialogContent className="max-w-3xl gap-0 p-0" data-testid="ai-settings">
        <DialogHeader className="border-b border-border-subtle px-5 py-4">
          <DialogTitle className="text-sm">AI provider</DialogTitle>
          <DialogDescription className="text-xs text-text-muted">
            Choose which model powers Copilot. Keys are stored on your Ananke backend and are never
            shown again after saving.
          </DialogDescription>
        </DialogHeader>
        {open && <AiProviderPanel requested={requested} bodyClassName="h-[400px]" />}
      </DialogContent>
    </Dialog>
  );
}

/**
 * The API-key center itself: paste-any-key quick add, the provider list, and
 * the selected provider's key / base URL / model form. Shared by the
 * /settings page and the dialog above.
 */
export function AiProviderPanel({
  requested = null,
  bodyClassName,
}: {
  requested?: string | null;
  bodyClassName?: string;
}) {
  const settings = useLlmSettings(true);
  const [selected, setSelected] = useState<string | null>(null);

  // Jump to the requested provider, else the active one, else the first.
  useEffect(() => {
    if (!settings.data) return;
    setSelected(
      requested ?? settings.data.activeProvider ?? settings.data.providers[0]?.id ?? null,
    );
  }, [requested, settings.data?.activeProvider]); // eslint-disable-line react-hooks/exhaustive-deps

  const provider = settings.data?.providers.find((p) => p.id === selected) ?? null;

  return (
    <>
      {settings.data && <QuickAdd onAdded={(id) => setSelected(id)} />}

      {settings.isError ? (
        <div className="p-5">
          <PanelState
            kind="error"
            art="plug"
            message="Couldn't load AI settings"
            detail={[
              settings.error instanceof Error ? settings.error.message : "Unknown error",
              "Is the Ananke backend running? It serves /api/settings/llm.",
            ]}
          />
        </div>
      ) : !settings.data ? (
        <div
          className={cn(
            "flex items-center justify-center text-text-muted",
            bodyClassName ?? "h-80",
          )}
        >
          <Loader2 className="h-4 w-4 animate-spin" />
        </div>
      ) : (
        <div className={cn("grid min-h-0 grid-cols-[200px_1fr]", bodyClassName)}>
          <ul
            className="overflow-y-auto border-r border-border-subtle py-2"
            role="listbox"
            aria-label="Providers"
          >
            {settings.data.providers.map((p) => (
              <li key={p.id}>
                <button
                  type="button"
                  role="option"
                  aria-selected={p.id === selected}
                  onClick={() => setSelected(p.id)}
                  data-testid={`ai-provider-${p.id}`}
                  className={cn(
                    "flex w-full items-center gap-2 px-4 py-2 text-left text-xs transition-colors",
                    p.id === selected
                      ? "bg-surface-2 text-text-primary"
                      : "text-text-secondary hover:bg-surface-1",
                  )}
                >
                  <span className="flex-1 truncate">{p.label}</span>
                  {settings.data!.activeProvider === p.id ? (
                    <CircleDot className="h-3 w-3 text-primary" aria-label="Active" />
                  ) : p.keySet ? (
                    <KeyRound className="h-3 w-3 text-text-muted" aria-label="Key saved" />
                  ) : null}
                </button>
              </li>
            ))}
          </ul>

          <div className="flex min-h-0 flex-col">
            {provider ? (
              <ProviderForm
                key={provider.id}
                provider={provider}
                isActive={settings.data.activeProvider === provider.id}
              />
            ) : null}
            <EffectiveFooter />
          </div>
        </div>
      )}
    </>
  );
}

/**
 * "Paste any API key": detects the provider from the key, verifies it by
 * loading that provider's models, saves it with a sensible default model and
 * makes it the Copilot's provider — one paste, no other choices required.
 */
function QuickAdd({ onAdded }: { onAdded: (provider: string) => void }) {
  const update = useUpdateLlmSettings();
  const [key, setKey] = useState("");
  const [busy, setBusy] = useState(false);
  const [result, setResult] = useState<{ ok: boolean; text: string } | null>(null);

  const add = async () => {
    const k = key.trim();
    if (!k || busy) return;
    setBusy(true);
    setResult(null);
    const found = await resolveKey(k);
    if ("error" in found) {
      setResult({ ok: false, text: found.error });
      setBusy(false);
      return;
    }
    const model = pickDefaultModel(found.provider, found.models);
    update.mutate(
      { provider: found.provider, apiKey: k, model, activate: true },
      {
        onSuccess: (data) => {
          const label =
            data.providers.find((p) => p.id === found.provider)?.label ?? found.provider;
          setKey("");
          setResult({
            ok: true,
            text: `${label} connected — ${found.models.length} models available. Copilot is now using ${model}; switch models from the chat box.`,
          });
          onAdded(found.provider);
        },
        onError: (e) => setResult({ ok: false, text: errText(e) }),
        onSettled: () => setBusy(false),
      },
    );
  };

  return (
    <div className="flex flex-col gap-2 border-b border-border-subtle bg-surface-1 px-5 py-4">
      <Label className="flex items-center gap-1.5 text-xs text-text-secondary">
        <Sparkles className="h-3.5 w-3.5 text-primary" />
        Paste any API key — Anthropic, OpenAI, OpenRouter, Groq, DeepSeek or Moonshot
      </Label>
      <div className="flex gap-2">
        <Input
          type="password"
          autoComplete="off"
          spellCheck={false}
          value={key}
          onChange={(e) => setKey(e.target.value)}
          onKeyDown={(e) => {
            if (e.key === "Enter") void add();
          }}
          placeholder="sk-ant-…  or  sk-proj-…"
          className="h-9 flex-1 font-mono text-xs"
          data-testid="ai-quick-key"
        />
        <Button
          size="sm"
          onClick={() => void add()}
          disabled={!key.trim() || busy}
          className="h-9 text-xs"
          data-testid="ai-quick-add"
        >
          {busy ? <Loader2 className="h-3.5 w-3.5 animate-spin" /> : null}
          {busy ? "Checking…" : "Connect"}
        </Button>
      </div>
      {result && (
        <span
          role="status"
          className={cn("text-[11px]", result.ok ? "text-text-secondary" : "text-destructive")}
        >
          {result.text}
        </span>
      )}
    </div>
  );
}

function ProviderForm({ provider, isActive }: { provider: LlmProvider; isActive: boolean }) {
  const ids = { key: useId(), base: useId(), model: useId(), list: useId() };
  const update = useUpdateLlmSettings();

  const [apiKey, setApiKey] = useState("");
  const [baseUrl, setBaseUrl] = useState(provider.baseUrl);
  const [model, setModel] = useState(provider.model);
  const [models, setModels] = useState<LlmModelList | null>(null);
  const [loadingModels, setLoadingModels] = useState(false);
  const [test, setTest] = useState<{ ok: boolean; text: string } | null>(null);
  const [testing, setTesting] = useState(false);
  const [saved, setSaved] = useState(false);

  const isCompat = provider.kind === "openai_compatible";
  const probe = useMemo(
    () => ({
      provider: provider.id,
      apiKey: apiKey || undefined,
      baseUrl: isCompat ? baseUrl : undefined,
      model: model || undefined,
    }),
    [provider.id, apiKey, baseUrl, model, isCompat],
  );

  const refreshModels = async () => {
    setLoadingModels(true);
    try {
      setModels(await llmSettingsApi.models(probe));
    } catch (e) {
      setModels({ models: provider.suggestedModels, live: false, error: errText(e) });
    } finally {
      setLoadingModels(false);
    }
  };

  // Load the model list once if this provider can already authenticate.
  useEffect(() => {
    if (provider.keySet || !provider.keyRequired) void refreshModels();
    else setModels({ models: provider.suggestedModels, live: false, error: null });
  }, []); // eslint-disable-line react-hooks/exhaustive-deps

  const runTest = async () => {
    setTesting(true);
    setTest(null);
    try {
      const r = await llmSettingsApi.test(probe);
      setTest(
        r.ok
          ? { ok: true, text: `Connected — ${model || provider.model} replied “${r.reply ?? ""}”` }
          : { ok: false, text: r.error ?? "Test failed" },
      );
    } catch (e) {
      setTest({ ok: false, text: errText(e) });
    } finally {
      setTesting(false);
    }
  };

  const save = (activate: boolean) => {
    setSaved(false);
    update.mutate(
      {
        provider: provider.id,
        apiKey: apiKey ? apiKey : undefined,
        baseUrl: isCompat ? baseUrl : undefined,
        model,
        activate,
      },
      {
        onSuccess: () => {
          setApiKey("");
          setSaved(true);
        },
      },
    );
  };

  const needsKey = provider.keyRequired && !provider.keySet && !apiKey;
  const canUse = !needsKey && Boolean(model) && (!isCompat || Boolean(baseUrl));

  return (
    <div className="flex min-h-0 flex-1 flex-col gap-4 overflow-y-auto px-5 py-4">
      <div className="flex items-center gap-2">
        <h3 className="text-sm font-medium text-text-primary">{provider.label}</h3>
        {isActive && (
          <span className="rounded bg-primary/15 px-1.5 py-0.5 text-[10px] font-medium uppercase tracking-wider text-primary">
            Active
          </span>
        )}
      </div>

      <div className="flex flex-col gap-1.5">
        <Label htmlFor={ids.key} className="text-xs text-text-secondary">
          API key {!provider.keyRequired && <span className="text-text-muted">(optional)</span>}
        </Label>
        <Input
          id={ids.key}
          type="password"
          autoComplete="off"
          spellCheck={false}
          value={apiKey}
          onChange={(e) => setApiKey(e.target.value)}
          placeholder={
            provider.keySet
              ? `Saved (${provider.keyHint ?? "…"}) — paste to replace`
              : "Paste API key"
          }
          className="h-8 font-mono text-xs"
          data-testid="ai-api-key"
        />
        {provider.keySet && (
          <button
            type="button"
            className="self-start text-[11px] text-text-muted underline-offset-2 hover:text-text-secondary hover:underline"
            onClick={() => update.mutate({ provider: provider.id, apiKey: "", activate: false })}
          >
            Remove saved key
          </button>
        )}
      </div>

      {isCompat && (
        <div className="flex flex-col gap-1.5">
          <Label htmlFor={ids.base} className="text-xs text-text-secondary">
            Base URL
          </Label>
          <Input
            id={ids.base}
            value={baseUrl}
            onChange={(e) => setBaseUrl(e.target.value)}
            placeholder="https://…/v1"
            spellCheck={false}
            className="h-8 font-mono text-xs"
          />
          {provider.id === "ollama" || provider.id === "lmstudio" ? (
            <span className="text-[11px] text-text-muted">
              The backend runs in Docker, so “host.docker.internal” reaches servers on this Mac.
            </span>
          ) : null}
        </div>
      )}

      <div className="flex flex-col gap-1.5">
        <div className="flex items-center justify-between">
          <Label htmlFor={ids.model} className="text-xs text-text-secondary">
            Model
          </Label>
          <button
            type="button"
            onClick={() => void refreshModels()}
            disabled={loadingModels}
            className="flex items-center gap-1 text-[11px] text-text-muted hover:text-text-secondary disabled:opacity-50"
          >
            <RefreshCw className={cn("h-3 w-3", loadingModels && "animate-spin")} />
            Load models
          </button>
        </div>
        <Input
          id={ids.model}
          list={ids.list}
          value={model}
          onChange={(e) => setModel(e.target.value)}
          placeholder="Pick or type a model id"
          spellCheck={false}
          className="h-8 font-mono text-xs"
          data-testid="ai-model"
        />
        <datalist id={ids.list}>
          {(models?.models ?? provider.suggestedModels).map((m) => (
            <option key={m} value={m} />
          ))}
        </datalist>
        {models && (
          <span className={cn("text-[11px]", models.error ? "text-warning" : "text-text-muted")}>
            {models.error
              ? models.error
              : models.live
                ? `${models.models.length} models available`
                : "Suggested models — add a key and load the full list"}
          </span>
        )}
      </div>

      {test && (
        <div
          role="status"
          className={cn(
            "rounded-md border px-3 py-2 text-xs",
            test.ok
              ? "border-border-subtle text-text-secondary"
              : "border-destructive/40 text-destructive",
          )}
        >
          {test.text}
        </div>
      )}
      {update.isError && (
        <div
          role="alert"
          className="rounded-md border border-destructive/40 px-3 py-2 text-xs text-destructive"
        >
          {errText(update.error)}
        </div>
      )}

      <div className="mt-auto flex items-center gap-2 pt-2">
        <Button
          variant="outline"
          size="sm"
          onClick={() => void runTest()}
          disabled={!canUse || testing}
          className="h-8 text-xs"
        >
          {testing ? <Loader2 className="h-3.5 w-3.5 animate-spin" /> : null}
          Test
        </Button>
        <div className="flex-1" />
        {saved && !update.isPending && (
          <span className="flex items-center gap-1 text-xs text-text-muted">
            <Check className="h-3.5 w-3.5" /> Saved
          </span>
        )}
        <Button
          variant="outline"
          size="sm"
          onClick={() => save(false)}
          disabled={update.isPending}
          className="h-8 text-xs"
        >
          Save
        </Button>
        <Button
          size="sm"
          onClick={() => save(true)}
          disabled={!canUse || update.isPending}
          className="h-8 text-xs"
          data-testid="ai-save-activate"
        >
          {update.isPending ? <Loader2 className="h-3.5 w-3.5 animate-spin" /> : null}
          {isActive ? "Save" : "Save & use for Copilot"}
        </Button>
      </div>
    </div>
  );
}

function EffectiveFooter() {
  const settings = useLlmSettings();
  const reset = useUseEnvDefault();
  const s = settings.data;
  if (!s) return null;

  const active = s.providers.find((p) => p.id === s.activeProvider);
  let text: string;
  if (s.effectiveSource === "settings" && active) {
    text = `Copilot is using ${active.label} · ${s.effectiveModel}`;
  } else if (s.activeProvider && active && !isUsable(active)) {
    text = `${active.label} is selected but incomplete — ${
      s.envConfigured ? `using .env (${s.envModel})` : "Copilot is off"
    }`;
  } else if (s.envConfigured) {
    text = `Copilot is using the .env provider · ${s.envModel}`;
  } else {
    text = "Copilot is off — no provider has a key yet.";
  }

  return (
    <div className="flex items-center gap-3 border-t border-border-subtle px-5 py-3 text-[11px] text-text-muted">
      <span className="flex-1 truncate" data-testid="ai-effective">
        {text}
      </span>
      {s.activeProvider && s.envConfigured && (
        <button type="button" onClick={() => reset.mutate()} className="hover:text-text-secondary">
          Use .env default
        </button>
      )}
    </div>
  );
}

function errText(e: unknown): string {
  return e instanceof Error ? e.message : String(e);
}
