import { apiFetch } from "./client";
import { marketDataApi } from "./marketdata";
import type { BackfillJob, CoverageBlock, CoverageBlockResponse, PriceBar } from "./types";
import { assetClassOfKey } from "@/lib/instruments";

export const pricesApi = {
  latest: (symbol: string, hours = 1) =>
    apiFetch<PriceBar[]>(`/api/prices/${encodeURIComponent(symbol)}/latest`, {
      query: { hours },
    }),

  /**
   * 1-minute bars for a range. Equities keep their original endpoint; any
   * other asset class (crypto today) goes through /api/bars by canonical key
   * and comes back in the same PriceBar shape, so every chart, table and
   * correlation consumer works unchanged (multi-asset P2, issue #8).
   */
  range: (symbol: string, from: string, to: string) => {
    const assetClass = assetClassOfKey(symbol);
    if (assetClass !== null && assetClass !== "EQUITY") {
      return marketDataApi.barsAsPriceBars(symbol, from, to);
    }
    return apiFetch<PriceBar[]>(`/api/prices/${encodeURIComponent(symbol)}/range`, {
      query: { from, to },
    });
  },

  /**
   * §16: contiguous covered blocks within [from, to] (backend splits on holes
   * > 96h). Maps {fromTime,toTime,barCount} → {from,to,barCount,state:"covered"}
   * so the future per-block state field needs no caller change.
   */
  coverageBlocks: async (symbol: string, from: string, to: string): Promise<CoverageBlock[]> => {
    const raw = await apiFetch<CoverageBlockResponse[]>(
      `/api/prices/${encodeURIComponent(symbol)}/coverage-blocks`,
      { query: { from, to } },
    );
    return raw.map((b) => ({
      from: b.fromTime,
      to: b.toTime,
      barCount: b.barCount,
      state: "covered" as const,
    }));
  },

  fetchNow: () => apiFetch<void>("/api/prices/fetch", { method: "POST" }),

  backfill: (symbol: string, from: string, to: string) =>
    apiFetch<void>(`/api/prices/${encodeURIComponent(symbol)}/backfill`, {
      method: "POST",
      query: { from, to },
    }),

  backfillAsync: (symbol: string, from: string, to: string) =>
    apiFetch<BackfillJob>(`/api/prices/${encodeURIComponent(symbol)}/backfill/async`, {
      method: "POST",
      query: { from, to },
    }),

  getJob: (jobId: string) => apiFetch<BackfillJob>(`/api/prices/jobs/${jobId}`),

  listJobs: (symbol: string) => apiFetch<BackfillJob[]>(`/api/prices/jobs`, { query: { symbol } }),

  retryJob: (jobId: string) =>
    apiFetch<BackfillJob>(`/api/prices/jobs/${jobId}/retry`, { method: "POST" }),

  cancelJob: (jobId: string) =>
    apiFetch<void>(`/api/prices/jobs/${jobId}/cancel`, { method: "POST" }),
};
