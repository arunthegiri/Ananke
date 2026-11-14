import { useEffect, useMemo, useState } from "react";
import { Loader2 } from "lucide-react";

import { PanelState } from "@/components/terminal/PanelState";
import { TerminalTable } from "@/components/terminal/table/TerminalTable";
import { DensityProvider } from "@/components/terminal/table/density";
import { findRows, genericColumns } from "@/components/terminal/table/presets/generic";
import { etStamp } from "@/components/terminal/table/presets/sec";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { useConnectors } from "@/hooks/useConnectors";
import {
  CATEGORY_LABEL,
  connectorsApi,
  type ConnectorResult,
  type ConnectorToolInfo,
  type ToolParamSchema,
} from "@/lib/api/connectors";
import { cn } from "@/lib/utils";

/**
 * Sources explorer (Connectors roadmap §1): run any tool of any linked connector from a form
 * generated from its JSON schema — the same tools Copilot calls. Results render as a table
 * (columns derived from the data) with the raw JSON one click away.
 */
export function SourceExplorer({
  source,
  tool,
  onSelect,
}: {
  source?: string;
  tool?: string;
  onSelect: (p: { src?: string; tool?: string }) => void;
}) {
  const q = useConnectors();
  const ready = useMemo(() => (q.data ?? []).filter((c) => c.status === "READY"), [q.data]);
  const current = ready.find((c) => c.id === source) ?? ready[0];
  const currentTool = current?.tools.find((t) => t.name === tool) ?? current?.tools[0];

  if (q.isError) {
    return (
      <PanelState
        kind="error"
        art="plug"
        message="Couldn't reach the connector service."
        detail={["GET /api/connectors"]}
      />
    );
  }
  if (!q.data) return <PanelState kind="loading" art="table" message="Loading sources…" />;
  if (ready.length === 0) {
    return (
      <PanelState
        kind="pending"
        art="plug"
        message="No data source is linked and switched on yet."
        action={{ label: "Open Settings → Data connectors", href: "/settings?section=connectors" }}
      />
    );
  }

  return (
    <div className="flex h-full min-h-0" data-testid="source-explorer">
      <nav
        className="w-56 shrink-0 overflow-y-auto border-r border-border-subtle py-2"
        aria-label="Sources"
      >
        {ready.map((c) => (
          <div key={c.id} className="mb-1">
            <button
              type="button"
              onClick={() => onSelect({ src: c.id, tool: c.tools[0]?.name })}
              className={cn(
                "w-full px-3 py-1 text-left text-xs",
                c.id === current?.id
                  ? "text-text-primary"
                  : "text-text-secondary hover:text-text-primary",
              )}
            >
              {c.name}
              <span className="ml-1 text-[10px] text-text-muted">{CATEGORY_LABEL[c.category]}</span>
            </button>
            {c.id === current?.id &&
              c.tools.map((t) => (
                <button
                  key={t.name}
                  type="button"
                  onClick={() => onSelect({ src: c.id, tool: t.name })}
                  className={cn(
                    "block w-full truncate py-0.5 pl-6 pr-2 text-left font-mono text-[11px]",
                    t.name === currentTool?.name
                      ? "bg-surface-2 text-text-primary"
                      : "text-text-muted hover:text-text-secondary",
                  )}
                  title={t.description}
                  data-testid={`source-tool-${t.name}`}
                >
                  {t.name.slice(c.id.length + 1)}
                </button>
              ))}
          </div>
        ))}
      </nav>
      <div className="min-w-0 flex-1">
        {current && currentTool && (
          <ToolRunner key={currentTool.name} connectorId={current.id} tool={currentTool} />
        )}
      </div>
    </div>
  );
}

type Values = Record<string, string | boolean>;

function coerce(schema: ToolParamSchema, raw: string | boolean): unknown {
  if (typeof raw === "boolean") return raw;
  const v = raw.trim();
  if (v === "") return undefined;
  if (schema.type === "integer") return Number.parseInt(v, 10);
  if (schema.type === "number") return Number.parseFloat(v);
  if (schema.type === "array")
    return v
      .split(",")
      .map((s) => s.trim())
      .filter(Boolean);
  if (schema.type === "object") {
    try {
      return JSON.parse(v);
    } catch {
      return v;
    }
  }
  return v;
}

function ToolRunner({ connectorId, tool }: { connectorId: string; tool: ConnectorToolInfo }) {
  const props = tool.parameters?.properties ?? {};
  const required = new Set(tool.parameters?.required ?? []);
  const [values, setValues] = useState<Values>({});
  const [busy, setBusy] = useState(false);
  const [result, setResult] = useState<ConnectorResult<unknown> | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [raw, setRaw] = useState(false);
  useEffect(() => {
    setResult(null);
    setError(null);
  }, [tool.name]);

  const missing = [...required].filter((k) => {
    const v = values[k];
    return v === undefined || (typeof v === "string" && v.trim() === "");
  });

  const run = async () => {
    const args: Record<string, unknown> = {};
    for (const [k, s] of Object.entries(props)) {
      const v = values[k];
      if (v === undefined) continue;
      const c = coerce(s, v);
      if (c !== undefined && !(typeof c === "number" && Number.isNaN(c))) args[k] = c;
    }
    setBusy(true);
    setError(null);
    try {
      setResult(await connectorsApi.call<unknown>(connectorId, tool.name, args));
    } catch (e) {
      setResult(null);
      setError(e instanceof Error ? e.message : "Unknown error");
    } finally {
      setBusy(false);
    }
  };

  // Derived once per result: TerminalTable caches rows by identity and columns by reference.
  const table = useMemo(() => (result ? findRows(result.payload) : null), [result]);
  const columns = useMemo(() => (table ? genericColumns(table.rows) : []), [table]);

  return (
    <div className="flex h-full min-h-0 flex-col">
      <form
        className="flex flex-col gap-2 border-b border-border-subtle px-4 py-3"
        onSubmit={(e) => {
          e.preventDefault();
          if (missing.length === 0) void run();
        }}
      >
        <p className="text-[11px] text-text-secondary">{tool.description}</p>
        <div className="flex flex-wrap items-end gap-3">
          {Object.entries(props).map(([k, s]) => (
            <label key={k} className="flex flex-col gap-1" title={s.description}>
              <span className="text-[10px] uppercase tracking-wider text-text-muted">
                {k}
                {required.has(k) && <span className="text-warning"> *</span>}
              </span>
              {s.type === "boolean" ? (
                <input
                  type="checkbox"
                  checked={values[k] === true}
                  onChange={(e) => setValues((v) => ({ ...v, [k]: e.target.checked }))}
                  className="h-4 w-4"
                />
              ) : s.enum ? (
                <select
                  value={(values[k] as string) ?? ""}
                  onChange={(e) => setValues((v) => ({ ...v, [k]: e.target.value }))}
                  className="h-8 rounded-md border border-input bg-background px-2 text-xs text-foreground"
                >
                  <option value="">—</option>
                  {s.enum.map((o) => (
                    <option key={o} value={o}>
                      {o}
                    </option>
                  ))}
                </select>
              ) : (
                <Input
                  value={(values[k] as string) ?? ""}
                  onChange={(e) => setValues((v) => ({ ...v, [k]: e.target.value }))}
                  placeholder={
                    s.type === "array" ? "a, b, c" : s.type === "integer" ? "number" : ""
                  }
                  className={cn(
                    "h-8 font-mono text-xs",
                    s.type === "integer" || s.type === "number" ? "w-24" : "w-48",
                  )}
                  data-testid={`source-arg-${k}`}
                />
              )}
            </label>
          ))}
          <Button
            type="submit"
            size="sm"
            className="h-8 text-xs"
            disabled={busy || missing.length > 0}
            data-testid="source-run"
          >
            {busy && <Loader2 className="h-3.5 w-3.5 animate-spin" />}
            Run
          </Button>
        </div>
      </form>

      <div className="min-h-0 flex-1">
        {error ? (
          <PanelState kind="error" art="warning" message={error} />
        ) : busy ? (
          <PanelState kind="loading" art="table" message="Fetching…" />
        ) : !result ? (
          <PanelState
            kind="empty"
            art="search"
            message="Fill in the arguments and run the tool — Copilot calls these same tools."
          />
        ) : table && !raw ? (
          <DensityProvider>
            <TerminalTable
              rows={table.rows}
              columns={columns}
              getRowId={(r) => r.__id}
              className="h-full"
            />
          </DensityProvider>
        ) : (
          <pre
            className="h-full overflow-auto px-4 py-3 font-mono text-[11px] text-text-secondary"
            data-testid="source-json"
          >
            {JSON.stringify(result.payload, null, 2)}
          </pre>
        )}
      </div>

      {result && (
        <div className="flex flex-wrap items-center gap-x-3 border-t border-border-subtle px-3 py-1.5 text-[11px] text-text-muted">
          {table && (
            <button
              type="button"
              className="text-accent-blue hover:underline"
              onClick={() => setRaw((r) => !r)}
            >
              {raw ? "Table" : "Raw JSON"}
            </button>
          )}
          {table?.key && (
            <span className="font-mono">
              {table.key}: {table.rows.length} rows
            </span>
          )}
          <span>{result.attribution ?? result.source}</span>
          <span className="font-mono">
            fetched {etStamp(result.observedAt)} ET{result.cached ? " · cached" : ""}
          </span>
        </div>
      )}
    </div>
  );
}
