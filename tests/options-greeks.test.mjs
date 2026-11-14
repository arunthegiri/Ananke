/**
 * Multi-asset P2 unit test — option pricing, greeks, IV, chain building (issue #8).
 *
 * Reference values: Hull, "Options, Futures, and Other Derivatives",
 * Example 15.6 (S=42, K=40, r=10%, σ=20%, T=0.5 → call 4.76, put 0.81) and
 * Example 18.x Black-76 (F=20, K=20, r=9%, σ=25%, T=4/12 → put 1.1166).
 *
 * Run: npm run test:options
 */

import assert from "node:assert/strict";
import { black76, bsm, impliedVol, normCdf } from "../src/lib/options/greeks.ts";
import {
  buildStraddle,
  enrichSide,
  strikesAroundAtm,
  yearsToExpiry,
} from "../src/lib/options/chain.ts";
import { etWallToUtcMs } from "../src/lib/market-calendar.ts";

const close = (a, b, tol, msg) =>
  assert.ok(Math.abs(a - b) <= tol, `${msg}: ${a} vs ${b} (tol ${tol})`);

// ── normCdf accuracy ────────────────────────────────────────────────────────
close(normCdf(0), 0.5, 1e-15, "N(0)");
close(normCdf(1.96), 0.9750021048517795, 1e-13, "N(1.96)");
close(normCdf(-3), 0.0013498980316301, 1e-14, "N(-3)");
close(normCdf(-8), 6.22096057427178e-16, 1e-20, "N(-8) deep tail");
close(normCdf(0.3), 0.6179114221889527, 1e-14, "N(0.3) series region");

// ── BSM reference (Hull 15.6) ───────────────────────────────────────────────
const hull = { s: 42, k: 40, t: 0.5, r: 0.1, sigma: 0.2 };
const call = bsm({ ...hull, right: "C" });
const put = bsm({ ...hull, right: "P" });
close(call.price, 4.7594, 1e-4, "Hull call");
close(put.price, 0.8086, 1e-4, "Hull put");
// Put-call parity: C − P = S − K e^{−rT}
close(call.price - put.price, 42 - 40 * Math.exp(-0.1 * 0.5), 1e-12, "parity");
// Greek identities.
close(call.delta - put.delta, 1, 1e-12, "Δc − Δp = 1 (q=0)");
close(call.gamma, put.gamma, 1e-15, "Γ equal");
close(call.vega, put.vega, 1e-15, "vega equal");

// Greeks against finite differences of the price (validates the units).
const h = 1e-4;
const px = (o) => bsm({ ...hull, right: "C", ...o }).price;
close(call.delta, (px({ s: 42 + h }) - px({ s: 42 - h })) / (2 * h), 1e-7, "delta FD");
close(
  call.gamma,
  (px({ s: 42 + h }) - 2 * call.price + px({ s: 42 - h })) / (h * h),
  1e-4,
  "gamma FD",
);
close(
  call.vega,
  (px({ sigma: 0.2 + h }) - px({ sigma: 0.2 - h })) / (2 * h) / 100,
  1e-8,
  "vega per vol pt",
);
close(call.rho, (px({ r: 0.1 + h }) - px({ r: 0.1 - h })) / (2 * h) / 100, 1e-8, "rho per rate pt");
close(
  call.theta,
  -((px({ t: 0.5 + h }) - px({ t: 0.5 - h })) / (2 * h)) / 365,
  1e-8,
  "theta per day",
);

// Dividend yield lowers the call.
assert.ok(bsm({ ...hull, q: 0.03, right: "C" }).price < call.price);

// ── Black-76 reference ──────────────────────────────────────────────────────
const b76 = black76({ s: 20, k: 20, t: 4 / 12, r: 0.09, sigma: 0.25, right: "P" });
close(b76.price, 1.1166, 1e-4, "Black-76 put");
const b76c = black76({ s: 20, k: 20, t: 4 / 12, r: 0.09, sigma: 0.25, right: "C" });
close(b76c.price, b76.price, 1e-12, "ATM Black-76 call = put");

// ── Expiry / zero vol → intrinsic ───────────────────────────────────────────
assert.deepEqual(bsm({ s: 105, k: 100, t: 0, r: 0.05, sigma: 0.3, right: "C" }), {
  price: 5,
  delta: 1,
  gamma: 0,
  theta: 0,
  vega: 0,
  rho: 0,
});
assert.equal(bsm({ s: 95, k: 100, t: 0, r: 0.05, sigma: 0.3, right: "C" }).price, 0);
assert.throws(() => bsm({ s: 0, k: 100, t: 1, r: 0, sigma: 0.2, right: "C" }), RangeError);

// ── Implied vol ─────────────────────────────────────────────────────────────
for (const sigma of [0.05, 0.2, 0.6, 1.5]) {
  for (const k of [30, 42, 60]) {
    for (const right of ["C", "P"]) {
      const inp = { s: 42, k, t: 0.25, r: 0.04, right };
      const p = bsm({ ...inp, sigma }).price;
      // Skip contracts whose time value is too small to carry vol information
      // (deep OTM ≈ 0, deep ITM ≈ intrinsic) — the solver returns null there by design.
      const lower =
        right === "C"
          ? Math.max(42 - k * Math.exp(-0.04 * 0.25), 0)
          : Math.max(k * Math.exp(-0.04 * 0.25) - 42, 0);
      if (p - lower < 1e-6) continue;
      close(impliedVol(p, inp), sigma, 1e-6, `IV round-trip σ=${sigma} k=${k} ${right}`);
    }
  }
}
close(
  impliedVol(b76.price, { s: 20, k: 20, t: 4 / 12, r: 0.09, right: "P" }, "black76"),
  0.25,
  1e-8,
  "B76 IV",
);
// No-arbitrage violations have no IV.
assert.equal(impliedVol(1, { s: 42, k: 30, t: 0.5, r: 0.04, right: "C" }), null, "below intrinsic");
assert.equal(impliedVol(43, { s: 42, k: 30, t: 0.5, r: 0.04, right: "C" }), null, "above spot");
assert.equal(impliedVol(1, { s: 42, k: 40, t: 0, r: 0.04, right: "C" }), null, "expired");
assert.equal(impliedVol(0, { s: 42, k: 40, t: 1, r: 0.04, right: "C" }), null, "zero price");

// ── Chain building ──────────────────────────────────────────────────────────
// Friday 2026-11-20 16:00 ET is exactly 6 days after Saturday 16:00 ET (no DST change).
const now = etWallToUtcMs("2026-11-14", 16, 0);
close(yearsToExpiry("2026-11-20", now), 6 / 365, 1e-12, "years to expiry");
assert.equal(yearsToExpiry("2026-11-13", now), 0, "past expiry clamps to 0");

const t = 6 / 365;
const spot = 356.4;
const modelCall = bsm({ s: spot, k: 360, t, r: 0.04, sigma: 0.22, right: "C" }).price;
const contracts = [
  // vendor greeks present
  {
    key: "AAPL261120C00355000",
    right: "C",
    strike: 355,
    bid: 5.1,
    ask: 5.3,
    last: 5.2,
    volume: 10,
    openInterest: 100,
    iv: 0.27,
    delta: 0.53,
    gamma: 0.03,
    theta: -0.42,
    vega: 0.18,
  },
  {
    key: "AAPL261120P00355000",
    right: "P",
    strike: 355,
    bid: 3.4,
    ask: 3.6,
    last: 3.5,
    volume: 5,
    openInterest: 80,
    iv: 0.21,
    delta: -0.47,
    gamma: 0.04,
    theta: -0.3,
    vega: 0.18,
  },
  // no vendor greeks, two-sided quote → model
  {
    key: "AAPL261120C00360000",
    right: "C",
    strike: 360,
    bid: modelCall - 0.05,
    ask: modelCall + 0.05,
    last: null,
    volume: null,
    openInterest: 7,
    iv: null,
    delta: null,
    gamma: null,
    theta: null,
    vega: null,
  },
  // no vendor greeks, one-sided → none
  {
    key: "AAPL261120P00100000",
    right: "P",
    strike: 100,
    bid: null,
    ask: 0.03,
    last: 0.01,
    volume: 2000,
    openInterest: 2208,
    iv: null,
    delta: null,
    gamma: null,
    theta: null,
    vega: null,
  },
];
const chain = buildStraddle(contracts, spot, "2026-11-20", now);
assert.deepEqual(
  chain.rows.map((r) => r.strike),
  [100, 355, 360],
  "ascending strikes",
);
assert.equal(chain.atmStrike, 355);
assert.deepEqual(
  chain.rows.map((r) => r.atm),
  [false, true, false],
);
assert.deepEqual(chain.counts, { vendor: 2, model: 1, none: 1 });

const r355 = chain.rows[1];
assert.equal(r355.cSource, "vendor");
assert.equal(r355.cDelta, 0.53, "vendor value passes through untouched");
assert.equal(r355.pKey, "AAPL261120P00355000");

const r360 = chain.rows[2];
assert.equal(r360.cSource, "model");
close(r360.cIv, 0.22, 1e-6, "model IV solved from mid");
assert.ok(r360.cDelta > 0 && r360.cDelta < 0.5, "OTM call delta");
assert.equal(r360.pKey, null, "missing put side is empty, not invented");
assert.equal(r360.pSource, "none");

assert.equal(chain.rows[0].pSource, "none", "one-sided quote gets no model vol");
assert.equal(chain.rows[0].cKey, null);

// No spot → no ATM, no model values.
const noSpot = buildStraddle(contracts, null, "2026-11-20", now);
assert.equal(noSpot.atmStrike, null);
assert.equal(noSpot.counts.model, 0);
assert.equal(enrichSide(contracts[2], null, t).source, "none");

// ── Strike window ───────────────────────────────────────────────────────────
assert.deepEqual(
  strikesAroundAtm(chain.rows, 0).map((r) => r.strike),
  [355],
);
assert.deepEqual(
  strikesAroundAtm(chain.rows, 1).map((r) => r.strike),
  [100, 355, 360],
);
assert.deepEqual(strikesAroundAtm(chain.rows, 10).length, 3, "clamped at both ends");
assert.equal(strikesAroundAtm(chain.rows, null), chain.rows, "null = all");
assert.equal(strikesAroundAtm(noSpot.rows, 1), noSpot.rows, "no ATM = all");

console.log("options: all assertions passed");
