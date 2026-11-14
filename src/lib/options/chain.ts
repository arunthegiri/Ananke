/**
 * Option chain → straddle rows (multi-asset P2, issue #8). Pure.
 *
 * One row per strike: calls on the left, puts on the right, every field a
 * primitive so the row crosses TerminalRow's memo boundary intact (rule 5).
 *
 * Vendor first, model second: Alpaca's free feed leaves IV/greeks empty for a
 * large share of contracts (61 of 194 populated on a live AAPL weekly). Where
 * the vendor is silent but a two-sided quote exists, IV is solved from the mid
 * and greeks follow from BSM, and the row records that it did so — the panel
 * reports vendor vs model counts rather than passing a model number off as a
 * quote.
 */

import { etWallToUtcMs } from "../market-calendar.ts";
import { bsm, impliedVol, type Right } from "./greeks.ts";

/** Flat risk-free assumption for model values. Good to ~±1 vol pt on short-dated equity options. */
export const DEFAULT_RATE = 0.04;

export interface ChainContract {
  key: string;
  right: Right;
  strike: number;
  bid: number | null;
  ask: number | null;
  last: number | null;
  volume: number | null;
  openInterest: number | null;
  iv: number | null;
  delta: number | null;
  gamma: number | null;
  theta: number | null;
  vega: number | null;
}

export type GreekSource = "vendor" | "model" | "none";

export interface ChainSide {
  key: string | null;
  bid: number | null;
  ask: number | null;
  last: number | null;
  volume: number | null;
  openInterest: number | null;
  iv: number | null;
  delta: number | null;
  gamma: number | null;
  theta: number | null;
  vega: number | null;
  source: GreekSource;
}

export interface StraddleRow {
  strike: number;
  /** Closest strike to spot (one row per chain, null spot → none). */
  atm: boolean;
  // Flattened (primitives only) — `c*` call side, `p*` put side.
  cKey: string | null;
  cBid: number | null;
  cAsk: number | null;
  cLast: number | null;
  cVolume: number | null;
  cOi: number | null;
  cIv: number | null;
  cDelta: number | null;
  cGamma: number | null;
  cTheta: number | null;
  cVega: number | null;
  cSource: GreekSource;
  pKey: string | null;
  pBid: number | null;
  pAsk: number | null;
  pLast: number | null;
  pVolume: number | null;
  pOi: number | null;
  pIv: number | null;
  pDelta: number | null;
  pGamma: number | null;
  pTheta: number | null;
  pVega: number | null;
  pSource: GreekSource;
}

/** Years from `nowMs` to the 16:00 ET close on the expiry date (365-day basis). */
export function yearsToExpiry(expiry: string, nowMs: number): number {
  return Math.max(0, (etWallToUtcMs(expiry, 16, 0) - nowMs) / (365 * 86_400_000));
}

const EMPTY_SIDE: ChainSide = {
  key: null,
  bid: null,
  ask: null,
  last: null,
  volume: null,
  openInterest: null,
  iv: null,
  delta: null,
  gamma: null,
  theta: null,
  vega: null,
  source: "none",
};

export function enrichSide(
  c: ChainContract,
  spot: number | null,
  t: number,
  rate = DEFAULT_RATE,
): ChainSide {
  const base = {
    key: c.key,
    bid: c.bid,
    ask: c.ask,
    last: c.last,
    volume: c.volume,
    openInterest: c.openInterest,
  };
  if (c.iv !== null && c.delta !== null) {
    return {
      ...base,
      iv: c.iv,
      delta: c.delta,
      gamma: c.gamma,
      theta: c.theta,
      vega: c.vega,
      source: "vendor",
    };
  }
  // Model fallback needs a two-sided market and a spot; a one-sided or stale
  // print would solve to a confident-looking but meaningless vol.
  if (spot !== null && spot > 0 && t > 0 && c.bid !== null && c.ask !== null && c.ask >= c.bid) {
    const mid = (c.bid + c.ask) / 2;
    const iv = impliedVol(mid, { s: spot, k: c.strike, t, r: rate, right: c.right });
    if (iv !== null) {
      const g = bsm({ s: spot, k: c.strike, t, r: rate, sigma: iv, right: c.right });
      return {
        ...base,
        iv,
        delta: g.delta,
        gamma: g.gamma,
        theta: g.theta,
        vega: g.vega,
        source: "model",
      };
    }
  }
  return {
    ...base,
    iv: c.iv,
    delta: c.delta,
    gamma: c.gamma,
    theta: c.theta,
    vega: c.vega,
    source: c.iv !== null ? "vendor" : "none",
  };
}

function flatten(strike: number, atm: boolean, c: ChainSide, p: ChainSide): StraddleRow {
  return {
    strike,
    atm,
    cKey: c.key,
    cBid: c.bid,
    cAsk: c.ask,
    cLast: c.last,
    cVolume: c.volume,
    cOi: c.openInterest,
    cIv: c.iv,
    cDelta: c.delta,
    cGamma: c.gamma,
    cTheta: c.theta,
    cVega: c.vega,
    cSource: c.source,
    pKey: p.key,
    pBid: p.bid,
    pAsk: p.ask,
    pLast: p.last,
    pVolume: p.volume,
    pOi: p.openInterest,
    pIv: p.iv,
    pDelta: p.delta,
    pGamma: p.gamma,
    pTheta: p.theta,
    pVega: p.vega,
    pSource: p.source,
  };
}

export interface StraddleChain {
  rows: StraddleRow[];
  /** Strike closest to spot, or null without a spot. */
  atmStrike: number | null;
  counts: { vendor: number; model: number; none: number };
}

/** Build ascending-strike straddle rows. `nowMs` is passed in — never read here (SSR rule 2). */
export function buildStraddle(
  contracts: readonly ChainContract[],
  spot: number | null,
  expiry: string,
  nowMs: number,
  rate = DEFAULT_RATE,
): StraddleChain {
  const t = yearsToExpiry(expiry, nowMs);
  const byStrike = new Map<number, { c?: ChainContract; p?: ChainContract }>();
  for (const c of contracts) {
    const slot = byStrike.get(c.strike) ?? {};
    if (c.right === "C") slot.c = c;
    else slot.p = c;
    byStrike.set(c.strike, slot);
  }
  const strikes = [...byStrike.keys()].sort((a, b) => a - b);

  let atmStrike: number | null = null;
  if (spot !== null && strikes.length > 0) {
    atmStrike = strikes.reduce((best, k) =>
      Math.abs(k - spot) < Math.abs(best - spot) ? k : best,
    );
  }

  const counts = { vendor: 0, model: 0, none: 0 };
  const rows = strikes.map((strike) => {
    const slot = byStrike.get(strike)!;
    const c = slot.c ? enrichSide(slot.c, spot, t, rate) : EMPTY_SIDE;
    const p = slot.p ? enrichSide(slot.p, spot, t, rate) : EMPTY_SIDE;
    if (slot.c) counts[c.source]++;
    if (slot.p) counts[p.source]++;
    return flatten(strike, strike === atmStrike, c, p);
  });
  return { rows, atmStrike, counts };
}

/**
 * The `n` strikes either side of the ATM strike (inclusive of ATM), or all
 * rows when `n` is null or there is no ATM. A weekly AAPL chain lists ~100
 * strikes and opens deep in the money; the useful part is the middle.
 */
export function strikesAroundAtm(
  rows: readonly StraddleRow[],
  n: number | null,
): readonly StraddleRow[] {
  if (n === null) return rows;
  const i = rows.findIndex((r) => r.atm);
  if (i < 0) return rows;
  return rows.slice(Math.max(0, i - n), i + n + 1);
}
