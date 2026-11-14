import { apiFetch } from "./client";
import type { PriceBar } from "./types";
import type { ChainContract } from "@/lib/options/chain";

/**
 * Multi-asset market data (P2, issue #8) — backend MarketDataController.
 * Keys ride in the query string ("BTC/USD" contains a slash).
 */

/** "DELAYED" means the UI must label the data as delayed (licensing, issue #8). */
export type DataMode = "REALTIME" | "DELAYED";

export interface BarsResponse {
  key: string;
  source: string;
  mode: DataMode;
  bars: {
    time: string;
    key: string;
    open: number | null;
    high: number | null;
    low: number | null;
    close: number;
    volume: number | null;
    vwap: number | null;
    tradeCount: number | null;
  }[];
}

export interface OptionExpirations {
  underlying: string;
  source: string;
  /** ISO dates, ascending. */
  expirations: string[];
}

export interface OptionChainResponse {
  underlying: string;
  expiry: string;
  underlyingPrice: number | null;
  source: string;
  /** Alpaca feed: "indicative" (free, delayed) or "opra". */
  feed: string;
  mode: DataMode;
  asOf: string;
  contracts: (Omit<ChainContract, "right"> & {
    right: "C" | "P";
    bidSize: number | null;
    askSize: number | null;
    prevClose: number | null;
    rho: number | null;
    quoteTime: string | null;
  })[];
}

export const marketDataApi = {
  bars: (key: string, from: string, to: string) =>
    apiFetch<BarsResponse>("/api/bars", { query: { key, from, to } }),

  /** Same rows as /api/prices/{symbol}/range, so chart code needs no second shape. */
  barsAsPriceBars: async (key: string, from: string, to: string): Promise<PriceBar[]> => {
    const res = await marketDataApi.bars(key, from, to);
    return res.bars.map((b) => ({
      time: b.time,
      symbol: b.key,
      open: b.open ?? b.close,
      high: b.high ?? b.close,
      low: b.low ?? b.close,
      close: b.close,
      volume: b.volume ?? 0,
      vwap: b.vwap,
      tradeCount: b.tradeCount,
    }));
  },

  optionExpirations: (underlying: string) =>
    apiFetch<OptionExpirations>("/api/options/expirations", { query: { underlying } }),

  optionChain: (underlying: string, expiry: string) =>
    apiFetch<OptionChainResponse>("/api/options/chain", { query: { underlying, expiry } }),
};
