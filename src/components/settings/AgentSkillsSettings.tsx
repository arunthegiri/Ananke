import { useState } from "react";
import { ChevronDown, ChevronRight, ExternalLink, Loader2 } from "lucide-react";

import { Switch } from "@/components/ui/switch";
import { PanelState } from "@/components/terminal/PanelState";
import { CLOUD_LABEL, type SkillPack } from "@/lib/api/agent-skills";
import { useSetSkillPackEnabled, useSkillAgents, useSkillPacks } from "@/hooks/useAgentSkills";
import { cn } from "@/lib/utils";

/**
 * Settings → Agent skills (Packs build doc §9.4): every vetted skill pack with its
 * upstream source, pinned commit and licence, and a switch to turn it off for all agents.
 */
export function AgentSkillsPanel({ bodyClassName }: { bodyClassName?: string }) {
  const packs = useSkillPacks();
  const agents = useSkillAgents();

  if (packs.isError) {
    return (
      <div className="p-5">
        <PanelState
          kind="error"
          art="plug"
          message="Couldn't load agent skills"
          detail={[
            packs.error instanceof Error ? packs.error.message : "Unknown error",
            "Is the Ananke backend running? It serves /api/skills/packs.",
          ]}
        />
      </div>
    );
  }
  if (!packs.data) {
    return (
      <div
        className={cn("flex items-center justify-center text-text-muted", bodyClassName ?? "h-80")}
      >
        <Loader2 className="h-4 w-4 animate-spin" />
      </div>
    );
  }
  if (packs.data.length === 0) {
    return (
      <div className="p-5">
        <PanelState
          kind="empty"
          art="table"
          message="No skill packs are bundled with this backend."
        />
      </div>
    );
  }

  const linked = agents.data?.flatMap((a) => a.linkedClouds) ?? [];

  return (
    <div className={cn("overflow-y-auto", bodyClassName)} data-testid="agent-skills">
      <ul className="divide-y divide-border-subtle">
        {packs.data.map((p) => (
          <PackRow key={p.id} pack={p} />
        ))}
      </ul>

      <div className="flex flex-col gap-1 border-t border-border-subtle px-5 py-3 text-[11px] text-text-muted">
        {agents.data?.map((a) => (
          <span key={a.id} data-testid={`agent-skill-count-${a.id}`}>
            <span className="text-text-secondary">{a.label}</span> sees {a.skills.length} skill
            {a.skills.length === 1 ? "" : "s"}
          </span>
        ))}
        <span>
          Cloud action tools:{" "}
          {linked.length === 0
            ? "none. No cloud account is linked, so agents can only read guidance."
            : [...new Set(linked)].join(", ")}
        </span>
      </div>
    </div>
  );
}

function PackRow({ pack }: { pack: SkillPack }) {
  const [open, setOpen] = useState(false);
  const toggle = useSetSkillPackEnabled();
  const pending = toggle.isPending && toggle.variables?.id === pack.id;
  const switchId = `skill-pack-${pack.id}`;

  return (
    <li className="px-5 py-3" data-testid={`skill-pack-${pack.id}`}>
      <div className="flex items-start gap-3">
        <button
          type="button"
          onClick={() => setOpen((o) => !o)}
          className="mt-0.5 text-text-muted hover:text-text-primary"
          aria-expanded={open}
          aria-label={`${open ? "Hide" : "Show"} skills in ${pack.label}`}
        >
          {open ? (
            <ChevronDown className="h-3.5 w-3.5" />
          ) : (
            <ChevronRight className="h-3.5 w-3.5" />
          )}
        </button>

        <div className="min-w-0 flex-1">
          <div className="flex items-center gap-2">
            <label htmlFor={switchId} className="text-xs font-medium text-text-primary">
              {pack.label}
            </label>
            <span className="rounded border border-border-subtle bg-surface-1 px-1.5 py-px text-[10px] uppercase tracking-wider text-text-secondary">
              {CLOUD_LABEL[pack.cloud] ?? pack.cloud}
            </span>
            <span className="text-[11px] text-text-muted">
              {pack.skills.length} skill{pack.skills.length === 1 ? "" : "s"}
            </span>
          </div>
          <div className="mt-1 flex flex-wrap items-center gap-x-3 gap-y-0.5 font-mono text-[11px] text-text-muted">
            <a
              href={pack.sourceUrl}
              target="_blank"
              rel="noreferrer"
              className="inline-flex items-center gap-1 text-accent-blue hover:underline"
              data-testid={`skill-pack-source-${pack.id}`}
            >
              {pack.repo}@{pack.commit.slice(0, 12)}
              <ExternalLink className="h-3 w-3" />
            </a>
            <span>{pack.license}</span>
            {pack.distribution === "fetch" && <span>fetched at install</span>}
          </div>
          {toggle.isError && toggle.variables?.id === pack.id && (
            <p className="mt-1 text-[11px] text-warning">
              Couldn't save:{" "}
              {toggle.error instanceof Error ? toggle.error.message : "unknown error"}
            </p>
          )}
        </div>

        <div className="flex items-center gap-2">
          {pending && <Loader2 className="h-3.5 w-3.5 animate-spin text-text-muted" />}
          <Switch
            id={switchId}
            checked={pack.enabled}
            disabled={pending}
            onCheckedChange={(enabled) => toggle.mutate({ id: pack.id, enabled })}
            aria-label={`${pack.enabled ? "Turn off" : "Turn on"} ${pack.label}`}
            data-testid={`skill-pack-switch-${pack.id}`}
          />
        </div>
      </div>

      {open && (
        <ul className="ml-6 mt-2 flex flex-col gap-1.5">
          {pack.skills.map((s) => (
            <li key={s.name} className="text-[11px]">
              <span className="font-mono text-text-primary">{s.name}</span>
              <span className="text-text-muted"> · {s.topics.join(", ")}</span>
              <p className="text-text-secondary">{s.description}</p>
            </li>
          ))}
        </ul>
      )}
    </li>
  );
}
