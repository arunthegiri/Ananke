import type { StraddleRow } from "@/lib/options/chain";
import type { CellRender } from "../cells";
import type { ColumnPreset } from "../presets";

/**
 * Option chain straddle columns (multi-asset P2, issue #8): calls | strike | puts.
 * Two presets over the same row — quotes and greeks — switched by an
 * UnderlineTabs in the route, never a second table component (rule 5).
 *
 * Greeks are in Alpaca's units (theta per day, vega per vol point — see
 * lib/options/greeks.ts).
 */

const W = 64;

/**
 * IV and greeks are not directional, so they render as neutral mono text
 * rather than `pct`/`num` cells (which colour by sign and fix 2dp — gamma
 * would read 0.00 on every row). Precision per field is what a chain reader
 * actually compares: Δ 2dp, Γ 4dp, Θ/vega 3dp, IV 1dp.
 */
const MINUS = "\u2212";
function fixed(dp: number) {
  return (v: number | null): CellRender => ({
    type: "text",
    value: v === null ? "—" : v.toFixed(dp).replace("-", MINUS),
    mono: true,
  });
}
const iv = (v: number | null): CellRender => ({
  type: "text",
  value: v === null ? "—" : `${(v * 100).toFixed(1)}%`,
  mono: true,
});
const d2 = fixed(2);
const d3 = fixed(3);
const d4 = fixed(4);
const price = (v: number | null): CellRender => ({ type: "num", value: v, kind: "price" });
const size = (v: number | null): CellRender => ({ type: "num", value: v, kind: "size" });

function col(
  id: string,
  header: string,
  pick: (r: StraddleRow) => number | null,
  render: (v: number | null) => CellRender,
  width = W,
): ColumnPreset<StraddleRow> {
  return { id, header, width, align: "right", cell: (r) => render(pick(r)), sortValue: pick };
}

const STRIKE: ColumnPreset<StraddleRow> = {
  id: "strike",
  header: "Strike",
  width: 84,
  align: "right",
  // The at-the-money strike is the one landmark a chain reader scans for.
  cell: (r) =>
    r.atm
      ? { type: "badge", value: fmtStrike(r.strike), tone: "info" }
      : { type: "text", value: fmtStrike(r.strike), mono: true, strong: true },
  sortValue: (r) => r.strike,
};

function fmtStrike(k: number): string {
  return Number.isInteger(k) ? k.toFixed(0) : String(k);
}

export const OPTION_CHAIN_QUOTE_COLUMNS: readonly ColumnPreset<StraddleRow>[] = [
  col("cOi", "C OI", (r) => r.cOi, size),
  col("cVol", "C Vol", (r) => r.cVolume, size),
  col("cIv", "C IV", (r) => r.cIv, iv, 72),
  col("cDelta", "C Δ", (r) => r.cDelta, d2, 56),
  col("cBid", "C Bid", (r) => r.cBid, price, 72),
  col("cAsk", "C Ask", (r) => r.cAsk, price, 72),
  STRIKE,
  col("pBid", "P Bid", (r) => r.pBid, price, 72),
  col("pAsk", "P Ask", (r) => r.pAsk, price, 72),
  col("pDelta", "P Δ", (r) => r.pDelta, d2, 56),
  col("pIv", "P IV", (r) => r.pIv, iv, 72),
  col("pVol", "P Vol", (r) => r.pVolume, size),
  col("pOi", "P OI", (r) => r.pOi, size),
];

export const OPTION_CHAIN_GREEK_COLUMNS: readonly ColumnPreset<StraddleRow>[] = [
  col("cVega", "C Vega", (r) => r.cVega, d3, 72),
  col("cTheta", "C Θ", (r) => r.cTheta, d3, 72),
  col("cGamma", "C Γ", (r) => r.cGamma, d4, 72),
  col("cDelta", "C Δ", (r) => r.cDelta, d2, 56),
  col("cIv", "C IV", (r) => r.cIv, iv, 72),
  STRIKE,
  col("pIv", "P IV", (r) => r.pIv, iv, 72),
  col("pDelta", "P Δ", (r) => r.pDelta, d2, 56),
  col("pGamma", "P Γ", (r) => r.pGamma, d4, 72),
  col("pTheta", "P Θ", (r) => r.pTheta, d3, 72),
  col("pVega", "P Vega", (r) => r.pVega, d3, 72),
];
