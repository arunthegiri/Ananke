/**
 * Multi-asset P1 unit test — per-asset session calendars (issue #8).
 *
 * Every instant is built from an ET wall clock via etWallToUtcMs, so the
 * expectations hold on any machine timezone. Both DST regimes are covered:
 * the Globex weekly open is 18:00 ET, which is 22:00 UTC in summer and 23:00
 * UTC in winter — a hardcoded offset would get one of them wrong.
 *
 * Run: npm run test:sessions
 */

import assert from "node:assert/strict";
import { etWallToUtcMs, getSessionInfo } from "../src/lib/market-calendar.ts";
import { isTradingNow, sessionInfoFor } from "../src/lib/session-calendar.ts";

const et = (date, hh, mm = 0) => etWallToUtcMs(date, hh, mm);

function expectGlobex(date, hh, mm, state, nextDate, nextHh, nextState) {
  const info = sessionInfoFor("CME_GLOBEX", et(date, hh, mm));
  const label = `Globex ${date} ${hh}:${String(mm).padStart(2, "0")} ET`;
  assert.equal(info.state, state, `${label} state`);
  assert.equal(info.nextAt, et(nextDate, nextHh), `${label} nextAt`);
  assert.equal(info.nextState, nextState, `${label} nextState`);
}

// ── CME Globex, summer (EDT). 2026-07-05 is a Sunday. ───────────────────────
expectGlobex("2026-07-04", 12, 0, "CLOSED", "2026-07-05", 18, "OPEN"); // Saturday
expectGlobex("2026-07-05", 17, 59, "CLOSED", "2026-07-05", 18, "OPEN"); // Sunday pre-open
expectGlobex("2026-07-05", 18, 0, "OPEN", "2026-07-06", 17, "HALT"); // Sunday open
expectGlobex("2026-07-06", 10, 0, "OPEN", "2026-07-06", 17, "HALT"); // Monday day
expectGlobex("2026-07-06", 17, 30, "HALT", "2026-07-06", 18, "OPEN"); // Monday halt
expectGlobex("2026-07-06", 20, 0, "OPEN", "2026-07-07", 17, "HALT"); // Monday evening
expectGlobex("2026-07-09", 19, 0, "OPEN", "2026-07-10", 17, "CLOSED"); // Thursday evening → Friday close
expectGlobex("2026-07-10", 16, 59, "OPEN", "2026-07-10", 17, "CLOSED"); // Friday
expectGlobex("2026-07-10", 17, 0, "CLOSED", "2026-07-12", 18, "OPEN"); // Friday after close

// The weekly open lands on a different UTC hour in each DST regime.
assert.equal(new Date(et("2026-07-05", 18)).getUTCHours(), 22);
// ── Winter (EST). 2026-12-06 is a Sunday. ───────────────────────────────────
expectGlobex("2026-12-06", 18, 0, "OPEN", "2026-12-07", 17, "HALT");
assert.equal(new Date(et("2026-12-06", 18)).getUTCHours(), 23);

// ── DST transition weekend: Sunday 2026-11-01 (clocks fall back at 02:00). ──
expectGlobex("2026-10-31", 12, 0, "CLOSED", "2026-11-01", 18, "OPEN");
expectGlobex("2026-11-01", 18, 30, "OPEN", "2026-11-02", 17, "HALT");

// ── Crypto never closes. ────────────────────────────────────────────────────
for (const instant of [et("2026-07-04", 3), et("2026-12-25", 12), et("2026-11-01", 1, 30)]) {
  assert.deepEqual(sessionInfoFor("CRYPTO_24_7", instant), {
    calendar: "CRYPTO_24_7",
    state: "OPEN",
    nextAt: null,
    nextState: null,
  });
}

// ── US equity is a pure delegation — byte-identical to market-calendar. ─────
for (const instant of [
  et("2026-07-06", 3),
  et("2026-07-06", 9, 31),
  et("2026-07-06", 17),
  et("2026-07-04", 12),
  et("2026-11-26", 12), // Thanksgiving
]) {
  const base = getSessionInfo(instant);
  const viaCalendar = sessionInfoFor("US_EQUITY", instant);
  assert.equal(viaCalendar.state, base.state);
  assert.equal(viaCalendar.nextAt, base.nextAt);
  assert.equal(viaCalendar.nextState, base.nextState);
}

// ── isTradingNow ────────────────────────────────────────────────────────────
// Sunday evening: futures and crypto trade, equities don't.
const sundayEvening = et("2026-07-05", 20);
assert.equal(isTradingNow("CME_GLOBEX", sundayEvening), true);
assert.equal(isTradingNow("CRYPTO_24_7", sundayEvening), true);
assert.equal(isTradingNow("US_EQUITY", sundayEvening), false);
assert.equal(isTradingNow("CME_GLOBEX", et("2026-07-07", 17, 15)), false); // halt

console.log("session-calendar: all assertions passed");
