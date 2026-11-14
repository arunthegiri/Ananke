/**
 * Per-asset-class trading-hours calendars (multi-asset P1, issue #8).
 *
 * `market-calendar.ts` remains the NYSE source of truth; this file dispatches
 * on a calendar id so futures and crypto stop inheriting equity hours:
 *
 *   US_EQUITY    delegates to getSessionInfo (PRE / OPEN / POST / CLOSED).
 *   CME_GLOBEX   Sunday 18:00 ET → Friday 17:00 ET, with a daily 17:00–18:00
 *                maintenance HALT Monday–Thursday. This is the equity-index /
 *                rates / FX / metals schedule. CME holiday schedules (mostly
 *                early halts, not full closures) are NOT modelled yet, nor are
 *                the shorter ags/livestock sessions. Acceptable for a status
 *                rail; a futures order ticket must not rely on this alone.
 *   CRYPTO_24_7  always OPEN; there is no next transition.
 *
 * Same DST rule as market-calendar.ts: all ET math goes through Intl via
 * etWallClock / etWallToUtcMs, never a hardcoded offset.
 */

import type { SessionCalendarId } from "./instruments.ts";
import {
  addEtDays,
  etWallClock,
  etWallToUtcMs,
  getSessionInfo,
  type SessionState,
} from "./market-calendar.ts";

export type CalendarState = SessionState | "HALT";

export interface CalendarSessionInfo {
  calendar: SessionCalendarId;
  state: CalendarState;
  /** Epoch ms of the next transition, or null when there is none (crypto). */
  nextAt: number | null;
  nextState: CalendarState | null;
}

const GLOBEX_HALT = 17 * 60; // 17:00 ET
const GLOBEX_REOPEN = 18 * 60; // 18:00 ET

function globex(nowMs: number): CalendarSessionInfo {
  const { date, minutes, weekday } = etWallClock(nowMs);
  const at = (d: string, hh: number) => etWallToUtcMs(d, hh, 0);
  const info = (state: CalendarState, nextAt: number, nextState: CalendarState) => ({
    calendar: "CME_GLOBEX" as const,
    state,
    nextAt,
    nextState,
  });

  switch (weekday) {
    case 6: // Saturday — closed until Sunday's open.
      return info("CLOSED", at(addEtDays(date, 1), 18), "OPEN");
    case 0: // Sunday — opens at 18:00, then trades into Monday.
      return minutes < GLOBEX_REOPEN
        ? info("CLOSED", at(date, 18), "OPEN")
        : info("OPEN", at(addEtDays(date, 1), 17), "HALT");
    case 5: // Friday — the 17:00 halt is the weekly close.
      return minutes < GLOBEX_HALT
        ? info("OPEN", at(date, 17), "CLOSED")
        : info("CLOSED", at(addEtDays(date, 2), 18), "OPEN");
    default: {
      // Monday–Thursday.
      if (minutes < GLOBEX_HALT) return info("OPEN", at(date, 17), "HALT");
      if (minutes < GLOBEX_REOPEN) return info("HALT", at(date, 18), "OPEN");
      // Evening session runs into tomorrow's 17:00, which is the weekly close on Friday.
      return info("OPEN", at(addEtDays(date, 1), 17), weekday === 4 ? "CLOSED" : "HALT");
    }
  }
}

/** Session state + next transition for an instant under a calendar. */
export function sessionInfoFor(calendar: SessionCalendarId, nowMs: number): CalendarSessionInfo {
  switch (calendar) {
    case "US_EQUITY": {
      const s = getSessionInfo(nowMs);
      return { calendar, state: s.state, nextAt: s.nextAt, nextState: s.nextState };
    }
    case "CME_GLOBEX":
      return globex(nowMs);
    case "CRYPTO_24_7":
      return { calendar, state: "OPEN", nextAt: null, nextState: null };
  }
}

/** True when the calendar has continuous trading at `nowMs` (equity: regular hours only). */
export function isTradingNow(calendar: SessionCalendarId, nowMs: number): boolean {
  return sessionInfoFor(calendar, nowMs).state === "OPEN";
}
