import { createFileRoute, useNavigate } from "@tanstack/react-router";
import { useEffect, useMemo, useState } from "react";

import { TerminalPanel } from "@/components/terminal/TerminalPanel";
import { PanelState } from "@/components/terminal/PanelState";
import { TerminalTable } from "@/components/terminal/table/TerminalTable";
import { DensityProvider } from "@/components/terminal/table/density";
import {
  SEC_FILING_COLUMNS,
  SEC_FINANCIAL_COLUMNS,
  SEC_INSIDER_COLUMNS,
  SEC_SEARCH_COLUMNS,
  etStamp,
} from "@/components/terminal/table/presets/sec";
import { Input } from "@/components/ui/input";
import { Button } from "@/components/ui/button";
import { useConnector, useConnectorCall } from "@/hooks/useConnectors";
import type { ConnectorResult } from "@/lib/api/connectors";
import {
  SEC_CONCEPTS,
  SEC_CONNECTOR,
  type SecFilingsPayload,
  type SecFinancialsPayload,
  type SecInsiderPayload,
  type SecSearchPayload,
} from "@/lib/api/sec";
import { cn } from "@/lib/utils";

/**
 * /research — company research from linked data connectors (Connectors roadmap §2, issue #10).
 * SEC EDGAR first: filings, insider trades, reported financials and full-text search. The same
 * tools are Copilot's, so what you read here is what Copilot can cite.
 */

const TABS = [
  { id: "filings", label: "Filings" },
  { id: "insiders", label: "Insider trades" },
  { id: "financials", label: "Financials" },
  { id: "search", label: "Filing search" },
] as const;

type TabId = (typeof TABS)[number]["id"];

interface ResearchSearch {
  /** Ticker, upper-case. Absent = none chosen. */
  symbol?: string;
  tab: TabId;
  /** Financials concept (XBRL name). */
  concept?: string;
  /** Full-text search query. */
  q?: string;
}

const TICKER = /^[A-Z0-9.-]{1,10}$/;

export const Route = createFileRoute("/research")({
  // URL = where you are: a shared link lands on the same company, tab and query.
  validateSearch: (s: Record<string, unknown>): ResearchSearch => {
    const sym = typeof s.symbol === "string" ? s.symbol.trim().toUpperCase() : "";
    return {
      symbol: TICKER.test(sym) ? sym : undefined,
      tab: TABS.some((t) => t.id === s.tab) ? (s.tab as TabId) : "filings",
      concept:
        typeof s.concept === "string" && /^[A-Za-z0-9_]{2,200}$/.test(s.concept)
          ? s.concept
          : undefined,
      q: typeof s.q === "string" && s.q.trim() ? s.q.slice(0, 500) : undefined,
    };
  },
  head: () => ({ meta: [{ title: "Research — Ananke Trading" }] }),
  component: ResearchPage,
});

function ResearchPage() {
  const search = Route.useSearch();
  const navigate = useNavigate({ from: "/research" });
  const set = (patch: Partial<ResearchSearch>) =>
    void navigate({ search: (prev: ResearchSearch) => ({ ...prev, ...patch }) });

  // Client-only data: the connector catalogue and tool calls never render during SSR, so the
  // first client render must match the server's skeleton.
  const [mounted, setMounted] = useState(false);
  useEffect(() => setMounted(true), []);

  const [draft, setDraft] = useState(search.symbol ?? "");
  useEffect(() => setDraft(search.symbol ?? ""), [search.symbol]);

  return (
    <div className="flex h-full min-h-0 flex-col gap-3 p-3" data-testid="research-page">
      <form
        className="flex items-center gap-2 rounded-md border border-border bg-card p-3"
        onSubmit={(e) => {
          e.preventDefault();
          const t = draft.trim().toUpperCase();
          if (TICKER.test(t)) set({ symbol: t });
        }}
      >
        <label
          htmlFor="research-ticker"
          className="text-[10px] uppercase tracking-wider text-text-muted"
        >
          Ticker
        </label>
        <Input
          id="research-ticker"
          value={draft}
          onChange={(e) => setDraft(e.target.value)}
          placeholder="AAPL"
          spellCheck={false}
          autoComplete="off"
          className="h-8 w-32 font-mono text-xs uppercase"
          data-testid="research-ticker"
        />
        <Button type="submit" size="sm" className="h-8 text-xs" data-testid="research-go">
          Load
        </Button>
        <span className="ml-auto text-[11px] text-text-muted">
          SEC EDGAR · filings, insiders, financials. More sources arrive as connectors are linked.
        </span>
      </form>

      <TerminalPanel
        tabs={TABS.map((t) => ({ id: t.id, label: t.label }))}
        activeTab={search.tab}
        onTabChange={(id) => set({ tab: id as TabId })}
        className="min-h-0 flex-1"
        bodyClassName="min-h-0"
      >
        {mounted ? <ResearchBody search={search} set={set} /> : <BodySkeleton />}
      </TerminalPanel>
    </div>
  );
}

function BodySkeleton() {
  return <PanelState kind="loading" art="table" message="Loading research…" />;
}

function ResearchBody({
  search,
  set,
}: {
  search: ResearchSearch;
  set: (p: Partial<ResearchSearch>) => void;
}) {
  const { connector, isError, error, isLoading } = useConnector(SEC_CONNECTOR);

  if (isLoading) return <BodySkeleton />;
  if (isError || !connector) {
    return (
      <PanelState
        kind="error"
        art="plug"
        message={
          isError
            ? "Couldn't reach the connector service."
            : "This backend has no SEC EDGAR connector."
        }
        detail={[error instanceof Error ? error.message : "GET /api/connectors"]}
      />
    );
  }
  if (connector.status === "NOT_LINKED" || connector.status === "DISABLED") {
    return (
      <PanelState
        kind="pending"
        art="plug"
        message={
          connector.status === "DISABLED"
            ? "SEC EDGAR is switched off. Turn it on in Settings → Data connectors."
            : "Link SEC EDGAR to read filings here. It's free: the SEC only asks for your name and email."
        }
        action={{ label: "Open Settings → Data connectors", href: "/settings?section=connectors" }}
        detail={["POST /api/connectors/sec_edgar/tools/*"]}
      />
    );
  }

  if (search.tab === "search") return <SearchTab search={search} set={set} />;
  if (!search.symbol) {
    return (
      <PanelState kind="empty" art="search" message="Enter a ticker to load its SEC record." />
    );
  }
  if (search.tab === "insiders") return <InsidersTab symbol={search.symbol} />;
  if (search.tab === "financials") return <FinancialsTab search={search} set={set} />;
  return <FilingsTab symbol={search.symbol} />;
}

/** Shared states for one tool call; renders `children` only once data is in. */
function CallState<T>({
  q,
  what,
  children,
}: {
  q: ReturnType<typeof useConnectorCall<T>>;
  what: string;
  children: (r: ConnectorResult<T>) => React.ReactNode;
}) {
  if (q.isError) {
    return (
      <PanelState
        kind="error"
        art="warning"
        message={q.error instanceof Error ? q.error.message : `Couldn't load ${what}.`}
        action={{ label: "Retry", onClick: () => void q.refetch() }}
      />
    );
  }
  if (!q.data) return <PanelState kind="loading" art="table" message={`Loading ${what}…`} />;
  return <>{children(q.data)}</>;
}

function SourceLine<T>({ r, extra }: { r: ConnectorResult<T>; extra?: string }) {
  return (
    <div
      className="flex flex-wrap items-center gap-x-3 border-t border-border-subtle px-3 py-1.5 text-[11px] text-text-muted"
      data-testid="research-source"
    >
      {extra && <span className="text-text-secondary">{extra}</span>}
      <span>{r.attribution ?? r.source}</span>
      <span className="font-mono">
        fetched {etStamp(r.observedAt)} ET{r.cached ? " · cached" : ""}
      </span>
    </div>
  );
}

function FilingsTab({ symbol }: { symbol: string }) {
  const args = useMemo(() => ({ ticker: symbol, limit: 50 }), [symbol]);
  const q = useConnectorCall<SecFilingsPayload>(SEC_CONNECTOR, "sec_edgar_filings", args, true);
  return (
    <CallState q={q} what={`${symbol} filings`}>
      {(r) => (
        <div className="flex h-full min-h-0 flex-col">
          <DensityProvider>
            <TerminalTable
              rows={r.payload.filings}
              columns={SEC_FILING_COLUMNS}
              getRowId={(f) => f.accession}
              className="min-h-0 flex-1"
              empty={<PanelState kind="empty" art="table" message="No recent filings." />}
            />
          </DensityProvider>
          <SourceLine r={r} extra={`${r.payload.company} · CIK ${r.payload.cik}`} />
        </div>
      )}
    </CallState>
  );
}

function InsidersTab({ symbol }: { symbol: string }) {
  const args = useMemo(() => ({ ticker: symbol, limit: 15 }), [symbol]);
  const q = useConnectorCall<SecInsiderPayload>(
    SEC_CONNECTOR,
    "sec_edgar_insider_trades",
    args,
    true,
  );
  return (
    <CallState q={q} what={`${symbol} insider trades`}>
      {(r) => {
        const rows = r.payload.transactions.map((t, i) => ({ ...t, id: `${t.url}#${i}` }));
        return (
          <div className="flex h-full min-h-0 flex-col">
            <DensityProvider>
              <TerminalTable
                rows={rows}
                columns={SEC_INSIDER_COLUMNS}
                getRowId={(t) => t.id}
                className="min-h-0 flex-1"
                empty={
                  <PanelState
                    kind="empty"
                    art="table"
                    message="No Form 4 transactions in the latest filings."
                  />
                }
              />
            </DensityProvider>
            <SourceLine
              r={r}
              extra={`${r.payload.company} · last ${r.payload.filings_read} Form 4 filings`}
            />
          </div>
        );
      }}
    </CallState>
  );
}

function FinancialsTab({
  search,
  set,
}: {
  search: ResearchSearch;
  set: (p: Partial<ResearchSearch>) => void;
}) {
  const concept = search.concept ?? "NetIncomeLoss";
  const preset = SEC_CONCEPTS.find((c) => c.id === concept);
  const args = useMemo(
    () => ({
      ticker: search.symbol,
      concept,
      unit: preset && "unit" in preset ? preset.unit : "USD",
      period: "annual",
      limit: 12,
    }),
    [search.symbol, concept, preset],
  );
  const q = useConnectorCall<SecFinancialsPayload>(
    SEC_CONNECTOR,
    "sec_edgar_financials",
    args,
    true,
  );
  return (
    <div className="flex h-full min-h-0 flex-col">
      <div className="flex flex-wrap gap-1 border-b border-border-subtle px-3 py-2">
        {SEC_CONCEPTS.map((c) => (
          <button
            key={c.id}
            type="button"
            onClick={() => set({ concept: c.id })}
            className={cn(
              "rounded px-2 py-0.5 text-[11px]",
              c.id === concept
                ? "bg-surface-3 text-text-primary"
                : "text-text-secondary hover:bg-surface-2",
            )}
          >
            {c.label}
          </button>
        ))}
      </div>
      <div className="min-h-0 flex-1">
        <CallState q={q} what={`${search.symbol} ${preset?.label ?? concept}`}>
          {(r) => (
            <div className="flex h-full min-h-0 flex-col">
              <DensityProvider>
                <TerminalTable
                  rows={r.payload.values}
                  columns={SEC_FINANCIAL_COLUMNS}
                  getRowId={(v) => `${v.start ?? ""}|${v.end}`}
                  className="min-h-0 flex-1"
                  empty={
                    <PanelState kind="empty" art="table" message="No annual values reported." />
                  }
                />
              </DensityProvider>
              <SourceLine
                r={r}
                extra={`${r.payload.label ?? r.payload.concept} (${r.payload.unit}) · first-reported values; "Public" is when each became known`}
              />
            </div>
          )}
        </CallState>
      </div>
    </div>
  );
}

function SearchTab({
  search,
  set,
}: {
  search: ResearchSearch;
  set: (p: Partial<ResearchSearch>) => void;
}) {
  const [draft, setDraft] = useState(search.q ?? "");
  const args = useMemo(
    () => ({ query: search.q, ticker: search.symbol, limit: 50 }),
    [search.q, search.symbol],
  );
  const q = useConnectorCall<SecSearchPayload>(SEC_CONNECTOR, "sec_edgar_search", args, !!search.q);
  return (
    <div className="flex h-full min-h-0 flex-col">
      <form
        className="flex items-center gap-2 border-b border-border-subtle px-3 py-2"
        onSubmit={(e) => {
          e.preventDefault();
          if (draft.trim()) set({ q: draft.trim() });
        }}
      >
        <Input
          value={draft}
          onChange={(e) => setDraft(e.target.value)}
          placeholder={'"supply chain" AND tariff'}
          className="h-8 max-w-lg flex-1 font-mono text-xs"
          data-testid="research-search-input"
        />
        <Button type="submit" size="sm" className="h-8 text-xs">
          Search
        </Button>
        <span className="text-[11px] text-text-muted">
          {search.symbol ? `within ${search.symbol}` : "all companies"} · full text since 2001
        </span>
      </form>
      <div className="min-h-0 flex-1">
        {!search.q ? (
          <PanelState
            kind="empty"
            art="search"
            message="Search every EDGAR filing and exhibit for a phrase, product, supplier or risk."
          />
        ) : (
          <CallState q={q} what="search results">
            {(r) => (
              <div className="flex h-full min-h-0 flex-col">
                <DensityProvider>
                  <TerminalTable
                    rows={r.payload.hits.map((h, i) => ({ ...h, id: `${h.accession}#${i}` }))}
                    columns={SEC_SEARCH_COLUMNS}
                    getRowId={(h) => h.id}
                    className="min-h-0 flex-1"
                    empty={<PanelState kind="empty" art="search" message="No filings match." />}
                  />
                </DensityProvider>
                <SourceLine r={r} extra={`${r.payload.total.toLocaleString()} matches`} />
              </div>
            )}
          </CallState>
        )}
      </div>
    </div>
  );
}
