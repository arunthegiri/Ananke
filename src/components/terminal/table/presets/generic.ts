import type { CellRender } from "../cells";
import type { ColumnPreset } from "../presets";

/**
 * Columns DERIVED from data, for the Sources explorer (Connectors roadmap §1): any connector
 * tool's result becomes a TerminalTable without a hand-written preset. Still a ColumnPreset[]
 * (rule 5) — just generated. Hand-written presets (presets/sec.ts) stay the better choice for
 * sources with a dedicated panel.
 */

export type GenericRow = Record<string, unknown> & { __id: string };

const MAX_COLUMNS = 10;

/** The first array of objects in a payload (the payload itself, or one level down). */
export function findRows(payload: unknown): { key: string | null; rows: GenericRow[] } | null {
  const asRows = (arr: unknown[]): GenericRow[] | null =>
    arr.length > 0 && arr.every((x) => x && typeof x === "object" && !Array.isArray(x))
      ? arr.map((x, i) => ({ ...(x as Record<string, unknown>), __id: String(i) }))
      : null;
  if (Array.isArray(payload)) {
    const r = asRows(payload);
    return r ? { key: null, rows: r } : null;
  }
  if (payload && typeof payload === "object") {
    for (const [k, v] of Object.entries(payload as Record<string, unknown>)) {
      if (Array.isArray(v)) {
        const r = asRows(v);
        if (r) return { key: k, rows: r };
      }
    }
  }
  return null;
}

const URL_RE = /^https?:\/\//;

function cellFor(v: unknown): CellRender {
  if (v === null || v === undefined || v === "") return { type: "text", value: "—" };
  if (typeof v === "number") return { type: "text", value: v.toLocaleString("en-US"), mono: true };
  if (typeof v === "boolean") return { type: "text", value: v ? "yes" : "no" };
  if (typeof v === "string") {
    if (URL_RE.test(v)) return { type: "link", value: v.replace(URL_RE, "").slice(0, 60), href: v };
    return { type: "text", value: v.length > 140 ? `${v.slice(0, 140)}…` : v };
  }
  if (Array.isArray(v) && v.every((x) => typeof x !== "object" || x === null)) {
    return { type: "chips", values: v.slice(0, 6).map(String) };
  }
  const json = JSON.stringify(v);
  return { type: "text", value: json.length > 140 ? `${json.slice(0, 140)}…` : json, mono: true };
}

function widthFor(key: string, rows: GenericRow[]): number {
  const sample = rows.slice(0, 20).map((r) => {
    const v = r[key];
    return typeof v === "string" ? Math.min(v.length, 60) : typeof v === "number" ? 12 : 16;
  });
  const chars = Math.max(key.length, ...sample);
  return Math.max(72, Math.min(360, chars * 7 + 24));
}

/** Columns from the keys of the first rows, most-populated first, capped at MAX_COLUMNS. */
export function genericColumns(rows: GenericRow[]): ColumnPreset<GenericRow>[] {
  const counts = new Map<string, number>();
  for (const r of rows.slice(0, 50)) {
    for (const [k, v] of Object.entries(r)) {
      if (k === "__id") continue;
      counts.set(k, (counts.get(k) ?? 0) + (v === null || v === undefined || v === "" ? 0 : 1));
    }
  }
  const keys = [...counts.keys()].filter((k) => (counts.get(k) ?? 0) > 0).slice(0, MAX_COLUMNS);
  return keys.map((k) => ({
    id: k,
    header: k.replace(/_/g, " "),
    width: widthFor(k, rows),
    align: rows.some((r) => typeof r[k] === "number") ? "right" : "left",
    cell: (r) => cellFor(r[k]),
    sortValue: (r) => {
      const v = r[k];
      return typeof v === "number" || typeof v === "string" ? v : null;
    },
  }));
}
