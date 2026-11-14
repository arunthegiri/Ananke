import { useState } from "react";
import {
  AlertTriangle,
  Check,
  ChevronDown,
  ChevronRight,
  ExternalLink,
  Loader2,
} from "lucide-react";

import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Switch } from "@/components/ui/switch";
import { PanelState } from "@/components/terminal/PanelState";
import {
  CATEGORY_LABEL,
  LICENCE_LABEL,
  type Connector,
  type ConnectorCategory,
} from "@/lib/api/connectors";
import {
  useConnectors,
  useLinkConnector,
  useSetConnectorEnabled,
  useTestConnector,
  useUnlinkConnector,
} from "@/hooks/useConnectors";
import { cn } from "@/lib/utils";

/**
 * Settings → Connectors (Connectors roadmap §1): every data source Ananke can read, grouped by
 * category, with its licence, rate limits and the Copilot tools it adds once linked. Keys are
 * tested live before they are saved and are never shown again (last-four hint only).
 */
export function ConnectorsPanel({ bodyClassName }: { bodyClassName?: string }) {
  const q = useConnectors();

  if (q.isError) {
    return (
      <div className="p-5">
        <PanelState
          kind="error"
          art="plug"
          message="Couldn't load connectors"
          detail={[
            q.error instanceof Error ? q.error.message : "Unknown error",
            "Is the Ananke backend running? It serves /api/connectors.",
          ]}
        />
      </div>
    );
  }
  if (!q.data) {
    return (
      <div
        className={cn("flex items-center justify-center text-text-muted", bodyClassName ?? "h-80")}
      >
        <Loader2 className="h-4 w-4 animate-spin" />
      </div>
    );
  }
  if (q.data.length === 0) {
    return (
      <div className="p-5">
        <PanelState kind="empty" art="plug" message="This backend has no connectors yet." />
      </div>
    );
  }

  const groups = new Map<ConnectorCategory, Connector[]>();
  for (const c of q.data) groups.set(c.category, [...(groups.get(c.category) ?? []), c]);
  const ready = q.data.filter((c) => c.status === "READY");

  return (
    <div className={cn("overflow-y-auto", bodyClassName)} data-testid="connectors">
      {[...groups.entries()].map(([category, list]) => (
        <section key={category}>
          <h3 className="border-b border-border-subtle bg-surface-1 px-5 py-1.5 text-[10px] font-medium uppercase tracking-wider text-text-muted">
            {CATEGORY_LABEL[category] ?? category}
          </h3>
          <ul className="divide-y divide-border-subtle">
            {list.map((c) => (
              <ConnectorRow key={c.id} connector={c} />
            ))}
          </ul>
        </section>
      ))}
      <p className="border-t border-border-subtle px-5 py-3 text-[11px] text-text-muted">
        {ready.length === 0
          ? "No connector is linked yet, so Copilot answers from Ananke's own data only."
          : `Copilot can call ${ready.reduce((n, c) => n + c.tools.length, 0)} tools from ${ready
              .map((c) => c.name)
              .join(", ")}. Everything they return is treated as data, never as instructions.`}
      </p>
    </div>
  );
}

function StatusBadge({ c }: { c: Connector }) {
  const map = {
    READY: { text: "Connected", cls: "text-text-primary", Icon: Check },
    ERROR: { text: "Needs attention", cls: "text-warning", Icon: AlertTriangle },
    NOT_LINKED: { text: "Not linked", cls: "text-text-muted", Icon: null },
    DISABLED: { text: "Off", cls: "text-text-muted", Icon: null },
  } as const;
  const s = map[c.status];
  return (
    <span
      className={cn("inline-flex items-center gap-1 text-[11px]", s.cls)}
      data-testid={`connector-status-${c.id}`}
    >
      {s.Icon && <s.Icon className="h-3 w-3" />}
      {s.text}
    </span>
  );
}

function ConnectorRow({ connector: c }: { connector: Connector }) {
  const [open, setOpen] = useState(!c.linked && !c.keyless);
  const toggle = useSetConnectorEnabled();
  const switchId = `connector-${c.id}`;
  const licence = LICENCE_LABEL[c.licence];

  return (
    <li className="px-5 py-3" data-testid={`connector-${c.id}`}>
      <div className="flex items-start gap-3">
        <button
          type="button"
          onClick={() => setOpen((o) => !o)}
          className="mt-0.5 text-text-muted hover:text-text-primary"
          aria-expanded={open}
          aria-label={`${open ? "Hide" : "Show"} ${c.name} settings`}
        >
          {open ? (
            <ChevronDown className="h-3.5 w-3.5" />
          ) : (
            <ChevronRight className="h-3.5 w-3.5" />
          )}
        </button>

        <div className="min-w-0 flex-1">
          <div className="flex flex-wrap items-center gap-2">
            <label htmlFor={switchId} className="text-xs font-medium text-text-primary">
              {c.name}
            </label>
            <span
              title={licence?.detail}
              className="rounded border border-border-subtle bg-surface-1 px-1.5 py-px text-[10px] uppercase tracking-wider text-text-secondary"
            >
              {licence?.label ?? c.licence}
            </span>
            <StatusBadge c={c} />
          </div>
          <p className="mt-0.5 text-[11px] text-text-secondary">{c.description}</p>
          {c.status === "ERROR" && (c.lastError || c.lastTestMessage) && (
            <p className="mt-1 text-[11px] text-warning">{c.lastError ?? c.lastTestMessage}</p>
          )}
        </div>

        <div className="flex items-center gap-2">
          {toggle.isPending && <Loader2 className="h-3.5 w-3.5 animate-spin text-text-muted" />}
          <Switch
            id={switchId}
            checked={c.enabled}
            disabled={toggle.isPending || !c.linked}
            onCheckedChange={(enabled) => toggle.mutate({ id: c.id, enabled })}
            aria-label={`${c.enabled ? "Turn off" : "Turn on"} ${c.name}`}
            data-testid={`connector-switch-${c.id}`}
          />
        </div>
      </div>

      {open && <ConnectorDetail c={c} />}
    </li>
  );
}

function errText(e: unknown) {
  return e instanceof Error ? e.message : "Unknown error";
}

function ConnectorDetail({ c }: { c: Connector }) {
  const [values, setValues] = useState<Record<string, string>>({});
  const link = useLinkConnector();
  const unlink = useUnlinkConnector();
  const test = useTestConnector();
  const dirty = Object.values(values).some((v) => v.trim() !== "");
  const missing = c.fields.some((f) => !f.optional && !f.set && !values[f.key]?.trim());

  const save = () => link.mutate({ id: c.id, values }, { onSuccess: () => setValues({}) });

  const message = link.isError
    ? { ok: false, text: errText(link.error) }
    : test.isError
      ? { ok: false, text: errText(test.error) }
      : c.lastTestMessage
        ? { ok: c.lastTestOk !== false, text: c.lastTestMessage }
        : null;

  return (
    <div className="ml-6 mt-3 flex flex-col gap-3">
      {c.fields.length > 0 && (
        <form
          className="flex flex-col gap-2"
          onSubmit={(e) => {
            e.preventDefault();
            if (!missing) save();
          }}
        >
          {c.fields.map((f) => (
            <label key={f.key} className="flex flex-col gap-1">
              <span className="text-[11px] text-text-secondary">
                {f.label}
                {f.optional && <span className="text-text-muted"> (optional)</span>}
                {f.set && (
                  <span className="ml-2 font-mono text-text-muted">
                    {f.secret ? `saved ${f.hint ?? ""}` : `saved: ${f.hint ?? ""}`}
                  </span>
                )}
              </span>
              <Input
                type={f.secret ? "password" : "text"}
                autoComplete="off"
                spellCheck={false}
                value={values[f.key] ?? ""}
                onChange={(e) => setValues((v) => ({ ...v, [f.key]: e.target.value }))}
                placeholder={f.set ? "Leave blank to keep the saved value" : (f.placeholder ?? "")}
                className="h-8 max-w-md font-mono text-xs"
                data-testid={`connector-field-${c.id}-${f.key}`}
              />
              {f.help && <span className="text-[11px] text-text-muted">{f.help}</span>}
            </label>
          ))}
          <div className="flex flex-wrap items-center gap-2">
            <Button
              type="submit"
              size="sm"
              disabled={link.isPending || missing || (c.linked && !dirty)}
              data-testid={`connector-save-${c.id}`}
            >
              {link.isPending && <Loader2 className="h-3.5 w-3.5 animate-spin" />}
              {link.isPending ? "Testing…" : c.linked ? "Update & test" : "Link & test"}
            </Button>
            {c.linked && (
              <>
                <Button
                  type="button"
                  size="sm"
                  variant="outline"
                  disabled={test.isPending}
                  onClick={() => test.mutate(c.id)}
                  data-testid={`connector-test-${c.id}`}
                >
                  {test.isPending && <Loader2 className="h-3.5 w-3.5 animate-spin" />}
                  Test again
                </Button>
                <Button
                  type="button"
                  size="sm"
                  variant="ghost"
                  disabled={unlink.isPending}
                  onClick={() => unlink.mutate(c.id)}
                  data-testid={`connector-unlink-${c.id}`}
                >
                  Unlink
                </Button>
              </>
            )}
          </div>
        </form>
      )}

      {message && (
        <p
          role="status"
          className={cn("text-[11px]", message.ok ? "text-text-secondary" : "text-destructive")}
          data-testid={`connector-message-${c.id}`}
        >
          {message.text}
        </p>
      )}

      <dl className="grid grid-cols-[auto_1fr] gap-x-3 gap-y-1 text-[11px]">
        {c.attribution && (
          <>
            <dt className="text-text-muted">Attribution</dt>
            <dd className="text-text-secondary">{c.attribution}</dd>
          </>
        )}
        {c.limits.length > 0 && (
          <>
            <dt className="text-text-muted">Rate limit</dt>
            <dd className="font-mono text-text-secondary">{c.limits.join(" · ")}</dd>
          </>
        )}
        {c.homepage && (
          <>
            <dt className="text-text-muted">Docs</dt>
            <dd>
              <a
                href={c.homepage}
                target="_blank"
                rel="noreferrer"
                className="inline-flex items-center gap-1 text-accent-blue hover:underline"
              >
                {new URL(c.homepage).host}
                <ExternalLink className="h-3 w-3" />
              </a>
            </dd>
          </>
        )}
        <dt className="text-text-muted">Copilot tools</dt>
        <dd className="flex flex-col gap-0.5">
          {c.tools.map((t) => (
            <span key={t.name}>
              <span className="font-mono text-text-primary">{t.name}</span>
              <span className="text-text-muted"> · {t.description}</span>
            </span>
          ))}
        </dd>
      </dl>
    </div>
  );
}
