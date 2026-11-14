/**
 * Multi-asset P1 unit test — canonical instrument keys (issue #8).
 *
 * The parse cases below are mirrored in the backend's InstrumentKeyTest; a key
 * that parses differently on the two sides is a routing bug, so change both.
 *
 * Run: npm run test:instruments
 */

import assert from "node:assert/strict";
import {
  assetClassOfKey,
  futureKey,
  normalizeInstrumentKey,
  optionKey,
  parseInstrumentKey,
  sessionCalendarFor,
} from "../src/lib/instruments.ts";

// ── Shape → kind ─────────────────────────────────────────────────────────────
const kinds = {
  AAPL: "EQUITY",
  "BRK.B": "EQUITY",
  "BF-B": "EQUITY",
  "^SPX": "INDEX",
  AAPL261218C00200000: "OPTION",
  "ESZ26@CME": "FUTURE",
  "MESZ26@CME": "FUTURE",
  "6EH27@CME": "FUTURE",
  "ES1!": "CONTINUOUS_FUTURE",
  "NQ2!@CME": "CONTINUOUS_FUTURE",
  "BTC/USD": "CRYPTO",
};
for (const [key, kind] of Object.entries(kinds)) {
  assert.equal(parseInstrumentKey(key)?.kind, kind, `${key} should parse as ${kind}`);
}

// Rejected shapes: no exchange on a dated future, garbage, empty, bad OCC month.
for (const bad of [
  "ESZ26",
  "",
  "   ",
  "aapl!!",
  "AAPL261318C00200000",
  "AAPL270230C00200000",
  "BTC/",
  "/USD",
]) {
  assert.equal(parseInstrumentKey(bad), null, `${JSON.stringify(bad)} should not parse`);
}

// ── Normalisation ───────────────────────────────────────────────────────────
assert.equal(normalizeInstrumentKey("  aapl "), "AAPL");
assert.equal(normalizeInstrumentKey("btc/usd"), "BTC/USD");
// Padded 21-char OCC collapses to the compact URL-safe form.
assert.equal(normalizeInstrumentKey("AAPL  261218C00200000"), "AAPL261218C00200000");
assert.equal(normalizeInstrumentKey("SPY   270115P00450500"), "SPY270115P00450500");

// ── Field extraction ────────────────────────────────────────────────────────
assert.deepEqual(parseInstrumentKey("AAPL  261218C00200000"), {
  kind: "OPTION",
  key: "AAPL261218C00200000",
  underlying: "AAPL",
  expiry: "2026-12-18",
  right: "C",
  strike: 200,
});
assert.equal(parseInstrumentKey("SPY270115P00450500").strike, 450.5);

// Non-greedy root: month-code letters inside the root resolve correctly.
assert.deepEqual(parseInstrumentKey("MESZ26@CME"), {
  kind: "FUTURE",
  key: "MESZ26@CME",
  root: "MES",
  month: 12,
  year: 2026,
  exchange: "CME",
});
const nq = parseInstrumentKey("NQH27@CME");
assert.equal(nq.root, "NQ");
assert.equal(nq.month, 3);
assert.equal(nq.year, 2027);
assert.equal(parseInstrumentKey("GCQ26@COMEX").root, "GC");
assert.equal(parseInstrumentKey("6EH27@CME").root, "6E");

assert.deepEqual(parseInstrumentKey("ES1!"), {
  kind: "CONTINUOUS_FUTURE",
  key: "ES1!",
  root: "ES",
  rank: 1,
  exchange: null,
});
assert.deepEqual(parseInstrumentKey("nq2!@cme"), {
  kind: "CONTINUOUS_FUTURE",
  key: "NQ2!",
  root: "NQ",
  rank: 2,
  exchange: "CME",
});
assert.deepEqual(parseInstrumentKey("eth/usdt"), {
  kind: "CRYPTO",
  key: "ETH/USDT",
  base: "ETH",
  quote: "USDT",
});

// ── Builders round-trip through the parser ──────────────────────────────────
assert.equal(optionKey("aapl", "2026-12-18", "C", 200), "AAPL261218C00200000");
assert.equal(optionKey("SPY", "2027-01-15", "P", 450.5), "SPY270115P00450500");
assert.throws(() => optionKey("SPY", "2027-01-15", "P", 1e9), RangeError);
assert.equal(futureKey("es", 12, 2026, "cme"), "ESZ26@CME");
assert.throws(() => futureKey("ES", 13, 2026, "CME"), RangeError);
for (let month = 1; month <= 12; month++) {
  const p = parseInstrumentKey(futureKey("CL", month, 2027, "NYMEX"));
  assert.equal(p.month, month);
  assert.equal(p.year, 2027);
}

// ── Asset class + calendar routing ──────────────────────────────────────────
assert.equal(assetClassOfKey("ES1!"), "FUTURE");
assert.equal(assetClassOfKey("AAPL261218C00200000"), "OPTION");
assert.equal(assetClassOfKey("nonsense!!"), null);
assert.equal(sessionCalendarFor("EQUITY"), "US_EQUITY");
assert.equal(sessionCalendarFor("OPTION"), "US_EQUITY");
assert.equal(sessionCalendarFor("FUTURE"), "CME_GLOBEX");
assert.equal(sessionCalendarFor("CRYPTO"), "CRYPTO_24_7");

console.log("instruments: all assertions passed");
