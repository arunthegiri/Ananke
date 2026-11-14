import { createFileRoute, useNavigate } from "@tanstack/react-router";
import { useCallback, useEffect, useMemo, useState } from "react";
import { useQuery } from "@tanstack/react-query";
import { marketDataApi } from "@/lib/api/marketdata";
import { ApiError } from "@/lib/api/client";
import { assetClassOfKey, normalizeInstrumentKey } from "@/lib/instruments";
import {
  buildStraddle,
  DEFAULT_RATE,
  strikesAroundAtm,
  type StraddleRow,
} from "@/lib/options/chain";
import { etWallClock } from "@/lib/market-calendar";
import { fmtPrice } from "@/lib/format";
import { TerminalPanel } from "@/components/terminal/TerminalPanel";
import { UnderlineTabs } from "@/components/terminal/UnderlineTabs";
import { PanelState } from "@/components/terminal/PanelState";
import { TerminalTable } from "@/components/terminal/table/TerminalTable";
import { DensityProvider } from "@/components/terminal/table/density";
import {
  OPTION_CHAIN_GREEK_COLUMNS,
  OPTION_CHAIN_QUOTE_COLUMNS,
} from "@/components/terminal/table/presets/option-chain";
import { SegmentedControl } from "@/components/terminal/controls/SegmentedControl";
import { Input } from "@/components/ui/input";
import { cn } from "@/lib/utils";

type ChainView = "quotes" | "greeks";

interface OptionsSearch {
  /** Underlying, canonical equity key. */
  u?: string;
  /** Expiry, YYYY-MM-DD. Absent = nearest listed. */
  exp?: string;
  view?: ChainView;
  /** Strikes either side of ATM; absent = 10, "all" = every listed strike. */
  strikes?: "5" | "20" | "all";
}

const STRIKE_OPTIONS = [
  { value: "5", label: "±5" },
  { value: "10", label: "±10" },
  { value: "20", label: "±20" },
  { value: "all", label: "All" },
] as const;

export const Route = createFileRoute("/options")({
  // Rule 3: where you are (underlying, expiry, quotes/greeks) is the URL, so a
  // shared link lands on the same chain. Pure parsing only — no clock here.
  validateSearch: (search: Record<string, unknown>): OptionsSearch => {
    const u =
      typeof search.u === "string" && assetClassOfKey(search.u) === "EQUITY"
        ? normalizeInstrumentKey(search.u)
        : undefined;
    const exp =
      typeof search.exp === "string" && /^\d{4}-\d{2}-\d{2}$/.test(search.exp)
        ? search.exp
        : undefined;
    const view = search.view === "greeks" ? "greeks" : undefined;
    // TanStack's default search parser JSON-decodes values: "?strikes=5"
    // arrives as the NUMBER 5, so compare on the string form.
    const rawStrikes = String(search.strikes ?? "");
    const strikes =
      rawStrikes === "5" || rawStrikes === "20" || rawStrikes === "all" ? rawStrikes : undefined;
    return { u, exp, view, strikes };
  },
  head: () => ({ meta: [{ title: "Options — Ananke Trading" }] }),
  component: OptionsPage,
});

/** "Nov 20" from an ISO date, formatted in UTC so no runtime zone can shift the day. */
function fmtExpiry(iso: string): string {
  return new Date(`${iso}T00:00:00Z`).toLocaleDateString("en-US", {
    month: "short",
    day: "numeric",
    timeZone: "UTC",
  });
}

/** Calendar days from today (ET) to an expiry date. */
function daysTo(iso: string, nowMs: number): number {
  const today = etWallClock(nowMs).date;
  return Math.round(
    (Date.parse(`${iso}T00:00:00Z`) - Date.parse(`${today}T00:00:00Z`)) / 86_400_000,
  );
}

function errorMessage(e: unknown): string {
  if (e instanceof ApiError && e.status === 422) return e.message;
  if (e instanceof ApiError && e.status === 0) return "Backend unreachable.";
  return e instanceof Error ? e.message : "Request failed.";
}

function OptionsPage() {
  const search = Route.useSearch();
  const navigate = useNavigate({ from: "/options" });
  const view: ChainView = search.view ?? "quotes";

  // SSR rule 2: queries and anything clock-derived render only after mount;
  // the server paints the same shell the first client render does.
  const [mounted, setMounted] = useState(false);
  useEffect(() => setMounted(true), []);

  const [draft, setDraft] = useState(search.u ?? "");
  useEffect(() => setDraft(search.u ?? ""), [search.u]);
  const draftValid = draft.trim() !== "" && assetClassOfKey(draft) === "EQUITY";

  const expirationsQ = useQuery({
    queryKey: ["options", "expirations", search.u],
    queryFn: () => marketDataApi.optionExpirations(search.u!),
    enabled: mounted && !!search.u,
    staleTime: 10 * 60_000,
    retry: false,
  });

  const expirations = expirationsQ.data?.expirations ?? [];
  const expiry =
    search.exp && expirations.includes(search.exp) ? search.exp : (expirations[0] ?? undefined);

  const chainQ = useQuery({
    queryKey: ["options", "chain", search.u, expiry],
    queryFn: () => marketDataApi.optionChain(search.u!, expiry!),
    enabled: mounted && !!search.u && !!expiry,
    refetchInterval: 15_000,
    retry: false,
  });

  const chain = useMemo(() => {
    const d = chainQ.data;
    if (!d) return null;
    return buildStraddle(d.contracts, d.underlyingPrice, d.expiry, Date.parse(d.asOf));
  }, [chainQ.data]);

  const strikeWindow = search.strikes === "all" ? null : Number(search.strikes ?? "10");
  const visibleRows = useMemo(
    () => (chain ? strikesAroundAtm(chain.rows, strikeWindow) : []),
    [chain, strikeWindow],
  );

  const getRowId = useCallback((r: StraddleRow) => String(r.strike), []);

  const submit = (e: React.FormEvent) => {
    e.preventDefault();
    if (!draftValid) return;
    navigate({ search: { u: normalizeInstrumentKey(draft), view: search.view } });
  };

  const nowMs = mounted ? Date.now() : 0;
  const expiryTabs = expirations.map((iso) => ({
    id: iso,
    label: `${fmtExpiry(iso)} · ${daysTo(iso, nowMs)}d`,
  }));

  let body: React.ReactNode;
  if (!search.u) {
    body = (
      <PanelState
        kind="empty"
        art="search"
        message="Enter a stock or ETF to load its option chain."
      />
    );
  } else if (!mounted || expirationsQ.isLoading || (expiry && chainQ.isLoading)) {
    body = <PanelState kind="loading" art="table" message={`Loading ${search.u} options…`} />;
  } else if (expirationsQ.isError) {
    body = (
      <PanelState
        kind="error"
        art="warning"
        message={errorMessage(expirationsQ.error)}
        detail={["GET /api/options/expirations"]}
      />
    );
  } else if (expirations.length === 0) {
    body = <PanelState kind="empty" art="table" message={`No listed options for ${search.u}.`} />;
  } else if (chainQ.isError) {
    body = (
      <PanelState
        kind="error"
        art="warning"
        message={errorMessage(chainQ.error)}
        detail={["GET /api/options/chain"]}
      />
    );
  } else if (chain) {
    body = (
      <DensityProvider density="compact">
        <TerminalTable
          rows={visibleRows}
          columns={view === "greeks" ? OPTION_CHAIN_GREEK_COLUMNS : OPTION_CHAIN_QUOTE_COLUMNS}
          getRowId={getRowId}
          initialSorting={[{ id: "strike", desc: false }]}
          empty={<PanelState kind="empty" art="table" message="No contracts for this expiry." />}
        />
      </DensityProvider>
    );
  }

  const d = mounted ? chainQ.data : undefined;

  return (
    <div className="flex h-full flex-col overflow-hidden bg-background text-foreground">
      <div className="flex shrink-0 items-center gap-4 border-b border-border px-6 py-3">
        <h1 className="text-lg font-semibold">Options</h1>
        <form onSubmit={submit} className="flex items-center gap-2">
          <Input
            aria-label="Underlying symbol"
            placeholder="AAPL"
            value={draft}
            onChange={(e) => setDraft(e.target.value.toUpperCase())}
            className="h-8 w-28 font-mono text-xs"
          />
          <button
            type="submit"
            disabled={!draftValid}
            className="rounded bg-accent-blue/20 px-2.5 py-1 text-xs text-accent-blue disabled:opacity-40"
          >
            Load
          </button>
        </form>
        {d && (
          <div className="flex items-center gap-3 font-mono text-xs tabular">
            <span className="text-text-muted">{d.underlying}</span>
            <span>{d.underlyingPrice === null ? "—" : fmtPrice(d.underlyingPrice)}</span>
            <span
              className={cn(
                "rounded border px-1.5 py-0.5 text-[10px] uppercase tracking-wider",
                d.mode === "DELAYED"
                  ? "border-warning/30 bg-warning/10 text-warning"
                  : "border-bull/30 bg-bull/10 text-bull",
              )}
              title={`Source: ${d.source}, feed: ${d.feed}`}
            >
              {d.mode === "DELAYED" ? `Delayed · ${d.feed}` : "Real-time"}
            </span>
          </div>
        )}
      </div>

      {mounted && expiryTabs.length > 0 && (
        <div className="shrink-0 overflow-x-auto border-b border-border px-4">
          <UnderlineTabs
            tabs={expiryTabs}
            activeTab={expiry ?? ""}
            onTabChange={(id) => navigate({ search: { ...search, exp: id } })}
          />
        </div>
      )}

      <div className="flex min-h-0 flex-1 flex-col p-4">
        <TerminalPanel
          className="min-h-0 flex-1"
          tabs={[
            { id: "quotes", label: "Quotes" },
            { id: "greeks", label: "Greeks" },
          ]}
          activeTab={view}
          onTabChange={(id) =>
            navigate({ search: { ...search, view: id === "greeks" ? "greeks" : undefined } })
          }
          actions={
            <SegmentedControl
              ariaLabel="Strikes around at-the-money"
              options={STRIKE_OPTIONS}
              value={search.strikes ?? "10"}
              onValueChange={(v) =>
                navigate({
                  search: {
                    ...search,
                    strikes: v === "5" || v === "20" || v === "all" ? v : undefined,
                  },
                })
              }
            />
          }
        >
          {body}
        </TerminalPanel>
        {chain && d && (
          <p className="mt-2 font-mono text-[10px] text-text-muted">
            IV/greeks: {chain.counts.vendor} from {d.source}, {chain.counts.model} model (BSM from
            mid, r={(DEFAULT_RATE * 100).toFixed(0)}%), {chain.counts.none} unavailable · as of{" "}
            {new Date(d.asOf).toLocaleTimeString("en-US", { timeZone: "America/New_York" })} ET
          </p>
        )}
      </div>
    </div>
  );
}
