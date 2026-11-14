/**
 * Canonical instrument keys — the one identifier every asset class shares
 * (multi-asset P1, issue #8).
 *
 * A key is what goes in the URL (`?symbol=`), what the backend's
 * `instruments.key` column stores, and what every future broker adapter maps
 * its own symbology to and from. Keys are uppercase, contain no spaces (they
 * live in URLs), and parse unambiguously by shape alone:
 *
 *   equity / ETF     AAPL, BRK.B                (ETF vs equity is metadata, not syntax)
 *   index            ^SPX
 *   option           AAPL261218C00200000        (compact OCC: root, YYMMDD, C|P, strike×1000)
 *   future           ESZ26@CME                  (root, month code, 2-digit year, @exchange)
 *   continuous fut.  ES1!                       (root, rank, '!'; an @exchange suffix
 *                                               is accepted and dropped from the key)
 *   crypto           BTC/USD
 *
 * Futures require the `@exchange` suffix: "ESZ26" alone would be shaped like
 * an equity ticker, and a parser that guessed would route a futures order to
 * the equity desk. Mirrors `com.stocktracker.instrument.InstrumentKey` — keep
 * the two in lockstep (tests/instruments.test.mjs and InstrumentKeyTest share
 * cases).
 *
 * Pure and deterministic: no Date.now(), no runtime timezone — safe inside
 * validateSearch (CLAUDE.md rule 2).
 */

export type AssetClass =
  | "EQUITY"
  | "ETF"
  | "INDEX"
  | "OPTION"
  | "FUTURE"
  | "FUTURE_OPTION"
  | "CRYPTO"
  | "EVENT_CONTRACT";

export type SessionCalendarId = "US_EQUITY" | "CME_GLOBEX" | "CRYPTO_24_7";

export type OptionRight = "C" | "P";

/** Futures month codes, January → December. */
export const FUTURES_MONTH_CODES = [
  "F",
  "G",
  "H",
  "J",
  "K",
  "M",
  "N",
  "Q",
  "U",
  "V",
  "X",
  "Z",
] as const;

export type ParsedInstrumentKey =
  | { kind: "EQUITY"; key: string; symbol: string }
  | { kind: "INDEX"; key: string; symbol: string }
  | {
      kind: "OPTION";
      key: string;
      underlying: string;
      /** Expiry as an ISO calendar date (YYYY-MM-DD). */
      expiry: string;
      right: OptionRight;
      strike: number;
    }
  | {
      kind: "FUTURE";
      key: string;
      root: string;
      /** 1–12. */
      month: number;
      year: number;
      exchange: string;
    }
  | { kind: "CONTINUOUS_FUTURE"; key: string; root: string; rank: number; exchange: string | null }
  | { kind: "CRYPTO"; key: string; base: string; quote: string };

// US equity tickers are letters only, plus an optional share-class suffix
// (BRK.B, BF-B). No digits — that is what keeps a bare "ESZ26" from being
// mistaken for a stock.
const EQUITY_RE = /^[A-Z]{1,6}(?:[.-][A-Z]{1,2})?$/;
const INDEX_RE = /^\^[A-Z0-9.]{1,10}$/;
const OPTION_RE = /^([A-Z][A-Z0-9.]{0,5})(\d{2})(\d{2})(\d{2})([CP])(\d{8})$/;
// Root is non-greedy so "MESZ26" resolves to MES/Z/26, and the two-digit year
// anchors backtracking so "NQH27" is NQ/H/27, not N/Q/H2….
const FUTURE_RE = /^([A-Z0-9]{1,4}?)([FGHJKMNQUVXZ])(\d{2})@([A-Z]{2,8})$/;
const CONTINUOUS_RE = /^([A-Z0-9]{1,4})([1-9])!(?:@([A-Z]{2,8}))?$/;
const CRYPTO_RE = /^([A-Z0-9]{2,10})\/([A-Z0-9]{2,6})$/;

function pad2(n: number): string {
  return String(n).padStart(2, "0");
}

/** Uppercase, trim, and collapse a padded 21-char OCC symbol to the compact form. */
export function normalizeInstrumentKey(raw: string): string {
  const s = raw.trim().toUpperCase();
  // Padded OCC: 6-char space-padded root + 15 chars. Spaces never survive in a key.
  if (/^[A-Z][A-Z0-9. ]{5}\d{6}[CP]\d{8}$/.test(s)) return s.replace(/ /g, "");
  return s;
}

/** Parse a key, or `null` if it matches no asset-class shape. */
export function parseInstrumentKey(raw: string): ParsedInstrumentKey | null {
  const key = normalizeInstrumentKey(raw);
  if (key === "") return null;

  let m: RegExpExecArray | null;

  if ((m = OPTION_RE.exec(key))) {
    const [, underlying, yy, mm, dd, right, strikeMilli] = m;
    const month = Number(mm);
    const day = Number(dd);
    if (month < 1 || month > 12) return null;
    const year = 2000 + Number(yy);
    // Real calendar date only (rejects Feb 30) — UTC calendar math, no timezone involved.
    const probe = new Date(Date.UTC(year, month - 1, day));
    if (probe.getUTCMonth() !== month - 1 || probe.getUTCDate() !== day) return null;
    return {
      kind: "OPTION",
      key,
      underlying,
      expiry: `20${yy}-${mm}-${dd}`,
      right: right as OptionRight,
      strike: Number(strikeMilli) / 1000,
    };
  }
  if ((m = FUTURE_RE.exec(key))) {
    const [, root, code, yy, exchange] = m;
    return {
      kind: "FUTURE",
      key,
      root,
      month: FUTURES_MONTH_CODES.indexOf(code as (typeof FUTURES_MONTH_CODES)[number]) + 1,
      year: 2000 + Number(yy),
      exchange,
    };
  }
  if ((m = CONTINUOUS_RE.exec(key))) {
    const [, root, rank, exchange] = m;
    // Canonical continuous key drops the exchange: "ES1!@CME" and "ES1!" are
    // the same series and must not become two instrument rows.
    return {
      kind: "CONTINUOUS_FUTURE",
      key: `${root}${rank}!`,
      root,
      rank: Number(rank),
      exchange: exchange ?? null,
    };
  }
  if ((m = CRYPTO_RE.exec(key))) {
    return { kind: "CRYPTO", key, base: m[1], quote: m[2] };
  }
  if (INDEX_RE.test(key)) return { kind: "INDEX", key, symbol: key.slice(1) };
  if (EQUITY_RE.test(key)) return { kind: "EQUITY", key, symbol: key };
  return null;
}

export function isValidInstrumentKey(raw: string): boolean {
  return parseInstrumentKey(raw) !== null;
}

/** Compact OCC key for an option contract. Strike is rounded to 1/1000. */
export function optionKey(
  underlying: string,
  expiry: string,
  right: OptionRight,
  strike: number,
): string {
  const [y, mo, d] = expiry.split("-");
  const milli = Math.round(strike * 1000);
  if (!Number.isFinite(milli) || milli < 0 || milli > 99_999_999) {
    throw new RangeError(`strike out of OCC range: ${strike}`);
  }
  return `${underlying.toUpperCase()}${y.slice(2)}${mo}${d}${right}${String(milli).padStart(8, "0")}`;
}

/** Dated futures key, e.g. futureKey("ES", 12, 2026, "CME") → "ESZ26@CME". */
export function futureKey(root: string, month: number, year: number, exchange: string): string {
  const code = FUTURES_MONTH_CODES[month - 1];
  if (!code) throw new RangeError(`futures month out of range: ${month}`);
  return `${root.toUpperCase()}${code}${pad2(year % 100)}@${exchange.toUpperCase()}`;
}

/**
 * Asset class implied by a key's shape. Equity-shaped keys report "EQUITY";
 * whether one is actually an ETF is instrument metadata from the backend.
 * Continuous futures report "FUTURE".
 */
export function assetClassOfKey(raw: string): AssetClass | null {
  const p = parseInstrumentKey(raw);
  if (!p) return null;
  switch (p.kind) {
    case "CONTINUOUS_FUTURE":
      return "FUTURE";
    default:
      return p.kind;
  }
}

/** Which trading-hours calendar governs an asset class (see lib/session-calendar.ts). */
export function sessionCalendarFor(assetClass: AssetClass): SessionCalendarId {
  switch (assetClass) {
    case "FUTURE":
    case "FUTURE_OPTION":
      return "CME_GLOBEX";
    case "CRYPTO":
      return "CRYPTO_24_7";
    default:
      return "US_EQUITY";
  }
}

/**
 * An instrument as the backend describes it (`GET /api/instruments/{key}`).
 * Optional blocks are populated per asset class.
 */
export interface Instrument {
  id: number;
  key: string;
  assetClass: AssetClass;
  symbol: string;
  name: string | null;
  exchange: string | null;
  currency: string;
  /** Contract multiplier: 1 for shares/crypto, 100 for equity options, 50 for ES. */
  multiplier: number;
  tickSize: number | null;
  sessionCalendar: SessionCalendarId;
  underlyingKey: string | null;
  option: { expiry: string; right: OptionRight; strike: number } | null;
  future: { root: string; contractMonth: string; lastTradeDate: string | null } | null;
  crypto: { base: string; quote: string } | null;
  active: boolean;
}
